package com.omaritoinforma.oiarchivos.data
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DebugCommandsTest {
    @Test fun commandsAreSeparateArguments() {
        assertEquals(listOf("am","force-stop","com.example.app"),DebugCommands.argv("force_stop","com.example.app"))
        assertEquals(listOf("input","text","hello;%sreboot"),DebugCommands.argv("text",value="hello; reboot"))
    }
    @Test fun rejectsShellInjectionRootAndInvalidCoordinates() {
        assertThrows(IOException::class.java){DebugCommands.argv("force_stop","com.example.app;reboot")}
        assertThrows(IOException::class.java){DebugCommands.argv("su")}
        assertThrows(IOException::class.java){DebugCommands.argv("tap",x=-1)}
        assertThrows(IOException::class.java){DebugCommands.argv("install",value="/storage/emulated/0/../secret.apk")}
    }
    @Test fun safeReadOnlySystemInspection() {
        assertEquals(listOf("ls", "-la", "--", "/"), DebugCommands.argv("system_list"))
        assertEquals(listOf("ls", "-ld", "--", "/system"), DebugCommands.argv("system_stat", value="/system"))
        assertEquals(listOf("head", "-c", "32768", "--", "/system/build.prop"),
            DebugCommands.argv("system_read", value="/system/build.prop"))
        assertThrows(IOException::class.java) { DebugCommands.argv("system_read", value="/data/data/com.example.app/shared_prefs") }
        assertThrows(IOException::class.java) { DebugCommands.argv("system_read", value="/proc/self/environ") }
        assertThrows(IOException::class.java) { DebugCommands.argv("system_read", value="/storage/emulated/0/secret.txt") }
        assertThrows(IOException::class.java) { DebugCommands.argv("system_list", value="/../../data") }
        assertThrows(IOException::class.java) { DebugCommands.argv("system_stat", value="/data/user/0/com.example.app") }
        assertThrows(IOException::class.java) { DebugCommands.argv("system_read", value="/system/etc/../build.prop") }
        assertEquals("system_list" in DebugCommands.changes, false)
    }
    @Test fun boundedOutputAndValidatedPermissions() {
        assertEquals("1000",DebugCommands.argv("logcat",lines=100000).last())
        assertThrows(IOException::class.java){DebugCommands.argv("grant","com.example.app","android.permission.CAMERA;reboot")}
    }
}
