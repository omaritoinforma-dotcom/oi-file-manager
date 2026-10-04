package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** Adapter used by the same verified transfer engine for phone destinations and sources. */
class LocalDocumentStore : DocumentStore {
    private val created = hashSetOf<String>()
    private val synced = hashSetOf<String>()

    private fun checked(id: String): File = File(id).absoluteFile.also {
        if (it.canonicalPath != it.path || Files.isSymbolicLink(it.toPath()))
            throw IOException("La ruta contiene un enlace o cambió durante la operación")
    }

    override fun stat(id: String): DocumentInfo {
        val file = checked(id)
        SafeFiles.requireRegular(file)
        if (!file.isFile && !file.isDirectory) throw IOException("Archivo especial no compatible")
        val parentWritable = file.parentFile?.canWrite() == true
        return DocumentInfo(file.path, file.name, file.isDirectory, if (file.isFile) file.length() else 0,
            file.lastModified(), file.canRead(), file.canWrite(), parentWritable, parentWritable,
            file.isDirectory && file.canWrite())
    }

    override fun children(id: String): List<DocumentInfo> =
        (checked(id).listFiles() ?: throw IOException("No se pudo leer la carpeta del teléfono"))
            .map { stat(it.path) }

    override fun openRead(id: String): InputStream = checked(id).inputStream()

    override fun createFile(parentId: String, name: String, mimeType: String): String {
        SafeFiles.requireName(name)
        val target = checked(File(checked(parentId), name).path)
        Files.createFile(target.toPath())
        return target.path.also { created += it }
    }

    override fun createDirectory(parentId: String, name: String): String {
        SafeFiles.requireName(name)
        return Files.createDirectory(checked(File(checked(parentId), name).path).toPath()).toString()
    }

    override fun openWriteNew(id: String): OutputStream {
        if (id !in created) throw IOException("Solo se escriben destinos nuevos")
        return object : FileOutputStream(checked(id)) {
            override fun close() {
                try { fd.sync() } finally { super.close() }
                synced += id
            }
        }
    }

    override fun requireDurable(id: String) {
        if (id !in synced) throw IOException("La copia local no confirmó la escritura en disco; el original se conserva")
    }

    override fun rename(id: String, name: String): String {
        SafeFiles.requireName(name)
        val source = checked(id)
        val target = checked(File(source.parentFile, name).path)
        Files.move(source.toPath(), target.toPath())
        if (created.remove(id)) created += target.path
        if (synced.remove(id)) synced += target.path
        return target.path
    }

    override fun delete(id: String): Boolean = checked(id).delete()
}
