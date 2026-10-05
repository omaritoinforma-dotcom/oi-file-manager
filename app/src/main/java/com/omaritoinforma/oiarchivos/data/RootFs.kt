package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.util.UUID

/**
 * Explicit su/Magisk authorization only. Shell arguments are always quoted, including filenames.
 */
internal class RootFs : RemoteFs {
    init {
        RootShell.requireRoot()
    }

    private fun q(value: String) = RootShell.q(value)

    private fun command(script: String, input: InputStream? = null) = RootShell.run(script, input)

    override fun list(path: String): List<RemoteEntry> = parseListing(command(listCommand(path)))

    companion object {
        fun listCommand(path: String): String {
            val p = RootShell.q(path)
            return "find $p -mindepth 1 -maxdepth 1 -type d -exec sh -c 'for f; do printf \"d\\0%s\\0\" \"\$f\"; done' sh {} +; " +
                "find $p -mindepth 1 -maxdepth 1 -type f -exec sh -c 'for f; do printf \"f\\0%s\\0%s\\0\" \"\$(stat -c %s \"\$f\")\" \"\$f\"; done' sh {} +"
        }

        /**
         * Una sola orden para toda la carpeta (antes eran tres por elemento): «d␀ruta␀» para cada
         * carpeta y «f␀tamaño␀ruta␀» para cada archivo; los enlaces simbólicos no se muestran.
         */
        fun parseListing(out: String): List<RemoteEntry> {
            val parts = out.split('\u0000')
            val result = ArrayList<RemoteEntry>()
            var i = 0
            while (i < parts.size) {
                when (parts[i]) {
                    "d" -> if (i + 1 < parts.size) {
                        val p = parts[i + 1]
                        result += RemoteEntry(p, p.substringAfterLast('/'), true, 0)
                        i += 2
                    } else i++
                    "f" -> if (i + 2 < parts.size) {
                        val p = parts[i + 2]
                        result += RemoteEntry(p, p.substringAfterLast('/'), false, parts[i + 1].trim().toLongOrNull() ?: 0)
                        i += 3
                    } else i++
                    else -> i++
                }
            }
            return result
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
