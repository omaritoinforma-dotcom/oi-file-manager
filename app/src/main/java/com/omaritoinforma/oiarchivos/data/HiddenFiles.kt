package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException

/**
 * «Ocultar» de ES: un archivo o carpeta se oculta poniéndole un punto delante del nombre
 * («foto.jpg» → «.foto.jpg»). Así desaparece de las galerías y de los listados normales, y se
 * recupera quitando el punto. La lista de lo ocultado desde la app se guarda aparte y se puede
 * proteger con contraseña.
 */
object HiddenFiles {
    /** El nombre oculto de [name], o null si ya está oculto o no tiene nombre. */
    fun hiddenName(name: String): String? = if (name.isEmpty() || name.startsWith(".")) null else ".$name"

    /** El nombre de [hidden] sin ocultar, o null si no estaba oculto con un punto. */
    fun shownName(hidden: String): String? = if (hidden.length > 1 && hidden.startsWith(".")) hidden.substring(1) else null

    /** Oculta [file] y devuelve su nueva ruta. Nunca sustituye algo que ya exista. */
    fun hide(file: File): File = rename(file, hiddenName(file.name) ?: throw IOException("«${file.name}» ya está oculto"))

    /** Devuelve a [file] su nombre sin punto. */
    fun show(file: File): File = rename(file, shownName(file.name) ?: throw IOException("«${file.name}» no está oculto con un punto"))

    private fun rename(file: File, name: String): File {
        val parent = file.parentFile ?: throw IOException("No se puede cambiar el nombre de ${file.path}")
        val target = File(parent, name)
        if (target.exists()) throw IOException("Ya existe «$name» en la carpeta")
        if (!file.renameTo(target)) throw IOException("No se pudo renombrar «${file.name}»")
        return target
    }
}
