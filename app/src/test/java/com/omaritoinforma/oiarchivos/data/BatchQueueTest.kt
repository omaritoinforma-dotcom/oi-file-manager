package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class BatchQueueTest {
    @Test
    fun runsInOrderAndSummarizesFailures() {
        val q = BatchQueue(7L, listOf("uno.apk", "dos.apk", "tres.apk")) { it }
        assertEquals("uno.apk", q.next())
        val first = q.key
        assertTrue(q.result(first, success = true))
        assertEquals("dos.apk", q.next())
        assertTrue(q.result(q.key, success = false, reason = "cancelado"))
        assertEquals("tres.apk", q.next())
        assertTrue(q.result(q.key, success = true))
        assertNull(q.next())
        assertEquals(3, q.done)
        assertEquals("Instaladas 2 de 3. dos.apk: cancelado", q.summary("Instaladas"))
    }

    @Test
    fun ignoresLateRepeatedOrForeignResults() {
        val q = BatchQueue(1L, listOf("a", "b")) { it }
        q.next()
        val a = q.key
        assertTrue(q.result(a, success = true))
        // Un aviso repetido de la tarea ya terminada no cuenta dos veces.
        assertFalse(q.result(a, success = false, reason = "repetido"))
        q.next()
        // Un aviso de otra tanda (otro id) tampoco.
        val other = BatchQueue(2L, listOf("x")) { it }
        other.next()
        assertFalse(q.result(other.key, success = false, reason = "ajeno"))
        assertTrue(q.result(q.key, success = true))
        assertEquals("Desinstaladas 2 de 2", q.summary("Desinstaladas"))
    }
}
