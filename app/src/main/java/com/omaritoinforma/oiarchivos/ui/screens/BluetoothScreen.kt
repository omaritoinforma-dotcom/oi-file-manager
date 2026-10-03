package com.omaritoinforma.oiarchivos.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.Screen

@Composable
@SuppressLint("MissingPermission") // Bonded devices are read only after checking the runtime grant.
fun BluetoothScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var devices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED) {
            error = "Concede el permiso de dispositivos cercanos"
            return
        }
        runCatching {
                val adapter =
                    (ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                            as BluetoothManager)
                        .adapter
                        ?: throw IllegalStateException("Este teléfono no dispone de Bluetooth")
                if (!adapter.isEnabled) throw IllegalStateException("Activa Bluetooth")
                devices =
                    adapter.bondedDevices
                        .map { (it.name ?: "Dispositivo") to it.address }
                        .sortedBy { it.first }
                error = null
            }
            .onFailure { error = it.message }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { load() }
    LaunchedEffect(Unit) { load() }
    ToolPage(
        "Explorador Bluetooth",
        vm,
        actions = { TextButton(onClick = { load() }) { Text("Actualizar") } }) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(
                            "Elige un equipo vinculado que ofrezca el servicio de transferencia de archivos OBEX FTP. El equipo remoto debe autorizar el acceso a su carpeta.")
                    }
                    item {
                        TextButton(
                            onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }) {
                                Text("Vincular o activar Bluetooth")
                            }
                    }
                    if (Build.VERSION.SDK_INT >= 31 &&
                        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                            PackageManager.PERMISSION_GRANTED)
                        item {
                            Button(
                                onClick = {
                                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                }) {
                                    Text("Conceder permiso")
                                }
                        }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    items(devices, key = { it.second }) { (name, address) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(address) },
                            modifier =
                                Modifier.clickable {
                                    runCatching {
                                            val store = ConnectionStore(ctx)
                                            val saved = store.load()
                                            val connection =
                                                saved.firstOrNull {
                                                    it.protocol == Protocol.BLUETOOTH &&
                                                        it.host == address
                                                }
                                                    ?: Connection(
                                                        label = name,
                                                        protocol = Protocol.BLUETOOTH,
                                                        host = address,
                                                        port = 0,
                                                        user = "",
                                                        secret = "",
                                                        root = "/")
                                            if (saved.none { it.id == connection.id })
                                                store.save(saved + connection)
                                            vm.goTo(Screen.Remote(connection.id))
                                        }
                                        .onFailure {
                                            vm.toast(it.message ?: "No se pudo guardar el equipo")
                                        }
                                })
                    }
                    if (devices.isEmpty() && error == null)
                        item {
                            Text(
                                "No hay equipos vinculados. Abre los ajustes Bluetooth y vincula uno, luego pulsa Actualizar.")
                        }
                }
        }
}
