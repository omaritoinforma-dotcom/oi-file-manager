package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream

/** Strict queries: an unavailable provider must never look like an empty directory. */
class AndroidDocumentStore(private val context: Context) : DocumentStore {
    companion object {
        /** Detect a phone folder reached both by a File path and by an external-storage tree. */
        fun sameLocation(first: String, second: String): Boolean {
            fun identity(value: String): String {
                val uri = Uri.parse(value)
                if (uri.scheme != "content") return "file:${java.io.File(value).canonicalPath}"
                val id = DocumentsContract.getDocumentId(uri)
                if (uri.authority == "com.android.externalstorage.documents") {
                    val volume = id.substringBefore(':')
                    val relative = id.substringAfter(':', "")
                    if (relative.split('/').any { it == "." || it == ".." })
                        throw IOException("El proveedor devolvió una ruta no válida")
                    val root = if (volume == "primary") android.os.Environment.getExternalStorageDirectory()
                        else java.io.File("/storage/$volume")
                    return "file:${java.io.File(root, relative).canonicalPath}"
                }
                return "document:${uri.authority}:$id"
            }
            return first == second || runCatching { identity(first) == identity(second) }.getOrDefault(false)
        }
    }
    private val resolver = context.contentResolver
    private val created = hashSetOf<String>()
    private val synced = hashSetOf<String>()
    private val parents = hashMapOf<String, String>()
    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS)

    private fun allowed(uri: Uri, flag: Int) =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flag) ==
            PackageManager.PERMISSION_GRANTED

    private fun requireComplete(cursor: android.database.Cursor) {
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
            throw IOException("El proveedor todavía está cargando la carpeta. Espera y toca Actualizar.")
        cursor.extras.getString(DocumentsContract.EXTRA_ERROR)?.takeIf { it.isNotBlank() }?.let {
            throw IOException("El proveedor no pudo completar la consulta: $it")
        }
    }

    private fun key(id: String): String {
        val uri = Uri.parse(id)
        return "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
    }

    fun requireSafeReplacement(parentId: String, originalId: String): String {
        // SAF capabilities do not promise transactional replacement for arbitrary cloud providers.
        // The platform's storage provider supports sibling file renames; keep the backup forever.
        if (Uri.parse(originalId).authority != "com.android.externalstorage.documents")
            throw IOException("Este proveedor no ofrece garantías suficientes para reemplazar el original con respaldo. Usa «Guardar copia».")
        val canonical = children(parentId).singleOrNull { key(it.id) == key(originalId) }?.id
            ?: throw IOException("El original ya no está en la carpeta; usa «Guardar copia»")
        parents[canonical] = parentId
        return canonical
    }

    /** ACTION_CREATE_DOCUMENT authorizes a new copy, never an existing non-empty file. */
    suspend fun adoptEmptyCreatedDocument(id: String) {
        val info = stat(id)
        if (!info.readable || !info.writable || info.directory ||
            DocumentTransactions.fingerprint(this, id, 0L).size != 0L)
            throw IOException("El destino para la copia no es un archivo nuevo vacío y verificable")
        created += id
    }

    private fun info(uri: Uri, cursor: android.database.Cursor): DocumentInfo {
        val flags = cursor.getLong(5)
        val directory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
        val write = allowed(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        fun flag(value: Int) = flags and value.toLong() != 0L
        return DocumentInfo(
            uri.toString(), cursor.getString(1) ?: throw IOException("Documento sin nombre"),
            directory, if (cursor.isNull(3)) -1 else cursor.getLong(3),
            if (cursor.isNull(4)) 0 else cursor.getLong(4),
            allowed(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) &&
                !flag(DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT),
            write && flag(if (directory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                else DocumentsContract.Document.FLAG_SUPPORTS_WRITE),
            write && flag(DocumentsContract.Document.FLAG_SUPPORTS_RENAME),
            write && flag(DocumentsContract.Document.FLAG_SUPPORTS_DELETE),
            write && directory && flag(DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE))
    }

    override fun stat(id: String): DocumentInfo {
        val uri = Uri.parse(id)
        if (!DocumentsContract.isDocumentUri(context, uri)) {
            // Shared FileProvider content can be imported, but has no tree mutation contract.
            val cursor = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE), null, null, null)
                ?: throw IOException("No se pudo consultar el archivo compartido")
            return cursor.use {
                requireComplete(it)
                if (!it.moveToFirst()) throw IOException("El archivo compartido ya no está disponible")
                DocumentInfo(id, it.getString(0) ?: throw IOException("Archivo compartido sin nombre"),
                    false, if (it.isNull(1)) -1 else it.getLong(1), 0,
                    allowed(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION), false, false, false, false)
            }
        }
        val cursor = resolver.query(uri, columns, null, null, null)
            ?: throw IOException("El proveedor no devolvió información del documento")
        return cursor.use {
            requireComplete(it)
            if (!it.moveToFirst()) throw IOException("El documento ya no está disponible")
            info(uri, it)
        }
    }

    override fun children(id: String): List<DocumentInfo> {
        val uri = Uri.parse(id)
        if (!stat(id).directory) throw IOException("El destino no es una carpeta")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri, DocumentsContract.getDocumentId(uri))
        val cursor = resolver.query(childrenUri, columns, null, null, null)
            ?: throw IOException("No se pudo enumerar la carpeta del proveedor")
        return cursor.use {
            requireComplete(it)
            val result = ArrayList<DocumentInfo>()
            while (it.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(uri, it.getString(0))
                parents[child.toString()] = id
                result += info(child, it)
            }
            result
        }
    }

    override fun openRead(id: String): InputStream =
        resolver.openInputStream(Uri.parse(id)) ?: throw IOException("No se pudo leer el documento")

    override fun createFile(parentId: String, name: String, mimeType: String): String {
        SafeFiles.requireName(name)
        if (!stat(parentId).canCreate) throw IOException("La carpeta no permite crear documentos")
        val existing = children(parentId).map { key(it.id) }.toSet()
        return (DocumentsContract.createDocument(resolver, Uri.parse(parentId), mimeType, name)
            ?: throw IOException("El proveedor no pudo crear el archivo")).toString().also {
                if (key(it) in existing) throw IOException("El proveedor devolvió un documento existente; se conserva sin escribir")
                created += it
                parents[it] = parentId
            }
    }

    override fun createDirectory(parentId: String, name: String): String =
        createFile(parentId, name, DocumentsContract.Document.MIME_TYPE_DIR)

    override fun openWriteNew(id: String): OutputStream {
        if (id !in created) throw IOException("Solo se escriben documentos nuevos; el original se conserva")
        if (!stat(id).writable) throw IOException("El proveedor no permite escribir el documento nuevo")
        val output = resolver.openOutputStream(Uri.parse(id), "w")
            ?: throw IOException("No se pudo escribir la copia nueva")
        return object : FilterOutputStream(output) {
            override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
            override fun close() {
                var syncFailure: Exception? = null
                try {
                    flush()
                    (out as? FileOutputStream)?.fd?.sync()
                        ?: throw IOException("El proveedor no entregó un archivo sincronizable")
                } catch (failure: Exception) {
                    syncFailure = failure
                } finally {
                    out.close()
                }
                if (syncFailure == null) synced += id
                else if (Uri.parse(id).authority == "com.android.externalstorage.documents")
                    throw IOException("No se pudo confirmar la escritura en USB / SD; el original se conserva", syncFailure)
            }
        }
    }

    override fun requireDurable(id: String) {
        if (id !in synced)
            throw IOException("La copia se verificó, pero este proveedor no confirma almacenamiento durable. El original se conserva; usa Copiar.")
    }

    override fun rename(id: String, name: String): String {
        SafeFiles.requireName(name)
        if (!stat(id).canRename) throw IOException("El proveedor no permite renombrar con respaldo")
        val parent = parents[id] ?: throw IOException("No se conoce la carpeta del documento; vuelve a abrirla")
        if (children(parent).any { it.name == name && key(it.id) != key(id) })
            throw IOException("Ya existe otro documento con ese nombre; se conserva sin reemplazar")
        val result = DocumentsContract.renameDocument(resolver, Uri.parse(id), name)
            ?: throw IOException("El proveedor no pudo renombrar el documento")
        if (created.remove(id)) created += result.toString()
        if (synced.remove(id)) synced += result.toString()
        parents.remove(id)
        parents[result.toString()] = parent
        if (stat(result.toString()).name != name)
            throw IOException("El proveedor eligió otro nombre al renombrar; revisa la carpeta y conserva el respaldo")
        return result.toString()
    }

    override fun delete(id: String): Boolean {
        if (!stat(id).canDelete) throw IOException("El proveedor no permite eliminar el documento")
        return DocumentsContract.deleteDocument(resolver, Uri.parse(id))
    }
}
