@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.Screen
import java.io.File
import com.omaritoinforma.oiarchivos.data.tr

/** Pide un nombre de lista (nueva o para renombrar). */
@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it }, label = { Text(tr("Nombre de la lista")) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(tr("Aceptar"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } })
}

/** «Añadir a lista de reproducción» desde la selección del explorador. */
@Composable
fun AddToPlaylistDialog(vm: MainViewModel, paths: List<String>, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshPlaylists() }
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        PlaylistNameDialog(tr("Nueva lista"), "", { creating = false }) { name ->
            if (vm.editPlaylists { vm.playlists.create(name) }) {
                vm.addToPlaylist(name, paths)
                onDismiss()
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Añadir a lista de reproducción")) },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(tr("Nueva lista…")) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                        modifier = Modifier.clickable { creating = true })
                }
                items(vm.playlistNames) { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        modifier =
                            Modifier.clickable {
                                vm.addToPlaylist(name, paths)
                                onDismiss()
                            })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } })
}

@Composable
fun PlaylistsScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.refreshPlaylists() }
    var creating by remember { mutableStateOf(false) }
    if (creating)
        PlaylistNameDialog(tr("Nueva lista"), "", { creating = false }) { name ->
            if (vm.editPlaylists { vm.playlists.create(name) }) creating = false
        }
    ToolPage(
        tr("Listas de reproducción"),
        vm,
        actions = { TextButton(onClick = { creating = true }) { Text(tr("Nueva")) } }) { pad ->
            if (vm.playlistNames.isEmpty())
                Text(
                    tr("No hay listas. Selecciona canciones o vídeos en el explorador y usa «Más» → «Añadir a lista de reproducción»."),
                    Modifier.padding(pad).padding(16.dp))
            else
                LazyColumn(Modifier.padding(pad)) {
                    items(vm.playlistNames) { name ->
                        val count =
                            remember(name) {
                                runCatching { vm.playlists.read(name).tracks.size }.getOrDefault(0)
                            }
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(tr("{0} pista(s)", count)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            modifier = Modifier.clickable { vm.goTo(Screen.Playlist(name)) })
                    }
                }
        }
}

@Composable
fun PlaylistScreen(vm: MainViewModel, name: String) {
    var tracks by remember(name) { mutableStateOf<List<String>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(name, version) {
        tracks = runCatching { vm.playlists.read(name).tracks }.getOrDefault(emptyList())
    }
    fun change(block: () -> Unit) {
        if (vm.editPlaylists(change = block)) version++
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming)
        PlaylistNameDialog(tr("Renombrar lista"), name, { renaming = false }) { to ->
            if (vm.editPlaylists { vm.playlists.rename(name, to) }) {
                renaming = false
                vm.back()
                vm.goTo(Screen.Playlist(to.trim()))
            }
        }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(tr("¿Eliminar la lista «{0}»?", name)) },
            text = { Text(tr("Los archivos no se borran, solo la lista.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        if (vm.editPlaylists(tr("Lista eliminada")) { vm.playlists.delete(name) })
                            vm.back()
                    }) {
                        Text(tr("Eliminar"))
                    }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(tr("Cancelar")) } })
    val present = tracks.filter { File(it).isFile }
    ToolPage(
        name,
        vm,
        actions = {
            TextButton(
                onClick = { vm.goTo(Screen.PlayPlaylist(name)) }, enabled = present.isNotEmpty()) {
                    Text(tr("Reproducir"))
                }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Más")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Renombrar")) },
                        onClick = {
                            menu = false
                            renaming = true
                        })
                    DropdownMenuItem(
                        text = { Text(tr("Eliminar lista")) },
                        onClick = {
                            menu = false
                            deleting = true
                        })
                }
            }
        }) { pad ->
            if (tracks.isEmpty())
                Text(tr("La lista está vacía."), Modifier.padding(pad).padding(16.dp))
            else
                LazyColumn(Modifier.padding(pad)) {
                    itemsIndexed(tracks) { index, path ->
                        val file = File(path)
                        val exists = path in present
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = {
                                Text(if (exists) file.parent.orEmpty() else tr("No está en el teléfono"))
                            },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        onClick = { change { vm.playlists.move(name, index, -1) } },
                                        enabled = index > 0) {
                                            Icon(Icons.Filled.ArrowUpward, tr("Subir"))
                                        }
                                    IconButton(
                                        onClick = { change { vm.playlists.move(name, index, 1) } },
                                        enabled = index < tracks.lastIndex) {
                                            Icon(Icons.Filled.ArrowDownward, tr("Bajar"))
                                        }
                                    IconButton(
                                        onClick = { change { vm.playlists.remove(name, path) } }) {
                                            Icon(Icons.Filled.Close, tr("Quitar"))
                                        }
                                }
                            },
                            modifier =
                                Modifier.clickable(enabled = exists) {
                                    vm.goTo(Screen.PlayPlaylist(name, present.indexOf(path)))
                                })
                    }
                }
        }
}
