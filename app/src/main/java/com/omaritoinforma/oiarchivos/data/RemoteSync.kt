package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Archivos remotos abiertos para editar en otra app («Enable remote synchronize» de ES): se bajan a
 * una copia local, se abren y, cuando esa copia cambia, se sube de vuelta al servidor. Aquí están
 * la lista de copias pendientes y la subida segura; la pantalla decide cuándo llamar.
 */
object RemoteSync {
    /** Una copia local de un archivo remoto y cómo estaba cuando se bajó o se subió por última vez. */
    data class Edit(
        val connectionId: String,
        val parent: String,
        val name: String,
        val local: String,
        /** Tamaño que tenía en el servidor cuando se bajó o se subió por última vez. */
        val remoteSize: Long,
        val syncedSize: Long,
        val syncedModified: Long
    )

    sealed interface Outcome {
        data class Updated(val edit: Edit) : Outcome

        /** El archivo cambió en el servidor mientras se editaba: no se pisa sin preguntar. */
        data object Conflict : Outcome

        /** La copia local ya no existe. */
        data object Gone : Outcome
    }

    enum class Mode {
        /** Sustituir el archivo del servidor, pero solo si no cambió allí. */
        SAFE,
        /** Sustituirlo aunque haya cambiado en el servidor. */
        OVERWRITE,
        /** Subirlo con otro nombre y dejar el del servidor como está. */
        COPY
    }

    const val MAX_EDITS = 30

    fun changed(edit: Edit): Boolean {
        val file = File(edit.local)
        return file.isFile && (file.lastModified() != edit.syncedModified || file.length() != edit.syncedSize)
    }

    /** Nombre libre con una etiqueta: «informe (editado).txt», «informe (editado 2).txt»… */
    fun copyName(name: String, taken: Set<String>, label: String = "editado"): String {
        val base = name.substringBeforeLast('.', name)
        val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var candidate = "$base ($label)$ext"
        var n = 2
        while (candidate in taken) candidate = "$base ($label $n)$ext".also { n++ }
        return candidate
    }

    /**
     * Sube la copia local de [edit]. El archivo del servidor nunca se pierde por un fallo a medias:
     * primero se sube con un nombre aparte, luego se borra el viejo y se renombra el nuevo.
     */
    fun upload(fs: RemoteFs, edit: Edit, mode: Mode = Mode.SAFE): Outcome {
        val local = File(edit.local)
        if (!local.isFile) return Outcome.Gone
        val size = local.length()
        val modified = local.lastModified()
        val entries = fs.list(edit.parent)
        val names = entries.map { it.name }.toSet()
        val current = entries.firstOrNull { it.name == edit.name && !it.directory }
        if (mode == Mode.SAFE && current != null && edit.remoteSize >= 0 && current.size != edit.remoteSize)
            return Outcome.Conflict
        val synced = edit.copy(remoteSize = size, syncedSize = size, syncedModified = modified)
        if (mode == Mode.COPY || current == null) {
            val target = if (mode == Mode.COPY) copyName(edit.name, names) else edit.name
            local.inputStream().use { fs.write(edit.parent, target, it, size) }
            return Outcome.Updated(if (mode == Mode.COPY) synced.copy(name = target) else synced)
        }
        val temp = copyName(edit.name, names, "subiendo")
        val written = local.inputStream().use { fs.write(edit.parent, temp, it, size) }
        fs.delete(current)
        try {
            fs.rename(RemoteEntry(written, temp, false, size), edit.name)
        } catch (e: Exception) {
            throw IOException(tr("El archivo se subió como «{0}» pero no se pudo renombrar: {1}", temp, e.message))
        }
        return Outcome.Updated(synced)
    }

    // ---- Lista de copias pendientes (sobrevive al cierre de la app) ----

    class Store(private val file: File) {
        fun all(): List<Edit> =
            runCatching {
                    val array = JSONArray(file.readText())
                    (0 until array.length()).map {
                        val o = array.getJSONObject(it)
                        Edit(
                            o.getString("conexion"),
                            o.getString("carpeta"),
                            o.getString("nombre"),
                            o.getString("local"),
                            o.getLong("tamanoRemoto"),
                            o.getLong("tamanoLocal"),
                            o.getLong("fechaLocal"))
                    }
                }
                .getOrDefault(emptyList())

        /** Guarda [edit], sustituyendo la que apunte a la misma copia local; las más viejas se descartan. */
        fun put(edit: Edit) = write((all().filter { it.local != edit.local } + edit).takeLast(MAX_EDITS))

        fun remove(local: String) = write(all().filter { it.local != local })

        /** Quita las copias que ya no existen (por ejemplo, tras borrar la caché). */
        fun prune() = write(all().filter { File(it.local).isFile })

        private fun write(edits: List<Edit>) {
            val array = JSONArray()
            edits.forEach {
                array.put(
                    JSONObject()
                        .put("conexion", it.connectionId)
                        .put("carpeta", it.parent)
                        .put("nombre", it.name)
                        .put("local", it.local)
                        .put("tamanoRemoto", it.remoteSize)
                        .put("tamanoLocal", it.syncedSize)
                        .put("fechaLocal", it.syncedModified))
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) throw IOException(tr("No se pudo guardar la lista de archivos remotos"))
        }
    }
}
