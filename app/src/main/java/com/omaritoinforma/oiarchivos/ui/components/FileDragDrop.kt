@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.omaritoinforma.oiarchivos.ui.components

import android.content.ClipData
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.unit.dp

private const val FILE_DRAG_MIME = "application/x-oi-files"
private data class LocalFileDrag(val paths: List<String>)

@Composable
fun FileDragHandle(paths: List<String>) {
    val currentPaths by rememberUpdatedState(paths)
    Icon(Icons.Default.DragIndicator, "Mantén pulsado y arrastra al otro panel",
        Modifier.padding(12.dp).dragAndDropSource {
            detectTapGestures(onLongPress = {
                val selected = currentPaths.distinct().take(100000)
                if (selected.isNotEmpty()) startTransfer(DragAndDropTransferData(
                    ClipData("Archivos", arrayOf(FILE_DRAG_MIME), ClipData.Item("${selected.size} elementos")),
                    localState = LocalFileDrag(selected)))
            })
        })
}

/** Only drags started by this app can carry local filesystem paths. */
@Composable
fun fileDropTarget(onDrop: (List<String>) -> Unit): Modifier {
    val callback by rememberUpdatedState(onDrop)
    var entered by remember { mutableStateOf(false) }
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = event.toAndroidDragEvent().localState as? LocalFileDrag ?: return false
                if (files.paths.isEmpty()) return false
                callback(files.paths)
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { entered = true }
            override fun onExited(event: DragAndDropEvent) { entered = false }
            override fun onEnded(event: DragAndDropEvent) { entered = false }
        }
    }
    return Modifier.then(if (entered) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
        .dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().contains(FILE_DRAG_MIME) && it.toAndroidDragEvent().localState is LocalFileDrag }, target = target)
}
