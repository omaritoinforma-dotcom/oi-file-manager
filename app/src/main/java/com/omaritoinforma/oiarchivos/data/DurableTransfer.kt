package com.omaritoinforma.oiarchivos.data

/** A recoverable operation; its journal contains paths and IDs, never credentials. */
interface DurableTransfer {
    val id: String
    val title: String
    val destination: String
    val completed: Int
    val count: Int
    val disconnectOnPause: Boolean
        get() = false

    suspend fun run(report: (OpProgress) -> Unit): OperationResult

    fun discard()
}
