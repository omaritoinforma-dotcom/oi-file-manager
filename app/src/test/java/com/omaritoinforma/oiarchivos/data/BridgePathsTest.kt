package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgePathsTest {
    @Test fun rootAndDescendants() {
        assertEquals("/home/data",BridgePaths.within("/home/data/",""))
        assertEquals("/home/data/x",BridgePaths.within("/home/data","/home/data/./x"))
        assertEquals("/a",BridgePaths.within("/","/a"))
    }
    @Test fun refusesSiblingTraversalAndMalformedPaths() {
        for (path in listOf("/home/database/x","/home/data/../secret","/../x","relative","/home/data/evil\\path","/home/data/\u0000")) {
            assertThrows(path,IOException::class.java) { BridgePaths.within("/home/data",path) }
        }
    }
}
