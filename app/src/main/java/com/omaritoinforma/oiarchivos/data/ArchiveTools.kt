package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

data class ArchiveEntry(val name: String, val size: Long, val directory: Boolean)

/** Nivel de compresión al crear ZIP, 7z o tar.gz («Nivel de compresión» de ES). */
enum class CompressionLevel(val label: String, val sevenZip: Int, val gzip: Int) {
    STORE("Sin compresión", 0, 0),
    FAST("Rápida", 1, 1),
    NORMAL("Normal", 5, 6),
    MAXIMUM("Máxima", 9, 9);

    val zip: net.lingala.zip4j.model.enums.CompressionLevel
        get() =
            when (this) {
                STORE -> net.lingala.zip4j.model.enums.CompressionLevel.NO_COMPRESSION
                FAST -> net.lingala.zip4j.model.enums.CompressionLevel.FASTEST
                NORMAL -> net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
                MAXIMUM -> net.lingala.zip4j.model.enums.CompressionLevel.ULTRA
            }
}

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
                if (NativeArchives.available) NativeArchives.list(file, password)
                else
                    seven(file, password).use { z ->
                        z.entries.take(MAX_ENTRIES).map {
                            ArchiveEntry(it.name, it.size, it.isDirectory)
                        }
                    }
            "rar" -> NativeArchives.list(file, password)
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
        level: CompressionLevel = CompressionLevel.NORMAL,
        report: (OpProgress) -> Unit
    ) {
        if (target.extension.lowercase() == "7z" && NativeArchives.available) {
            NativeArchives.compress(sources, target, password, report, level)
            return
        }
        if (target.extension.lowercase() == "7z" ||
            target.name.lowercase().endsWith(".tar") ||
            target.name.lowercase().endsWith(".tar.gz")) {
            if (password.isNotEmpty())
                throw IOException("La contraseña de creación está disponible para ZIP")
            compressOther(sources, target, report, level)
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
                                compressionMethod =
                                    if (level == CompressionLevel.STORE) CompressionMethod.STORE
                                    else CompressionMethod.DEFLATE
                                compressionLevel = level.zip
                                // Al guardar sin comprimir, ZIP necesita saber el tamaño antes de escribir.
                                if (level == CompressionLevel.STORE)
                                    entrySize = if (f.isFile) f.length() else 0L
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
        report: (OpProgress) -> Unit,
        level: CompressionLevel
    ) {
        val temp = File.createTempFile(".oi-archive-", ".tmp", target.parentFile)
        val tracker = Tracker("Comprimiendo", report)
        val (bytes, count) = FileOps.measure(sources)
        tracker.totalBytes = bytes
        tracker.totalFiles = count
        try {
            if (target.extension.lowercase() == "7z")
                SevenZOutputFile(temp).use { out ->
                    out.setContentCompression(
                        if (level == CompressionLevel.STORE) org.apache.commons.compress.archivers.sevenz.SevenZMethod.COPY
                        else org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2)
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
                    if (target.name.lowercase().endsWith(".gz"))
                        GzipCompressorOutputStream(
                            raw,
                            org.apache.commons.compress.compressors.gzip.GzipParameters().apply {
                                compressionLevel = level.gzip
                            })
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
                "7z",
                "rar" -> {
                    if (NativeArchives.available) {
                        val contents = NativeArchives.list(file, password)
                        t.totalBytes = contents.sumOf { it.size }
                        if (t.totalBytes > MAX_BYTES)
                            throw IOException("El comprimido supera el límite de extracción")
                        for (entry in contents) {
                            if (entry.directory) write(entry.name, true, 0, null)
                            else
                                NativeArchives.withEntry(file, password, entry.name) {
                                    write(entry.name, false, entry.size, it)
                                }
                        }
                    } else if (file.extension.equals("7z", true)) {
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
                    } else throw IOException("El motor RAR no está instalado")
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
