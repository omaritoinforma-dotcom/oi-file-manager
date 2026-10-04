package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException

/**
 * Listas de reproducción guardadas, como las de la música de ES. Cada lista es un archivo M3U8 en
 * la carpeta privada de la app: un formato estándar que otros reproductores entienden si se
 * comparte. Solo se guardan rutas de archivos del teléfono.
 */
class Playlists(private val dir: File) {
    data class Playlist(val name: String, val tracks: List<String>)

    fun names(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            .orEmpty()
            .map { it.name.removeSuffix(EXT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun read(name: String): Playlist {
        val file = fileOf(name)
        if (!file.isFile) throw IOException("No existe la lista «$name»")
        return Playlist(name, parse(file.readText()))
    }

    fun create(name: String) {
        val file = fileOf(name)
        if (file.exists()) throw IOException("Ya existe una lista llamada «${name.trim()}»")
        write(file, emptyList())
    }

    /** Añade [paths] al final, sin repetir las que ya estaban. Crea la lista si no existe. */
    fun add(name: String, paths: List<String>): Int {
        val file = fileOf(name)
        val current = if (file.isFile) parse(file.readText()) else emptyList()
        val added = paths.filter { it.startsWith("/") && it !in current }.distinct()
        write(file, current + added)
        return added.size
    }

    fun remove(name: String, path: String) = update(name) { it - path }

    /** Sube (-1) o baja (+1) una pista en la lista. */
    fun move(name: String, index: Int, delta: Int) = update(name) { tracks ->
        val target = index + delta
        if (index !in tracks.indices || target !in tracks.indices) tracks
        else tracks.toMutableList().apply { add(target, removeAt(index)) }
    }

    fun rename(from: String, to: String) {
        val source = fileOf(from)
        val target = fileOf(to)
        if (!source.isFile) throw IOException("No existe la lista «$from»")
        if (source == target) return
        if (target.exists()) throw IOException("Ya existe una lista llamada «${to.trim()}»")
        if (!source.renameTo(target)) throw IOException("No se pudo renombrar la lista")
    }

    fun delete(name: String) {
        fileOf(name).delete()
    }

    private fun update(name: String, change: (List<String>) -> List<String>) {
        val file = fileOf(name)
        if (!file.isFile) throw IOException("No existe la lista «$name»")
        write(file, change(parse(file.readText())))
    }

    private fun write(file: File, tracks: List<String>) {
        dir.mkdirs()
        // Se escribe aparte y se reemplaza: una lista nunca queda a medias.
        val tmp = File(dir, ".${file.name}.tmp")
        tmp.writeText(format(tracks))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("No se pudo guardar la lista")
        }
    }

    private fun fileOf(name: String): File = File(dir, checkName(name) + EXT)

    companion object {
        const val EXT = ".m3u8"
        const val MAX_NAME = 80

        /** Devuelve el nombre limpio o lanza un error que se puede mostrar. */
        fun checkName(name: String): String {
            val clean = name.trim()
            if (clean.isEmpty()) throw IOException("Escribe un nombre para la lista")
            if (clean.length > MAX_NAME) throw IOException("El nombre es demasiado largo")
            if (clean.startsWith(".") || clean.any { it == '/' || it == '\\' || it < ' ' })
                throw IOException("El nombre no puede tener «/», «\\» ni empezar por un punto")
            return clean
        }

        fun format(tracks: List<String>): String = buildString {
            append("#EXTM3U\n")
            tracks.forEach {
                append("#EXTINF:-1,").append(File(it).nameWithoutExtension).append('\n')
                append(it).append('\n')
            }
        }

        /** Lee un M3U: se ignoran comentarios, líneas vacías y lo que no sea una ruta absoluta. */
        fun parse(text: String): List<String> =
            text.lineSequence()
                .map { it.trim().removePrefix("\uFEFF") }
                .filter { it.startsWith("/") }
                .distinct()
                .toList()
    }
}
