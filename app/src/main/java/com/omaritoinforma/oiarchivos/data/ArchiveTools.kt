package com.omaritoinforma.oiarchivos.data

import com.github.junrar.Archive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

data class ArchiveEntry(val name: String, val size: Long, val directory: Boolean)

object ArchiveTools {
    const val MAX_BYTES = 64L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 100000
    private val zipTypes = setOf("zip", "apk", "jar", "apks")

    fun supports(file: File) =
        file.extension.lowercase() in zipTypes + setOf("7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

    fun list(file: File, password: String = ""): List<ArchiveEntry> =
        when (file.extension.lowercase()) {
            in zipTypes ->
                ZipFile(file, password.toCharArray()).use { zip ->
                    zip.fileHeaders.take(MAX_ENTRIES).map {
                        ArchiveEntry(it.fileName, it.uncompressedSize, it.isDirectory)
                    }
                }
            "7z" ->
                seven(file, password).use { z ->
                    z.entries.take(MAX_ENTRIES).map {
                        ArchiveEntry(it.name, it.size, it.isDirectory)
                    }
                }
            "rar" ->
                Archive(file).use { a ->
                    a.fileHeaders.take(MAX_ENTRIES).map {
                        ArchiveEntry(it.fileNameString, it.fullUnpackSize, it.isDirectory)
                    }
                }
            else ->
                tarOrSingle(file).use { stream ->
                    if (isTar(file)) {
                        TarArchiveInputStream(stream).use { tar ->
                            val entries = mutableListOf<ArchiveEntry>()
                            while (entries.size < MAX_ENTRIES) {
                                val e = tar.nextTarEntry ?: break
                                entries += ArchiveEntry(e.name, e.size, e.isDirectory)
                            }
                            entries
                        }
                    } else listOf(ArchiveEntry(file.name.substringBeforeLast('.'), -1, false))
                }
        }

    suspend fun compress(
        sources: List<File>,
        target: File,
        password: String,
        report: (OpProgress) -> Unit
    ) {
        if (target.extension.lowercase() == "7z" ||
            target.name.lowercase().endsWith(".tar") ||
            target.name.lowercase().endsWith(".tar.gz")) {
            if (password.isNotEmpty())
                throw IOException("La contraseña de creación está disponible para ZIP")
            compressOther(sources, target, report)
            return
        }
        val t = Tracker("Comprimiendo", report)
        val (bytes, count) = FileOps.measure(sources)
        t.totalBytes = bytes
        t.totalFiles = count
        val temp = File.createTempFile(".oi-zip-", ".tmp", target.parentFile)
        try {
            ZipOutputStream(temp.outputStream().buffered(), password.toCharArray()).use { out ->
                for (root in sources) {
                    SafeFiles.requireRegular(root)
                    for (f in SafeFiles.walk(root)) {
                        currentCoroutineContext().ensureActive()
                        val relative =
                            if (f == root) root.name
                            else root.name + "/" + f.relativeTo(root).invariantSeparatorsPath
                        if (f.canonicalPath == target.canonicalPath || f == temp)
                            throw IOException("El ZIP no puede incluirse a sí mismo")
                        out.putNextEntry(
                            ZipParameters().apply {
                                fileNameInZip = relative + if (f.isDirectory) "/" else ""
                                isEncryptFiles = password.isNotEmpty() && !f.isDirectory
                                encryptionMethod = EncryptionMethod.AES
                            })
                        if (f.isFile) {
                            t.current = f.name
                            f.inputStream().use { pump(it, out, t) }
                            t.fileDone()
                        }
                        out.closeEntry()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            SafeFiles.commit(temp, target, replace = false)
        } finally {
            temp.delete()
        }
    }

    private suspend fun compressOther(
        sources: List<File>,
        target: File,
        report: (OpProgress) -> Unit
    ) {
        val temp = File.createTempFile(".oi-archive-", ".tmp", target.parentFile)
        val tracker = Tracker("Comprimiendo", report)
        val (bytes, count) = FileOps.measure(sources)
        tracker.totalBytes = bytes
        tracker.totalFiles = count
        try {
            if (target.extension.lowercase() == "7z")
                SevenZOutputFile(temp).use { out ->
                    for (root in sources) for (file in SafeFiles.walk(root)) {
                        currentCoroutineContext().ensureActive()
                        val name =
                            if (file == root) root.name
                            else root.name + "/" + file.relativeTo(root).invariantSeparatorsPath
                        if (file == temp || file.canonicalPath == target.canonicalPath)
                            throw IOException("El comprimido no puede incluirse a sí mismo")
                        out.putArchiveEntry(out.createArchiveEntry(file, name))
                        if (file.isFile) {
                            tracker.current = file.name
                            file.inputStream().use { input ->
                                val buffer = ByteArray(131072)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    tracker.addBytes(n.toLong())
                                }
                            }
                            tracker.fileDone()
                        }
                        out.closeArchiveEntry()
                    }
                }
            else {
                val raw = temp.outputStream().buffered()
                val stream =
                    if (target.name.lowercase().endsWith(".gz")) GzipCompressorOutputStream(raw)
                    else raw
                TarArchiveOutputStream(stream, "UTF-8").use { out ->
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for (root in sources) for (file in SafeFiles.walk(root)) {
                        currentCoroutineContext().ensureActive()
                        if (file == temp || file.canonicalPath == target.canonicalPath)
                            throw IOException("El comprimido no puede incluirse a sí mismo")
                        val name =
                            if (file == root) root.name
                            else root.name + "/" + file.relativeTo(root).invariantSeparatorsPath
                        out.putArchiveEntry(TarArchiveEntry(file, name))
                        if (file.isFile) {
                            tracker.current = file.name
                            file.inputStream().use { pump(it, out, tracker) }
                            tracker.fileDone()
                        }
                        out.closeArchiveEntry()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            SafeFiles.commit(temp, target, false)
        } finally {
            temp.delete()
        }
    }

    /** Destination is a new folder. Any failure removes only the newly created extraction. */
    suspend fun extract(
        file: File,
        dest: File,
        password: String,
        report: (OpProgress) -> Unit
    ): Int {
        if (dest.exists() || !dest.mkdirs())
            throw IOException("La carpeta de extracción debe ser nueva")
        val t = Tracker("Extrayendo", report)
        var entries = 0
        try {
            suspend fun write(name: String, directory: Boolean, size: Long, input: InputStream?) {
                currentCoroutineContext().ensureActive()
                if (++entries > MAX_ENTRIES)
                    throw IOException("Demasiadas entradas en el comprimido")
                val out = SafeFiles.archiveTarget(dest, name)
                if (size > MAX_BYTES - t.doneBytes || (size > 0 && size > dest.usableSpace))
                    throw IOException("No hay espacio suficiente para extraer")
                if (directory) {
                    if (!out.isDirectory && !out.mkdirs())
                        throw IOException("No se pudo crear ${out.name}")
                } else {
                    if (out.exists()) throw IOException("Entrada repetida: $name")
                    if (!out.parentFile!!.isDirectory && !out.parentFile!!.mkdirs())
                        throw IOException("No se pudo crear la carpeta")
                    t.current = name
                    out.outputStream().use { output ->
                        if (input != null) pump(input, output, t, MAX_BYTES)
                    }
                    t.fileDone()
                }
            }
            when (file.extension.lowercase()) {
                in zipTypes ->
                    ZipFile(file, password.toCharArray()).use { zip ->
                        if (zip.isEncrypted && password.isEmpty())
                            throw IOException("Este ZIP necesita una contraseña")
                        for (h in zip.fileHeaders) {
                            zip.getInputStream(h).use {
                                write(h.fileName, h.isDirectory, h.uncompressedSize, it)
                            }
                        }
                    }
                "7z" ->
                    seven(file, password).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            if (e.isAntiItem) throw IOException("Entrada 7z no admitida")
                            val input =
                                object : InputStream() {
                                    override fun read() = z.read()

                                    override fun read(b: ByteArray, off: Int, len: Int) =
                                        z.read(b, off, len)
                                }
                            write(e.name, e.isDirectory, e.size, input)
                        }
                    }
                "rar" ->
                    Archive(file).use { a ->
                        if (a.isEncrypted)
                            throw IOException("RAR cifrado: no compatible con este lector")
                        for (h in a.fileHeaders) {
                            if (h.isEncrypted) throw IOException("RAR cifrado: no compatible")
                            val name = h.fileNameString
                            // Junrar writes to our bounded OutputStream; it never chooses an output
                            // path.
                            if (h.isDirectory) write(name, true, 0, null)
                            else {
                                if (++entries > MAX_ENTRIES ||
                                    h.fullUnpackSize > MAX_BYTES - t.doneBytes ||
                                    h.fullUnpackSize > dest.usableSpace)
                                    throw IOException("El RAR supera el espacio disponible")
                                val out = SafeFiles.archiveTarget(dest, name)
                                if (out.exists()) throw IOException("Entrada repetida")
                                out.parentFile!!.mkdirs()
                                t.current = name
                                out.outputStream().use { raw ->
                                    val guarded =
                                        object : OutputStream() {
                                            override fun write(value: Int) {
                                                write(byteArrayOf(value.toByte()), 0, 1)
                                            }

                                            override fun write(b: ByteArray, off: Int, len: Int) {
                                                if (t.doneBytes + len > MAX_BYTES ||
                                                    Thread.currentThread().isInterrupted)
                                                    throw IOException("Límite de extracción")
                                                raw.write(b, off, len)
                                                t.addBytes(len.toLong())
                                            }
                                        }
                                    a.extractFile(h, guarded)
                                }
                                currentCoroutineContext().ensureActive()
                                t.fileDone()
                            }
                        }
                    }
                else ->
                    tarOrSingle(file).use { stream ->
                        if (isTar(file))
                            TarArchiveInputStream(stream).use { tar ->
                                while (true) {
                                    val e = tar.nextTarEntry ?: break
                                    if (!e.isFile && !e.isDirectory)
                                        throw IOException(
                                            "Enlaces y archivos especiales no se extraen")
                                    if (!tar.canReadEntryData(e))
                                        throw IOException("Entrada TAR no compatible")
                                    write(e.name, e.isDirectory, e.size, tar)
                                }
                            }
                        else write(file.name.substringBeforeLast('.'), false, -1, stream)
                    }
            }
            return t.doneFiles
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun seven(file: File, password: String) =
        if (password.isEmpty()) SevenZFile(file) else SevenZFile(file, password.toCharArray())

    private fun isTar(file: File): Boolean =
        file.name.lowercase().let {
            it.endsWith(".tar") ||
                it.endsWith(".tgz") ||
                it.endsWith(".tar.gz") ||
                it.endsWith(".tar.bz2") ||
                it.endsWith(".tar.xz")
        }

    private fun tarOrSingle(file: File): InputStream {
        val raw = file.inputStream().buffered()
        return try {
            when (file.extension.lowercase()) {
                "gz",
                "tgz" ->
                    CompressorStreamFactory()
                        .createCompressorInputStream(CompressorStreamFactory.GZIP, raw)
                "bz2" ->
                    CompressorStreamFactory()
                        .createCompressorInputStream(CompressorStreamFactory.BZIP2, raw)
                "xz" ->
                    CompressorStreamFactory()
                        .createCompressorInputStream(CompressorStreamFactory.XZ, raw)
                "tar" -> raw
                else -> throw IOException("Formato no compatible")
            }
        } catch (e: Exception) {
            raw.close()
            throw e
        }
    }

    private suspend fun pump(
        input: InputStream,
        out: OutputStream,
        t: Tracker,
        limit: Long = Long.MAX_VALUE
    ) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            if (n > limit - t.doneBytes)
                throw IOException("El comprimido supera el límite de extracción")
            out.write(buffer, 0, n)
            t.addBytes(n.toLong())
        }
    }
}
