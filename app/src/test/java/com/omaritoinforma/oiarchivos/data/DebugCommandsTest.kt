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
    @Test fun boundedOutputAndValidatedPermissions() {
        assertEquals("1000",DebugCommands.argv("logcat",lines=100000).last())
        assertThrows(IOException::class.java){DebugCommands.argv("grant","com.example.app","android.permission.CAMERA;reboot")}
    }
}
