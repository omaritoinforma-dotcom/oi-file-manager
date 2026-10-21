package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Bounded private queue. A submitted APK is a snapshot, not a mutable shared path. */
class PendingUserActionStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val maxPending: Int = 4,
) {
    data class Request(val id: String, val action: String, val packageName: String,
                       val remote: Boolean, val createdAt: Long, val apk: File?, val sha256: String)
    companion object {
        private val lock = Any()
        private val idPattern = Regex("[a-f0-9]{32}")
        const val TTL_MS = 60L * 60 * 1000
    }
    private fun metadata(id: String): File {
        if (!idPattern.matches(id)) throw IOException("Solicitud no válida")
        return File(root, "$id.properties")
    }
    private fun remove(id: String) {
        metadata(id).delete(); File(root, "$id.apk").delete(); File(root, "$id.part").delete()
    }
    private fun prune() {
        root.listFiles().orEmpty().filter { it.extension == "properties" }.forEach { file ->
            val id = file.nameWithoutExtension
            if (idPattern.matches(id)) {
                val created = runCatching { Properties().apply { file.inputStream().use { load(it) } }
                    .getProperty("createdAt").toLong() }.getOrDefault(0)
                if (created > now() || now() - created >= TTL_MS) remove(id)
            }
        }
        // Recover interrupted staging after a process death without retaining orphan APKs forever.
        root.listFiles().orEmpty().filter { it.extension in setOf("apk", "part") && idPattern.matches(it.nameWithoutExtension) }
            .filter { !metadata(it.nameWithoutExtension).exists() && now() - it.lastModified() >= TTL_MS }
            .forEach { it.delete() }
    }
    fun pendingIds(): List<String> = synchronized(lock) {
        prune()
        root.listFiles().orEmpty().filter { it.extension == "properties" && idPattern.matches(it.nameWithoutExtension) }
            .sortedBy { it.lastModified() }.map { it.nameWithoutExtension }
    }
    fun stageInstall(source: File, expectedPackage: String = "", remote: Boolean = true): Request =
        stage("install", expectedPackage, remote, source)
    fun stageUninstall(packageName: String, remote: Boolean = true): Request =
        stage("uninstall", DebugCommands.packageName(packageName), remote, null)
    private fun stage(action: String, pkg: String, remote: Boolean, source: File?): Request = synchronized(lock) {
        if (pkg.isNotBlank()) DebugCommands.packageName(pkg)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("No se pudo preparar la solicitud")
        prune()
        if (pendingIds().size >= maxPending) throw IOException("Revisa las solicitudes pendientes antes de enviar otra")
        val id = UUID.randomUUID().toString().replace("-", "")
        val part = File(root, "$id.part"); val apk = File(root, "$id.apk")
        try {
            var hash = ""
            if (source != null) {
                if (!source.isFile || source.length() > maxBytes) throw IOException("APK no accesible o superior a 256 MiB")
                val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
                source.inputStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("La APK supera el límite")
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                } }
                if (!part.renameTo(apk)) throw IOException("No se pudo conservar la APK")
                hash = digest.digest().joinToString("") { "%02x".format(it) }
                apk.setReadOnly()
            }
            val created = now()
            Properties().apply {
                setProperty("action", action); setProperty("package", pkg)
                setProperty("remote", remote.toString()); setProperty("createdAt", created.toString())
                setProperty("sha256", hash)
            }.also { props -> part.outputStream().use { props.store(it, null) } }
            if (!part.renameTo(metadata(id))) throw IOException("No se pudo guardar la solicitud")
            Request(id, action, pkg, remote, created, apk.takeIf { source != null }, hash)
        } catch (error: Exception) { remove(id); throw error }
    }
    fun load(id: String): Request = synchronized(lock) {
        prune()
        val file = metadata(id)
        if (!file.isFile) throw IOException("La solicitud caducó o ya no existe; vuelve a enviarla")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val action = props.getProperty("action")
        if (action !in setOf("install", "uninstall")) throw IOException("Solicitud no válida")
        val apk = File(root, "$id.apk").takeIf { action == "install" }
        val hash = props.getProperty("sha256", "")
        if (apk != null) {
            if (!apk.isFile || apk.length() > maxBytes) throw IOException("La APK pendiente no está disponible")
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; if (total > maxBytes) throw IOException("La APK supera el límite")
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != hash)
                throw IOException("La APK pendiente cambió; vuelve a enviarla")
        }
        Request(id, action, props.getProperty("package", ""), props.getProperty("remote") == "true",
            props.getProperty("createdAt").toLong(), apk, hash)
    }
    fun discard(id: String) = synchronized(lock) { remove(id) }
}
