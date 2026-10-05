package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Órdenes como root con su (Magisk, KernelSU…): solo con la autorización que da el gestor de root. */
object RootShell {
    /** Entre comillas simples para el shell, también con espacios o comillas en el nombre. */
    fun q(value: String): String {
        if (value.contains('\u0000')) throw IOException(tr("Ruta no válida"))
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun run(script: String, input: InputStream? = null): String {
        val process =
            try {
                ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            } catch (e: IOException) {
                throw IOException(tr("Root no concedido. Se necesita un dispositivo con su/Magisk y autorización del usuario."), e)
            }
        try {
            process.outputStream.use { out -> input?.copyTo(out) }
            val result = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0) throw IOException(result.take(1000).trim().ifBlank { tr("Operación root rechazada") })
            return result
        } finally {
            process.destroy()
        }
    }

    fun requireRoot() {
        if (runCatching { run("id -u").trim() }.getOrNull() != "0")
            throw IOException(tr("Root no concedido. Se necesita un dispositivo con su/Magisk y autorización del usuario."))
    }
}

/**
 * Funciones de sistema que en ES piden root: quitar apps del sistema, montar el sistema en lectura y
 * escritura y editar el archivo hosts.
 */
object RootSystem {
    const val HOSTS = "/system/etc/hosts"

    /** Copia editable del hosts; se monta encima del original (como los módulos de hosts de Magisk). */
    const val HOSTS_COPY = "/data/adb/oi-archivos/hosts"

    /**
     * Quita una app del sistema para el usuario actual (pm uninstall -k --user 0). El APK sigue en
     * /system, así que se puede devolver con [restoreSystemApp]; es lo que se puede hacer sin romper
     * la verificación del sistema en los Android actuales.
     */
    fun removeSystemApp(pkg: String) {
        Adb.requirePackageName(pkg)
        val out = RootShell.run("pm uninstall -k --user 0 $pkg")
        if (!out.contains("Success")) throw IOException(out.trim().ifEmpty { tr("No se pudo desinstalar") })
    }

    fun restoreSystemApp(pkg: String) {
        Adb.requirePackageName(pkg)
        RootShell.run("cmd package install-existing --user 0 $pkg")
    }

    /** Apps del sistema quitadas para este usuario, que se pueden devolver. */
    fun removedSystemApps(ctx: Context): List<String> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val all = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        return all.filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.flags and ApplicationInfo.FLAG_INSTALLED == 0 }
            .map { it.packageName }
            .sorted()
    }

    /**
     * Punto de montaje que contiene [path] según /proc/mounts y si está en solo lectura. Gana el
     * punto más largo y, si hay varios montajes en el mismo punto, el último (el que se ve).
     */
    fun mountOf(path: String, mounts: String = File("/proc/mounts").readText()): Pair<String, Boolean>? =
        mounts.lines()
            .map { it.split(' ') }
            .filter { it.size >= 4 }
            .map { it[1].replace("\\040", " ") to it[3].split(',').contains("ro") }
            .filter { (point, _) -> path == point || point == "/" || path.startsWith("$point/") }
            .withIndex()
            .maxWithOrNull(compareBy({ it.value.first.length }, { it.index }))
            ?.value

    /** Monta en lectura y escritura (o vuelve a solo lectura) la partición del sistema. */
    fun remountSystem(writable: Boolean): String {
        val point = mountOf("/system")?.first ?: "/"
        val out = runCatching { RootShell.run("mount -o remount,${if (writable) "rw" else "ro"} ${RootShell.q(point)}") }
        val readOnly = runCatching { RootShell.run("cat /proc/mounts") }.getOrNull()?.let { mountOf("/system", it)?.second }
        if (readOnly == !writable) return point
        val reason = out.exceptionOrNull()?.message ?: tr("el sistema sigue igual")
        throw IOException(
            if (writable) tr("Android no dejó montar {0} en lectura y escritura: {1}", point, reason)
            else tr("No se pudo volver a montar {0} en solo lectura: {1}", point, reason))
    }

    /** Errores de un archivo hosts: líneas que no son «IP nombre…» ni comentarios. */
    fun hostsErrors(text: String): List<String> {
        val ipv4 = Regex("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")
        val ipv6 = Regex("[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*(%\\w+)?")
        val name = Regex("[A-Za-z0-9_]([A-Za-z0-9_.-]*[A-Za-z0-9_])?")
        return text.lines().mapIndexedNotNull { i, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            val parts = line.split(Regex("\\s+"))
            val okIp = ipv4.matches(parts[0]) || ipv6.matches(parts[0])
            val okNames = parts.size >= 2 && parts.drop(1).all { name.matches(it) && it.length <= 253 }
            if (okIp && okNames) null else tr("Línea {0}: «{1}»", i + 1, raw.trim().take(60))
        }
    }

    fun readHosts(): String = File(HOSTS).readText()

    /**
     * Guarda el hosts. Si el sistema está montado en escritura se cambia el original; si no, se
     * monta encima una copia en /data/adb (dura hasta reiniciar; con Magisk, un módulo de hosts la
     * hace permanente).
     */
    fun saveHosts(text: String) {
        val errors = hostsErrors(text)
        if (errors.isNotEmpty()) throw IOException(tr("El hosts tiene líneas no válidas:\n{0}", errors.take(5).joinToString("\n")))
        val content = if (text.endsWith("\n")) text else text + "\n"
        val h = RootShell.q(HOSTS)
        val c = RootShell.q(HOSTS_COPY)
        RootShell.run(
            """
            set -e
            if grep -q ' $HOSTS ' /proc/mounts; then cat > $c
            elif [ -w $h ] && touch $h 2>/dev/null; then cat > $h
            else
              mkdir -p "${'$'}(dirname $c)"
              cat > $c.tmp
              chmod 644 $c.tmp
              chcon u:object_r:system_file:s0 $c.tmp 2>/dev/null || true
              mv -f $c.tmp $c
              mount -o bind $c $h
            fi
            """.trimIndent(),
            content.byteInputStream())
        if (RootShell.run("cat $h") != content) throw IOException(tr("Android no aplicó el nuevo hosts"))
    }

    /** Quita la copia montada encima y vuelve al hosts original. */
    fun restoreHosts() {
        RootShell.run(
            "while grep -q ' $HOSTS ' /proc/mounts; do umount ${RootShell.q(HOSTS)} || break; done; rm -f ${RootShell.q(HOSTS_COPY)}")
    }

    fun hostsReplaced(): Boolean = runCatching { File("/proc/mounts").readText().contains(" $HOSTS ") }.getOrDefault(false)
}
