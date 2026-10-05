package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationService

@Composable
fun ConnectionsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val store = remember { ConnectionStore(ctx) }
    var accounts by remember { mutableStateOf<List<Connection>>(emptyList()) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Connection?>(null) }
    var scan by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var additionalLogin by remember { mutableStateOf<Connection?>(null) }
    val authService = remember { AuthorizationService(ctx) }
    DisposableEffect(authService) { onDispose { authService.dispose() } }
    val oauth =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val account =
                runCatching { store.load().firstOrNull { it.id == pendingId } }.getOrNull()
            pendingId = null
            if (account != null)
                CloudAuth.finish(ctx, authService, account, result.data) { outcome ->
                    outcome
                        .onSuccess { refresh++ }
                        .onFailure { vm.toast(it.message ?: tr("No se pudo entrar")) }
                }
        }
    val google =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            result ->
            runCatching {
                    val data =
                        result.data ?: throw java.io.IOException(tr("Inicio de sesión cancelado"))
                    CloudAuth.googleFinish(
                        ctx,
                        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data))
                }
                .onSuccess { refresh++ }
                .onFailure { vm.toast(it.message ?: tr("No se pudo autorizar Google Drive")) }
        }
    fun loginGoogle() {
        Identity.getAuthorizationClient(ctx)
            .authorize(CloudAuth.googleRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution())
                    google.launch(
                        IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                else
                    runCatching { CloudAuth.googleFinish(ctx, result) }
                        .onSuccess { refresh++ }
                        .onFailure { vm.toast(it.message ?: tr("No se pudo entrar")) }
            }
            .addOnFailureListener { vm.toast(tr("No se pudo autorizar Google Drive: {0}", it.message)) }
    }
    val tree =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    .onFailure {
                        vm.toast(tr("Permiso temporal; vuelve a elegir la carpeta si caduca"))
                    }
                vm.goTo(Screen.Documents(uri.toString()))
            }
        }
    LaunchedEffect(edit, refresh) {
        withContext(Dispatchers.IO) { runCatching { store.load() } }
            .onSuccess { accounts = it }
            .onFailure { loadingError = tr("No se pudieron leer las conexiones: {0}", it.message) }
    }
    ToolPage(
        tr("Red, nube y USB"),
        vm,
        actions = {
            TextButton(
                onClick = {
                    selected = null
                    prefilled = false
                    edit = true
                }) {
                    Text(tr("Agregar"))
                }
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item {
                    ListItem(
                        headlineContent = { Text(tr("Entrar en Google Drive")) },
                        supportingContent = {
                            Text(tr("Elegir una cuenta y autorizar acceso a sus archivos"))
                        },
                        modifier = Modifier.clickable { loginGoogle() })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Enviar a otro teléfono")) },
                        supportingContent = { Text(tr("Enviar o recibir archivos por la misma Wi-Fi")) },
                        modifier = Modifier.clickable { vm.goTo(Screen.Nearby) })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Descargar desde una URL")) },
                        supportingContent = { Text(tr("Gestor de descargas; si se corta, continúa donde iba")) },
                        modifier = Modifier.clickable { downloadUrl = true })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Enviar a la TV")) },
                        supportingContent = { Text(tr("Fotos, música y vídeos en un televisor DLNA")) },
                        modifier = Modifier.clickable { vm.goTo(Screen.Cast) })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Android TV por ADB")) },
                        supportingContent = { Text(tr("Instalar APK, abrir o quitar apps y usar el teléfono como mando")) },
                        modifier = Modifier.clickable { vm.goTo(Screen.AdbTv) })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Buscar en la red local")) },
                        supportingContent = {
                            Text(tr("Servidores SMB, FTP, FTPS y SFTP de tu Wi-Fi"))
                        },
                        modifier = Modifier.clickable { scan = true })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Explorar equipos por Bluetooth")) },
                        supportingContent = { Text(tr("Archivos y carpetas mediante OBEX FTP")) },
                        modifier = Modifier.clickable { vm.goTo(Screen.Bluetooth) })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Carpeta de SD / USB / proveedor de nube")) },
                        supportingContent = {
                            Text(tr("Elegir una carpeta usando el selector de Android"))
                        },
                        modifier = Modifier.clickable { tree.launch(null) })
                }
                item {
                    ListItem(
                        headlineContent = { Text(tr("Compartir por Wi-Fi / FTP")) },
                        supportingContent = { Text(tr("Acceder desde una computadora")) },
                        modifier = Modifier.clickable { vm.goTo(Screen.Sharing) })
                }
                if (loadingError != null)
                    item {
                        Text(
                            loadingError!!,
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error)
                    }
                items(accounts, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.label) },
                        supportingContent = { Text(c.protocol.label) },
                        trailingContent = {
                            Column {
                                if (c.protocol == Protocol.BAIDU ||
                                    c.protocol == Protocol.SUGARSYNC)
                                    TextButton(onClick = { additionalLogin = c }) { Text(tr("Entrar")) }
                                if (c.protocol in CloudAuth.providers && c.clientId.isNotBlank())
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                    pendingId = c.id
                                                    oauth.launch(CloudAuth.request(authService, c))
                                                }
                                                .onFailure {
                                                    vm.toast(it.message ?: tr("No se pudo entrar"))
                                                }
                                        }) {
                                            Text(tr("Entrar"))
                                        }
                                TextButton(
                                    onClick = {
                                        selected = c
                                        edit = true
                                    }) {
                                        Text(tr("Editar"))
                                    }
                            }
                        },
                        modifier = Modifier.clickable { vm.goTo(Screen.Remote(c.id)) })
                }
                if (accounts.isEmpty())
                    item {
                        Text(
                            tr("Agrega un servidor FTP, FTPS, SFTP, SMB, WebDAV o una cuenta de nube. Puedes guardar varias cuentas de cada proveedor."),
                            Modifier.padding(20.dp))
                    }
            }
        }
    additionalLogin?.let { c ->
        AdditionalCloudLoginDialog(
            c,
            onDone = {
                additionalLogin = null
                refresh++
            },
            onDismiss = { additionalLogin = null })
    }
    if (downloadUrl) DownloadUrlDialog(vm) { downloadUrl = false }
    if (scan)
        LanScanDialog(onDismiss = { scan = false }) { host ->
            scan = false
            selected =
                Connection(
                    label = host.name.ifBlank { "${host.protocol.label} ${host.address}" },
                    protocol = host.protocol,
                    host =
                        if (host.protocol == Protocol.WEBDAV) "http://${host.address}:${host.port}"
                        else host.address,
                    port = host.port,
                    user = "",
                    secret = "")
            prefilled = true
            edit = true
        }
    if (edit)
        ConnectionDialog(
            selected,
            isNew = selected == null || prefilled,
            onDismiss = { edit = false },
            onSave = { c ->
                try {
                    store.save(accounts.filter { it.id != c.id } + c)
                    edit = false
                } catch (e: Exception) {
                    vm.toast(e.message ?: tr("No se pudo guardar"))
                }
            },
            onDelete = { c ->
                store.save(accounts.filter { it.id != c.id })
                edit = false
            })
}

@Composable
private fun ConnectionDialog(
    existing: Connection?,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Connection) -> Unit,
    onDelete: (Connection) -> Unit
) {
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.SFTP) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(existing?.user.orEmpty()) }
    var secret by remember { mutableStateOf(existing?.secret.orEmpty()) }
    var root by remember { mutableStateOf(existing?.root ?: "/") }
    var fingerprint by remember { mutableStateOf(existing?.fingerprint.orEmpty()) }
    var clientId by remember { mutableStateOf(existing?.clientId.orEmpty()) }
    var clientSecret by remember { mutableStateOf(existing?.clientSecret.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val cloud =
        protocol in
            setOf(
                Protocol.DRIVE,
                Protocol.DROPBOX,
                Protocol.ONEDRIVE,
                Protocol.BOX,
                Protocol.YANDEX,
                Protocol.BAIDU,
                Protocol.SUGARSYNC)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr("Nueva conexión") else tr("Editar conexión")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        label, { label = it }, label = { Text(tr("Nombre de la conexión")) })
                }
                item {
                    Column {
                        Protocol.entries
                            .filter { it != Protocol.ROOT && it != Protocol.BLUETOOTH }
                            .chunked(3)
                            .forEach { row ->
                                Row {
                                    row.forEach { p ->
                                        FilterChip(
                                            protocol == p,
                                            onClick = {
                                                protocol = p
                                                port =
                                                    when (p) {
                                                        Protocol.FTP -> "21"
                                                        Protocol.FTPS -> "21"
                                                        Protocol.FTPS_IMPLICIT -> "990"
                                                        Protocol.SFTP -> "22"
                                                        Protocol.SMB -> "445"
                                                        Protocol.NFS -> "2049"
                                                        else -> "443"
                                                    }
                                                root =
                                                    when (p) {
                                                        Protocol.DRIVE -> "root"
                                                        Protocol.BOX -> "0"
                                                        Protocol.YANDEX -> "disk:/"
                                                        else -> "/"
                                                    }
                                            },
                                            label = { Text(p.label) })
                                    }
                                }
                            }
                    }
                }
                if (!cloud) {
                    item {
                        OutlinedTextField(
                            host,
                            { host = it },
                            label = {
                                Text(
                                    if (protocol == Protocol.WEBDAV || protocol == Protocol.S3)
                                        tr("URL completa https://…")
                                    else tr("Servidor"))
                            })
                    }
                    // NFS encuentra sus puertos por el portmapper: no hay puerto que elegir.
                    if (protocol != Protocol.NFS)
                        item { OutlinedTextField(port, { port = it }, label = { Text(tr("Puerto")) }) }
                    item {
                        OutlinedTextField(
                            user,
                            { user = it },
                            label = {
                                Text(if (protocol == Protocol.NFS) tr("Usuario y grupo uid:gid") else tr("Usuario"))
                            },
                            supportingText = {
                                if (protocol == Protocol.NFS)
                                    Text(tr("Vacío: 65534:65534 («nobody»). NFS no cifra nada: úsalo en una red de confianza"))
                            })
                    }
                }
                if (protocol == Protocol.SUGARSYNC) {
                    item {
                        OutlinedTextField(
                            user, { user = it }, label = { Text(tr("Correo de SugarSync")) })
                    }
                    item {
                        OutlinedTextField(
                            host,
                            { host = it },
                            label = { Text(tr("Identificador de tu aplicación SugarSync /sc/…")) })
                    }
                }
                if (protocol != Protocol.NFS)
                  item {
                    OutlinedTextField(
                        secret,
                        { secret = it },
                        label = {
                            Text(
                                if (cloud) tr("Token de acceso (opcional si usas Entrar)")
                                else tr("Contraseña"))
                        },
                        visualTransformation = PasswordVisualTransformation())
                }
                item {
                    OutlinedTextField(
                        root,
                        { root = it },
                        label = {
                            Text(
                                if (protocol == Protocol.S3) "/nombre-del-bucket/carpeta"
                                else if (protocol == Protocol.BOX) tr("ID de carpeta, 0 para la raíz")
                                else if (protocol == Protocol.DRIVE) tr("ID de carpeta o root")
                                else if (protocol == Protocol.SMB) tr("Carpeta compartida /nombre")
                                else if (protocol == Protocol.NFS) tr("Ruta exportada /srv/datos")
                                else tr("Carpeta inicial"))
                        })
                }
                if (protocol == Protocol.S3)
                    item {
                        OutlinedTextField(
                            fingerprint,
                            { fingerprint = it },
                            label = { Text(tr("Región de S3, por ejemplo us-east-1")) })
                    }
                if (protocol == Protocol.SFTP)
                    item {
                        OutlinedTextField(
                            fingerprint,
                            { fingerprint = it },
                            label = { Text(tr("Huella del servidor SHA256:…")) },
                            supportingText = {
                                Text(
                                    tr("Cópiala del administrador del servidor. No se aceptan claves distintas."))
                            })
                    }
                if (protocol in CloudAuth.providers ||
                    protocol == Protocol.BAIDU ||
                    protocol == Protocol.SUGARSYNC) {
                    item {
                        OutlinedTextField(
                            clientId,
                            { clientId = it },
                            label = {
                                Text(
                                    if (protocol == Protocol.SUGARSYNC)
                                        tr("Access Key ID de tu aplicación")
                                    else tr("Identificador de la aplicación OAuth"))
                            },
                            supportingText = {
                                Text(
                                    tr("Tras guardar, pulsa Entrar para autorizar la cuenta y renovar el acceso automáticamente."))
                            })
                    }
                    item {
                        OutlinedTextField(
                            clientSecret,
                            { clientSecret = it },
                            label = { Text(tr("Clave de tu aplicación (si el proveedor la exige)")) },
                            visualTransformation = PasswordVisualTransformation())
                    }
                }
                if (cloud && clientId.isBlank())
                    item {
                        Text(
                            tr("Esta conexión usa un token de tu cuenta con permisos de archivos. Cuando caduque, actualízalo aquí. También puedes usar el selector de Android si tienes instalada la app de tu nube."))
                    }
                if (protocol == Protocol.FTP)
                    item {
                        Text(
                            tr("FTP envía datos y contraseña sin cifrar. Para conexiones de Internet usa FTPS o SFTP."))
                    }
                if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val n = port.toIntOrNull()
                    if (label.isBlank() ||
                        (!cloud && host.isBlank()) ||
                        n == null ||
                        n !in 1..65535 ||
                        (protocol == Protocol.SFTP && fingerprint.isBlank()) ||
                        (cloud && secret.isBlank() && clientId.isBlank())) {
                        error = tr("Completa los campos y revisa el puerto")
                    } else
                        onSave(
                            Connection(
                                existing?.id ?: java.util.UUID.randomUUID().toString(),
                                label.trim(),
                                protocol,
                                host.trim(),
                                n,
                                user,
                                secret,
                                root.trim(),
                                fingerprint.trim(),
                                clientId.trim(),
                                clientSecret,
                                if (protocol == existing?.protocol &&
                                    secret == existing?.secret &&
                                    clientId == existing?.clientId)
                                    existing?.authState.orEmpty()
                                else "",
                                if (secret == existing?.secret) existing?.expiresAt ?: 0 else 0,
                                existing?.googleAccount.orEmpty()))
                }) {
                    Text(tr("Guardar"))
                }
        },
        dismissButton = {
            Row {
                if (existing != null && !isNew)
                    TextButton(onClick = { onDelete(existing) }) { Text(tr("Eliminar")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancelar")) }
            }
        })
}

/** «Descargar desde una URL»: el archivo va a la carpeta de descargas de Ajustes → Carpetas. */
@Composable
private fun DownloadUrlDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Descargar desde una URL")) },
        text = {
            Column {
                OutlinedTextField(
                    url,
                    {
                        url = it
                        error = null
                    },
                    label = { Text(tr("Dirección (URL)")) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: tr("Se guarda en {0}", vm.downloadFolder.value)) })
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    val problem = UrlDownloader.problem(url)
                    if (problem != null) {
                        error = problem
                        return@TextButton
                    }
                    val link = url.trim()
                    val folder = java.io.File(vm.downloadFolder.value)
                    onDismiss()
                    vm.runTask(tr("Descargando")) { report ->
                        val file = UrlDownloader.download(link, folder, report)
                        OperationResult(tr("Descargado: {0}", file.name), listOf(file))
                    }
                }) {
                    Text(tr("Descargar"))
                }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } })
}
