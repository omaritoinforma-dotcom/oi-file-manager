package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gestor de descargas desde una URL, como el de ES. Si la descarga se corta (sin red, la app se
 * cierra o se cancela), al volver a pedir la misma URL continúa donde iba: el resto parcial se
 * guarda junto a su validador (ETag o fecha) y se pide con Range e If-Range, así que si el archivo
 * cambió en el servidor se empieza de cero en lugar de mezclar dos versiones.
 */
object UrlDownloader {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /** Comprueba la URL antes de empezar; devuelve un mensaje de error o null. */
    fun problem(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return "Escribe una dirección http:// o https://"
        if (parsed.host.isBlank()) return "Falta el servidor"
        return null
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(12).joinToString("") {
            "%02x".format(it)
        }

    internal fun partFile(folder: File, url: String) = File(folder, ".oi-descarga-${key(url)}.part")

    private fun validatorFile(part: File) = File(part.path + ".validador")

    /** Nombre del archivo: el que indica el servidor o el último trozo de la URL. */
    internal fun fileName(url: String, disposition: String?): String {
        val fromHeader =
            disposition?.let { header ->
                Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(header)?.groupValues?.get(1)?.let {
                    runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull()
                } ?: Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(header)?.groupValues?.get(1)
            }
        val fromUrl =
            url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() }
        val raw = (fromHeader ?: fromUrl ?: "descarga").substringAfterLast('/').substringAfterLast('\\')
        val clean =
            raw.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trim('.').take(200)
        return clean.ifBlank { "descarga" }
    }

    suspend fun download(url: String, folder: File, report: (OpProgress) -> Unit): File {
        problem(url)?.let { throw IOException(it) }
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException(tr("No se pudo crear la carpeta de descargas"))
        val part = partFile(folder, url.trim())
        val validatorFile = validatorFile(part)
        val validator = validatorFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val already = if (part.isFile && validator.isNotEmpty()) part.length() else 0L
        val request =
            Request.Builder().url(url.trim()).apply {
                if (already > 0) {
                    header("Range", "bytes=$already-")
                    header("If-Range", validator)
                }
            }.build()
        val tracker = Tracker("Descargando", report)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(tr("El servidor respondió {0}", response.code))
            val resumed = response.code == 206 && already > 0
            val name = fileName(url, response.header("Content-Disposition"))
            val body = response.body ?: throw IOException(tr("Respuesta vacía"))
            val length = body.contentLength()
            tracker.current = name
            tracker.totalFiles = 1
            tracker.totalBytes = if (length >= 0) length + (if (resumed) already else 0) else 0
            tracker.doneBytes = if (resumed) already else 0
            tracker.emit()
            if (!resumed) part.delete()
            // Se guarda el validador antes de escribir: sin él no se reanuda (podría ser otro archivo).
            val newValidator = response.header("ETag") ?: response.header("Last-Modified")
            if (newValidator != null) validatorFile.writeText(newValidator) else validatorFile.delete()
            FileOutputStream(part, resumed).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        tracker.addBytes(n.toLong())
                    }
                }
                out.fd.sync()
            }
            if (length >= 0 && part.length() != tracker.totalBytes)
                throw IOException(tr("Descarga incompleta; vuelve a intentarlo para continuar"))
            val target = FileOps.uniqueName(folder, name)
            SafeFiles.commit(part, target, replace = false)
            validatorFile.delete()
            tracker.fileDone()
            return target
        }
    }
}
