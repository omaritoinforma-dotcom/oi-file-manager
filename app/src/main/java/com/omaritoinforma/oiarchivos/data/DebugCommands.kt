package com.omaritoinforma.oiarchivos.data

import java.io.IOException

/** Structured ADB commands only; no shell interpreter and no root command. */
object DebugCommands {
    val changes = setOf("force_stop","clear_data","disable","enable","install","uninstall","grant","revoke","set_appop","tap","swipe","keyevent","text","screenshot")
    fun packageName(value: String): String {
        if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(value)) throw IOException("Paquete no válido")
        return value
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
