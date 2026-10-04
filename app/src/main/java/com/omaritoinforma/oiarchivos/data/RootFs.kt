package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.util.UUID

/**
 * Explicit su/Magisk authorization only. Shell arguments are always quoted, including filenames.
 */
internal class RootFs : RemoteFs {
    init {
        if (command("id -u").trim() != "0")
            throw IOException(
                tr("Root no concedido. Se necesita un dispositivo con su/Magisk y autorización del usuario."))
    }

    private fun q(value: String): String {
        if (value.contains('\u0000')) throw IOException(tr("Ruta no válida"))
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun command(script: String, input: InputStream? = null): String {
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        try {
            process.outputStream.use { out -> input?.copyTo(out) }
            val result = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0)
                throw IOException(result.take(1000).ifBlank { tr("Operación root rechazada") })
            return result
        } finally {
            process.destroy()
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val names =
            command("find ${q(path)} -mindepth 1 -maxdepth 1 -print0").split('\u0000').filter {
                it.isNotBlank()
            }
        return names.mapNotNull { item ->
            val type =
                command(
                    "if [ -L ${q(item)} ]; then printf l; elif [ -d ${q(item)} ]; then printf d; elif [ -f ${q(item)} ]; then printf f; else printf x; fi")
            if (type != "d" && type != "f") null
            else
                RemoteEntry(
                    item,
                    item.substringAfterLast('/'),
                    type == "d",
                    if (type == "d") 0 else command("stat -c %s ${q(item)}").trim().toLong())
        }
    }

    override fun read(path: String): InputStream {
        val process = ProcessBuilder("su", "-c", "cat ${q(path)}").start()
        process.outputStream.close()
        return object : FilterInputStream(process.inputStream) {
            override fun close() {
                super.close()
                try {
                    if (process.waitFor() != 0) throw IOException(tr("Lectura root rechazada"))
                } finally {
                    process.destroy()
                }
            }
        }
    }

    override fun write(parent: String, name: String, input: InputStream, size: Long): String {
        val target = RemoteFiles.join(parent, name)
        val temp = RemoteFiles.partName(target)
        try {
            command(
                "set -e; test ! -e ${q(target)}; cat > ${q(temp)}; test \"\$(stat -c %s ${q(temp)})\" = ${q(size.toString())}; mv -n ${q(temp)} ${q(target)}",
                input)
        } finally {
            runCatching { command("rm -f ${q(temp)}") }
        }
        return target
    }

    override fun mkdir(parent: String, name: String): String =
        RemoteFiles.join(parent, name).also { command("mkdir ${q(it)}") }

    override fun rename(entry: RemoteEntry, name: String) {
        val target = RemoteFiles.join(entry.path.substringBeforeLast('/'), name)
        command("set -e; test ! -e ${q(target)}; mv ${q(entry.path)} ${q(target)}")
    }

    override fun delete(entry: RemoteEntry) {
        if (entry.path == "/") throw IOException(tr("La raíz no se puede eliminar"))
        command("rm ${if(entry.directory)"-r"else"-f"} ${q(entry.path)}")
    }

    fun chmod(path: String, mode: String) {
        if (!Regex("[0-7]{3,4}").matches(mode)) throw IOException(tr("Permisos octales no válidos"))
        command("chmod $mode ${q(path)}")
    }
}
