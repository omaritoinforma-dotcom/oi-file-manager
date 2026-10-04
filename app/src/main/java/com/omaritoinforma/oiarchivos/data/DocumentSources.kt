package com.omaritoinforma.oiarchivos.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** URIs retain the tree grant; a cached preview is never the authoritative document. */
data class DocumentOrigin(
    val treeUri: String,
    val parentUri: String,
    val documentUri: String,
    val name: String,
    val mimeType: String,
    val fingerprint: DocumentFingerprint
)

data class DocumentClip(val treeUri: String, val ids: List<String>, val move: Boolean)

object DocumentClipboard {
    var value by mutableStateOf<DocumentClip?>(null)
}
