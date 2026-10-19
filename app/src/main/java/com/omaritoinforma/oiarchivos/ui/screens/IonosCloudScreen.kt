@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.IonosCloud
import com.omaritoinforma.oiarchivos.data.IonosItem
import com.omaritoinforma.oiarchivos.data.IonosListing
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** IONOS is a permanent explorer root, not a user-created SFTP shortcut. */
@Composable
fun IonosCloudScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val cloud = remember { IonosCloud(ctx) }
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf("/") }
    var refresh by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<IonosListing?>(cloud.cached("/")) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var makeFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<IonosItem?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<IonosItem?>(null) }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loading = true; error = ""
            try {
                withContext(Dispatchers.IO) { cloud.upload(folder, uri) }
                refresh++
            } catch (e: Exception) { error = e.message ?: "No se pudo subir" }
            finally { loading = false }
        }
    }
    // The displayed folder remains when offline; network recovery triggers a refresh.
    DisposableEffect(ctx) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val handler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler.post { refresh++ } }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    LaunchedEffect(folder, refresh) {
        listing = cloud.cached(folder)
        loading = true
        error = ""
        try {
            listing = withContext(Dispatchers.IO) { cloud.list(folder) }
        } catch (e: Exception) {
            error = e.message ?: "Servidor no disponible"
        } finally { loading = false }
    }
    fun operate(action: suspend () -> Unit) {
        scope.launch {
            loading = true; error = ""
            try { action(); refresh++ }
            catch (e: Exception) { error = e.message ?: "Error de operación" }
            finally { loading = false }
        }
    }
    fun open(item: IonosItem) {
        if (item.directory) { folder = item.path; return }
        operate {
            val local: File = withContext(Dispatchers.IO) {
                val cached = cloud.offlineCopy(item.path)
                if (!cloud.isUnmetered() && cached.isFile) cached
                else cloud.download(item)
            }
            vm.openFile(local.absolutePath)
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("IONOS · Mis archivos") },
            navigationIcon = {
                IconButton(onClick = {
                    if (folder == "/") vm.back()
                    else folder = folder.substringBeforeLast('/').ifBlank { "/" }
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            },actions = {
                IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "Actualizar") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(folder, Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge)
            if (listing?.stale == true)
                Text("Sin conexión: lista guardada. Solo se abren archivos descargados.",
                    Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.tertiary)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank())
                Text(error, Modifier.padding(18.dp),color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderName = ""; makeFolder = true }) {
                    Icon(Icons.Filled.CreateNewFolder,null)
                    Text(" Carpeta")
                }
                Button(onClick = { upload.launch("*/*") },enabled = !loading) {
                    Icon(Icons.Filled.CloudUpload,null)
                    Text(" Subir (Wi-Fi)")
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(listing?.items.orEmpty(), key = { it.path }) { item ->
                    var expanded by remember(item.path) { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = { Text(if (item.directory) "Carpeta" else
                            (item.size / 1024).toString() + " KiB" +
                            if (cloud.offlineCopy(item.path).isFile) " · Disponible sin conexión" else "") },
                        leadingContent = {
                            Icon(if(item.directory)Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null)
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { expanded = true }) {
                                    Icon(Icons.Filled.MoreVert, "Opciones")
                                }
                                DropdownMenu(expanded = expanded,onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(text = { Text("Renombrar") }, onClick = {
                                        expanded = false; renameTarget = item; renameName = item.name
                                    })
                                    if (!item.directory)
                                        DropdownMenuItem(text = { Text("Eliminar archivo") }, onClick = {
                                            expanded = false; deleteTarget = item
                                        })
                                }
                            }
                        },
                        modifier = Modifier.clickable { open(item) }
                    )
                    HorizontalDivider()
                }
                if (listing?.items?.isEmpty() == true) item {
                    Text("Carpeta vacía",Modifier.padding(22.dp))
                }
            }
        }
    }
    if (makeFolder)
        AlertDialog(onDismissRequest = { makeFolder = false },
            title = { Text("Nueva carpeta en IONOS") },
            text = { OutlinedTextField(folderName,{ folderName=it },label={Text("Nombre")}) },
            confirmButton = { TextButton(onClick = {
                makeFolder = false
                if(folderName.isNotBlank())operate {
                    withContext(Dispatchers.IO) { cloud.mkdir(folder.trimEnd('/')+"/"+folderName) }
                }
            }) { Text("Crear") } },
            dismissButton = { TextButton(onClick = { makeFolder = false }) { Text("Cancelar") } })
    renameTarget?.let { target ->
        AlertDialog(onDismissRequest = { renameTarget=null },title={Text("Renombrar")},
            text = {OutlinedTextField(renameName,{renameName=it},label={Text("Nombre nuevo")})},
            confirmButton={TextButton(onClick={
                renameTarget=null
                if(renameName.isNotBlank())operate {
                    withContext(Dispatchers.IO) {
                        cloud.rename(target.path,folder.trimEnd('/')+"/"+renameName)
                    }
                }
            }){Text("Guardar")}},
            dismissButton={TextButton(onClick={renameTarget=null}){Text("Cancelar")}})
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest={deleteTarget=null},title={Text("Eliminar archivo")},
            text={Text("¿Eliminar definitivamente "+target.name+" de IONOS?")},
            confirmButton={TextButton(onClick={
                deleteTarget=null
                operate { withContext(Dispatchers.IO) { cloud.delete(target.path) } }
            }){Text("Eliminar")}},
            dismissButton={TextButton(onClick={deleteTarget=null}){Text("Cancelar")}})
    }
}
