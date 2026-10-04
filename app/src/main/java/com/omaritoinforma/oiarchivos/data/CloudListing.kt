package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.json.JSONObject

internal typealias CloudJsonRequest = (String, String, JSONObject?) -> JSONObject
internal typealias CloudDeleteRequest = (String, Map<String, String>) -> Unit

/** A list is returned only after the provider has confirmed its final page. */
internal class CloudListing<T>(
    private val limit: Int = 50_000,
    private val identity: (T) -> String
) {
    private val entries = ArrayList<T>()
    private val identities = HashSet<String>()
    private val cursors = HashSet<String>()

    val size: Int get() = entries.size

    fun beginPage(cursor: String) {
        // A malicious or changing provider must not keep an operation in an endless loop,
        // including when continuation pages contain no entries.
        if (!cursors.add(cursor) || cursors.size > 1_000)
            throw IOException("El proveedor repitió una página o no completó el listado")
    }

    fun add(entry: T) {
        if (entries.size >= limit)
            throw IOException("La carpeta supera el límite de ${"%,d".format(java.util.Locale.US, limit).replace(',', '.')} elementos; no se completó el listado")
        if (!identities.add(identity(entry)))
            throw IOException("El proveedor repitió un archivo; no se completó el listado")
        entries += entry
    }

    fun result(): List<T> = entries
}

/** JSON null is the end of a marker sequence, never the literal token "null". */
internal fun JSONObject.pageToken(name: String): String =
    if (!has(name) || isNull(name)) "" else getString(name)
