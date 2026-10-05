package com.omaritoinforma.oiarchivos.data

/**
 * A transfer that can survive Activity/process loss because its state is journaled on disk.
 *
 * Implementations must never persist credentials or access tokens inside their journal.
 */
interface DurableTransfer {
    val id: String
    val title: String
    val destination: String
    val completed: Int
    val count: Int

    suspend fun run(report: (OpProgress) -> Unit): OperationResult

    suspend fun discard()
}
