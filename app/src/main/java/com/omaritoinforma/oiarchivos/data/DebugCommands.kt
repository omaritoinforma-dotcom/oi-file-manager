package com.omaritoinforma.oiarchivos.data

import java.io.IOException

/** Structured ADB commands only; no shell interpreter and no root command. */
object DebugCommands {
    val changes = setOf("force_stop","clear_data","disable","enable","install","uninstall","grant","revoke","set_appop","tap","swipe","keyevent","text","screenshot")
    fun packageName(value: String): String {
        if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(value)) throw IOException("Paquete no válido")
        return value
    }

    /**
     * Read-only system diagnostics run as ADB shell UID 2000 via the approved
     * Shizuku service, never as root. A path is one argv item, not a shell script.
     * Android DAC/SELinux restrictions still decide what may be inspected.
     */
    private fun systemPath(value: String, readingContent: Boolean): String {
        val path = if (value.isBlank() && !readingContent) "/" else value
        if (!path.startsWith("/") || path.length > 512 || path.contains('\u0000') ||
            path.contains('\n') || path.contains('\r') || path.contains('\\') ||
            path.split('/').any { it == "." || it == ".." }) {
            throw IOException("Ruta de sistema absoluta no válida")
        }
        val forbidden = listOf("/data/data", "/data/user", "/data/user_de")
        if (forbidden.any { path == it || path.startsWith("$it/") }) {
            throw IOException("Los datos privados de otras aplicaciones no son accesibles sin root")
        }
        if (readingContent) {
            val readableRoots = listOf("/system", "/system_ext", "/vendor", "/product",
                "/odm", "/apex", "/etc")
            val procFiles = setOf("/proc/version", "/proc/cpuinfo", "/proc/meminfo",
                "/proc/uptime", "/proc/mounts", "/proc/loadavg")
            val startupConfig = Regex("^/init(?:\\.[a-zA-Z0-9_-]+)?\\.rc$").matches(path)
            if (readableRoots.none { path.startsWith("$it/") } &&
                path !in procFiles && !startupConfig) {
                throw IOException("Lectura de contenido limitada a configuración pública del sistema")
            }
        }
        return path
    }

    fun argv(action: String, pkg: String = "", value: String = "", lines: Int = 200,
             x: Int = 0, y: Int = 0, x2: Int = 0, y2: Int = 0): List<String> {
        fun p() = packageName(pkg)
        fun permission(): String {
            if (!Regex("[A-Za-z][A-Za-z0-9_.]+").matches(value)) throw IOException("Permiso no válido")
            return value
        }
        fun coordinate(v: Int): String {
            if (v !in 0..10000) throw IOException("Coordenada no válida")
            return v.toString()
        }
        val count=lines.coerceIn(1,1000).toString()
        return when(action) {
            "memory" -> listOf("dumpsys","meminfo") + if(pkg.isBlank()) emptyList() else listOf(p())
            "battery" -> listOf("dumpsys","battery")
            "activity" -> listOf("dumpsys","activity","activities")
            "package" -> listOf("dumpsys","package",p())
            "appops" -> listOf("cmd","appops","get",p())
            "network" -> listOf("dumpsys","connectivity")
            "display" -> listOf("dumpsys","display")
            "system_list" -> listOf("ls", "-la", "--", systemPath(value, false))
            "system_stat" -> listOf("ls", "-ld", "--", systemPath(value, false))
            "system_read" -> listOf("head", "-c", "32768", "--", systemPath(value, true))
            "storage" -> listOf("df","-h")
            "processes" -> listOf("ps","-A")
            "list_packages" -> listOf("pm","list","packages","-f")
            "crashes" -> listOf("logcat","-b","crash","-d","-t",count)
            "logcat" -> listOf("logcat","-d","-v","threadtime","-t",count)
            "force_stop" -> listOf("am","force-stop",p())
            "clear_data" -> listOf("pm","clear",p())
            "disable" -> listOf("pm","disable-user","--user","current",p())
            "enable" -> listOf("pm","enable","--user","current",p())
            "uninstall" -> listOf("pm","uninstall","--user","current",p())
            "grant", "revoke" -> listOf("pm",action,p(),permission())
            "install", "screenshot" -> {
                if (!value.startsWith("/storage/") || value.contains('\u0000') || value.split('/').any { it == ".." })
                    throw IOException("Selecciona una ruta del almacenamiento compartido")
                if (action=="install") listOf("pm","install","-r",value) else listOf("screencap","-p",value)
            }
            "set_appop" -> {
                val parts=value.split(':')
                if (parts.size!=2 || !Regex("[A-Za-z0-9_]+").matches(parts[0]) || parts[1] !in setOf("allow","ignore","default","foreground"))
                    throw IOException("Usa OPERACION:modo")
                listOf("cmd","appops","set",p(),parts[0],parts[1])
            }
            "tap" -> listOf("input","tap",coordinate(x),coordinate(y))
            "swipe" -> listOf("input","swipe",coordinate(x),coordinate(y),coordinate(x2),coordinate(y2),"300")
            "keyevent" -> {
                val code=value.toIntOrNull() ?: throw IOException("Código de tecla no válido")
                if(code !in 0..400)throw IOException("Código fuera de rango")
                listOf("input","keyevent",code.toString())
            }
            "text" -> {
                if(value.length>1000 || value.contains('\u0000'))throw IOException("Texto demasiado largo")
                listOf("input","text",value.replace(" ","%s"))
            }
            else -> throw IOException("Operación de depuración no reconocida")
        }
    }
}
