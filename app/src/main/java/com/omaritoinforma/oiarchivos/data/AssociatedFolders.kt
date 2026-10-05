package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.text.Normalizer

/**
 * «Clean associated folders after uninstallation» de ES: tras desinstalar una app, carpetas de la raíz
 * del almacenamiento que llevan su nombre (por ejemplo «/sdcard/WhatsApp» de com.whatsapp). Android ya
 * borra Android/data, media y obb de la app; estas otras las crean las apps por su cuenta y se quedan.
 *
 * Es deliberadamente conservador: solo carpetas de primer nivel cuyo nombre coincide entero con el de
 * la app o su paquete, nunca las carpetas comunes de Android y nunca nombres genéricos. Además la app
 * solo las propone: quien decide es el usuario, y van a la papelera.
 */
object AssociatedFolders {
    /** Carpetas estándar de Android y de esta app: nunca se proponen. */
    private val protectedNames =
        setOf(
            "android", "dcim", "download", "downloads", "documents", "pictures", "movies", "music",
            "notifications", "podcasts", "ringtones", "alarms", "audiobooks", "recordings", "screenshots",
            "oiarchivos", "oipapelera", "lost+found")

    /** Palabras que no identifican a ninguna app (último trozo de muchos paquetes). */
    private val generic =
        setOf(
            "app", "apps", "android", "mobile", "free", "pro", "lite", "client", "music", "video", "videos",
            "camera", "photo", "photos", "gallery", "files", "file", "manager", "browser", "player", "launcher",
            "google", "main", "data", "media", "share", "chat", "mail", "notes", "backup", "cloud", "home",
            "settings", "tools", "games", "game", "store", "sync")

    /** Minúsculas, sin tildes y solo letras y números: «Prueba Limpia» y «prueba_limpia» quedan igual. */
    fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }

    /** Nombres que identifican a la app: su nombre visible, su paquete y el último trozo del paquete. */
    fun keys(label: String, packageName: String): Set<String> {
        val result = LinkedHashSet<String>()
        normalize(label).takeIf { it.length >= 3 && it !in generic }?.let { result += it }
        normalize(packageName).takeIf { it.length >= 3 }?.let { result += it }
        normalize(packageName.substringAfterLast('.'))
            .takeIf { it.length >= 4 && it !in generic }
            ?.let { result += it }
        return result
    }

    /** De los nombres de carpeta [children], los que parecen de la app. */
    fun matching(children: List<String>, label: String, packageName: String): List<String> {
        val wanted = keys(label, packageName)
        return children.filter { name ->
            val key = normalize(name)
            !name.startsWith(".") && key !in protectedNames && key in wanted
        }
    }

    /** Carpetas de primer nivel de [root] que dejó la app (no sigue enlaces). */
    fun find(root: File, label: String, packageName: String): List<File> {
        val dirs =
            root.listFiles()
                ?.filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .orEmpty()
        val names = matching(dirs.map { it.name }, label, packageName).toSet()
        return dirs.filter { it.name in names }.sortedBy { it.name.lowercase() }
    }
}
