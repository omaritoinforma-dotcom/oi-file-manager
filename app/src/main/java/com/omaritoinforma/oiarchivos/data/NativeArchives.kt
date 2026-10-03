package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Bundled official 7-Zip; stdout extraction keeps destination decisions in SafeFiles. */
object NativeArchives {
    @Volatile var executable: File? = null
    val available: Boolean
        get() = executable?.canExecute() == true

    fun list(file: File, password: String): List<ArchiveEntry> {
        start(
                listOf(
                    "l", "-slt", "-ba", "-bd", "-sccUTF-8", "-p$password", "--", file.absolutePath))
            .use { process ->
                val text = process.readText(64 * 1024 * 1024)
                process.check()
                return parseListing(text)
            }
    }

    internal fun parseListing(text: String): List<ArchiveEntry> {
        val result = ArrayList<ArchiveEntry>()
        var fields = linkedMapOf<String, String>()
        val seen = hashSetOf<String>()
        fun finish() {
            if (fields.isEmpty()) return
            val name = fields["Path"] ?: throw IOException("Listado del comprimido no válido")
            // Reject ambiguous line-delimited names, links, and all traversal before starting
            // writes.
            if (name.any { it.code < 32 } ||
                fields["Symbolic Link"]?.isNotEmpty() == true ||
                fields["Hard Link"]?.isNotEmpty() == true ||
                fields["Attributes"]?.contains("lrwx") == true)
                throw IOException("Enlaces y nombres especiales no se extraen")
            SafeFiles.archiveTarget(
                File(System.getProperty("java.io.tmpdir") ?: "/tmp", "oi-check"), name)
            if (!seen.add(name.replace('\\', '/').trimEnd('/')))
                throw IOException("Entrada repetida en el comprimido")
            val size = fields["Size"]?.toLongOrNull() ?: 0L
            if (size < 0 || size > ArchiveTools.MAX_BYTES)
                throw IOException("Entrada demasiado grande")
            result +=
                ArchiveEntry(
                    name,
                    size,
                    fields["Folder"] == "+" || fields["Attributes"]?.startsWith("D") == true)
            if (result.size > 100000) throw IOException("Demasiadas entradas en el comprimido")
            fields = linkedMapOf()
        }
        for (line in text.lineSequence()) {
            if (line.isBlank()) {
                finish()
                continue
            }
            val split = line.indexOf(" = ")
            if (split < 1) throw IOException("Nombre de archivo o listado no compatible")
            val key = line.substring(0, split)
            if (fields.put(key, line.substring(split + 3)) != null)
                throw IOException("Listado ambiguo en el comprimido")
        }
        finish()
        return result
    }

    suspend fun <T> withEntry(
        file: File,
        password: String,
        entry: String,
        consume: suspend (InputStream) -> T
    ): T = coroutineScope {
        start(
                listOf(
                    "x", "-so", "-spd", "-y", "-bd", "-p$password", "--", file.absolutePath, entry))
            .use { process ->
                val cancel =
                    launch(Dispatchers.IO) {
                        try {
                            awaitCancellation()
                        } finally {
                            process.close()
                        }
                    }
                try {
                    val result = consume(process.stdout)
                    currentCoroutineContext().ensureActive()
                    process.check()
                    result
                } finally {
                    cancel.cancel()
                    process.close()
                }
            }
    }

    suspend fun compress(
        sources: List<File>,
        target: File,
        password: String,
        report: (OpProgress) -> Unit
    ) = coroutineScope {
        if (sources.isEmpty()) throw IOException("Selecciona archivos para comprimir")
        val parent =
            sources.first().canonicalFile.parentFile ?: throw IOException("Carpeta no válida")
        if (sources.any { it.canonicalFile.parentFile != parent })
            throw IOException("Para crear un 7z, selecciona archivos de la misma carpeta")
        for (root in sources) {
            SafeFiles.requireRegular(root)
            for (entry in SafeFiles.walk(root)) {
                ensureActive()
                if (entry.canonicalFile == target.canonicalFile)
                    throw IOException("El comprimido no puede incluirse a sí mismo")
            }
        }
        val temp = File.createTempFile(".oi-7z-", ".7z", target.parentFile)
        temp.delete()
        try {
            val args = mutableListOf("a", "-t7z", "-m0=LZMA2", "-mx=5", "-bd", "-y", "-sccUTF-8")
            if (password.isNotEmpty()) args += listOf("-mhe=on", "-p$password")
            args += listOf("--", temp.absolutePath)
            args += sources.map { it.name }
            report(OpProgress("Comprimiendo 7z", sources.first().name))
            start(args, parent).use { process ->
                val cancel =
                    launch(Dispatchers.IO) {
                        try {
                            awaitCancellation()
                        } finally {
                            process.close()
                        }
                    }
                try {
                    process.readText(1024 * 1024)
                    process.check()
                } finally {
                    cancel.cancel()
                    process.close()
                }
            }
            ensureActive()
            SafeFiles.commit(temp, target, false)
            report(OpProgress("7z creado", target.name, 1, 1))
        } finally {
            temp.delete()
        }
    }

    private fun start(arguments: List<String>, directory: File? = null): Running {
        val exe =
            executable?.takeIf { it.canExecute() }
                ?: throw IOException("El motor de comprimidos no está instalado")
        return Running(
            ProcessBuilder(listOf(exe.absolutePath) + arguments).directory(directory).start())
    }

    private class Running(private val process: Process) : Closeable {
        val stdout: InputStream = process.inputStream
        private val errors = ByteArrayOutputStream()
        private val stderr =
            Thread {
                    try {
                        process.errorStream.use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (errors.size() < 8192)
                                    errors.write(buffer, 0, minOf(n, 8192 - errors.size()))
                            }
                        }
                    } catch (_: IOException) {}
                }
                .apply {
                    isDaemon = true
                    start()
                }
        // Bounds malformed files that make a synchronous listing stop responding.
        private val timer =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "Archive timeout").apply { isDaemon = true }
            }

        init {
            process.outputStream.close()
            timer.schedule({ process.destroyForcibly() }, 10, TimeUnit.MINUTES)
        }

        fun readText(limit: Int): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = stdout.read(buffer)
                if (n < 0) break
                if (out.size() > limit - n)
                    throw IOException("Listado del comprimido demasiado grande")
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        }

        fun check() {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("El motor de comprimidos no respondió")
            }
            stderr.join(1000)
            if (process.exitValue() != 0)
                throw IOException(
                    "No se pudo leer o crear el comprimido. Comprueba su integridad y contraseña.")
        }

        override fun close() {
            timer.shutdownNow()
            if (process.isAlive) process.destroyForcibly()
            try {
                stdout.close()
            } catch (_: IOException) {}
        }
    }
}
