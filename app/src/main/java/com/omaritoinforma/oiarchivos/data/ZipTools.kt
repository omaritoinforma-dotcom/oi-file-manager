package com.omaritoinforma.oiarchivos.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipTools {
    private const val BUFFER = 128 * 1024

    suspend fun compress(sources: List<File>, zip: File, report: (OpProgress) -> Unit) {
        val (bytes, count) = FileOps.measure(sources)
        val t = Tracker("Comprimiendo", report)
        t.totalBytes = bytes
        t.totalFiles = count
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip), BUFFER)).use { zos ->
                for (s in sources) add(zos, s, s.name, t)
            }
        } catch (e: Exception) {
            zip.delete()
            throw e
        }
    }

    private suspend fun add(zos: ZipOutputStream, f: File, entryName: String, t: Tracker) {
        currentCoroutineContext().ensureActive()
        if (f.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryName/").apply { time = f.lastModified() })
            zos.closeEntry()
            val children = f.listFiles() ?: emptyArray()
            for (c in children) add(zos, c, "$entryName/${c.name}", t)
        } else {
            t.current = f.name
            zos.putNextEntry(ZipEntry(entryName).apply { time = f.lastModified() })
            FileInputStream(f).use { pump(it, zos, t) }
            zos.closeEntry()
            t.fileDone()
        }
    }

    private suspend fun pump(input: InputStream, out: OutputStream, t: Tracker) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            t.addBytes(n.toLong())
            currentCoroutineContext().ensureActive()
        }
    }

    /** Abre el ZIP probando UTF-8 y luego la codificación antigua de Windows (IBM437). */
    private fun open(file: File): Pair<ZipFile, List<ZipEntry>> {
        var last: Exception? = null
        for (cs in listOf(Charsets.UTF_8, Charset.forName("IBM437"))) {
            var z: ZipFile? = null
            try {
                val zf = ZipFile(file, cs)
                z = zf
                return zf to zf.entries().toList()
            } catch (e: Exception) {
                runCatching { z?.close() }
                last = e
            }
        }
        throw IOException("El ZIP está dañado o no es compatible", last)
    }

    suspend fun extract(zip: File, destDir: File, report: (OpProgress) -> Unit): Int {
        val (zf, entries) = open(zip)
        zf.use {
            val t = Tracker("Extrayendo", report)
            t.totalBytes = entries.sumOf { e -> if (e.size > 0) e.size else 0L }
            t.totalFiles = entries.count { e -> !e.isDirectory }
            if (!destDir.exists() && !destDir.mkdirs()) throw IOException("No se pudo crear «${destDir.name}»")
            val destCanon = destDir.canonicalPath
            for (e in entries) {
                val out = File(destDir, e.name)
                val canon = out.canonicalPath
                // Protección contra "zip slip" (rutas que intentan salir de la carpeta).
                if (canon != destCanon && !canon.startsWith(destCanon + File.separator)) {
                    throw IOException("Entrada insegura en el ZIP: ${e.name}")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                t.current = out.name
                zf.getInputStream(e).use { input -> FileOutputStream(out).use { o -> pump(input, o, t) } }
                if (e.time > 0) out.setLastModified(e.time)
                t.fileDone()
            }
            return t.doneFiles
        }
    }
}
