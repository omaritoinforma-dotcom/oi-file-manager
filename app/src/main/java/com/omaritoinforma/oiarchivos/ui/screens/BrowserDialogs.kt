package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.FileItem
import com.omaritoinforma.oiarchivos.data.FileRepo
import com.omaritoinforma.oiarchivos.data.PropInfo
import com.omaritoinforma.oiarchivos.data.RenameRules
import com.omaritoinforma.oiarchivos.data.SortBy
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.Opener
import com.omaritoinforma.oiarchivos.util.formatDate
import com.omaritoinforma.oiarchivos.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BrowserDialog {
    data object CreateMenu : BrowserDialog
    data class Create(val folder: Boolean) : BrowserDialog
    data class Rename(val item: FileItem) : BrowserDialog
    data class BatchRename(val items: List<FileItem>) : BrowserDialog
    data class Delete(val items: List<FileItem>) : BrowserDialog
    data class Properties(val items: List<FileItem>) : BrowserDialog
    data class Compress(val items: List<FileItem>) : BrowserDialog
    data class OpenArchive(val item: FileItem) : BrowserDialog
    data class OpenApk(val item: FileItem) : BrowserDialog
    data class Encrypt(val item: FileItem, val decrypt: Boolean) : BrowserDialog
    data class InspectApk(val item: FileItem) : BrowserDialog
    data object Sort : BrowserDialog
}

@Composable
fun BrowserDialogs(vm: MainViewModel, dialog: BrowserDialog, setDialog: (BrowserDialog?) -> Unit) {
    val ctx = LocalContext.current
    val dismiss: () -> Unit = { setDialog(null) }
    when (dialog) {
        is BrowserDialog.Encrypt -> PasswordDialog(if(dialog.decrypt) "Descifrar archivo" else "Cifrar archivo",dismiss) { password ->
            vm.encrypt(dialog.item,password,dialog.decrypt); dismiss()
        }
        is BrowserDialog.InspectApk -> ApkInfoDialog(dialog.item,dismiss)

        BrowserDialog.CreateMenu -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Crear nuevo") },
            text = {
                Column {
                    DialogOption(Icons.Filled.CreateNewFolder, "Carpeta") { setDialog(BrowserDialog.Create(folder = true)) }
                    DialogOption(Icons.Filled.NoteAdd, "Archivo vacío") { setDialog(BrowserDialog.Create(folder = false)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
        )

        is BrowserDialog.Create -> NameDialog(
            title = if (dialog.folder) "Nueva carpeta" else "Nuevo archivo",
            initial = "",
            confirm = "Crear",
            onDismiss = dismiss,
        ) {
            vm.create(it, dialog.folder)
            dismiss()
        }

        is BrowserDialog.Rename -> NameDialog(
            title = "Renombrar",
            initial = dialog.item.name,
            confirm = "Renombrar",
            selectBase = !dialog.item.isDirectory,
            onDismiss = dismiss,
        ) {
            vm.rename(dialog.item, it)
            dismiss()
        }

        is BrowserDialog.BatchRename -> BatchRenameDialog(dialog.items, dismiss) { rules ->
            vm.batchRename(dialog.items, rules)
            dismiss()
        }

        is BrowserDialog.Delete -> {
            var toTrash by remember { mutableStateOf(vm.useTrash) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("Eliminar") },
                text = {
                    Column {
                        Text(
                            if (dialog.items.size == 1) "¿Eliminar «${dialog.items[0].name}»?"
                            else "¿Eliminar ${dialog.items.size} elementos?",
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { toTrash = !toTrash },
                        ) {
                            Checkbox(checked = toTrash, onCheckedChange = { toTrash = it })
                            Text("Mover a la papelera (se puede restaurar)")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.delete(dialog.items, toTrash)
                        dismiss()
                    }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
            )
        }

        is BrowserDialog.Properties -> PropertiesDialog(dialog.items, dismiss)

        is BrowserDialog.Compress -> CompressDialog(dialog.items,dismiss) { name,password ->
            vm.compress(dialog.items,name,password);dismiss()
        }

        is BrowserDialog.OpenArchive -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(dialog.item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    DialogOption(Icons.Filled.Unarchive, "Extraer aquí (en una carpeta nueva)") {
                        vm.extract(dialog.item)
                        dismiss()
                    }
                    DialogOption(Icons.Filled.OpenInNew, "Abrir con otra app") {
                        Opener.open(ctx, dialog.item.file, chooser = true)
                        dismiss()
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
        )

        is BrowserDialog.OpenApk -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(dialog.item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    DialogOption(Icons.Filled.Android, "Instalar") {
                        Opener.installApk(ctx, dialog.item.file)
                        dismiss()
                    }
                    DialogOption(Icons.Filled.Unarchive, "Extraer contenido") {
                        vm.extract(dialog.item)
                        dismiss()
                    }
                    DialogOption(Icons.Filled.OpenInNew, "Abrir con otra app") {
                        Opener.open(ctx, dialog.item.file, chooser = true)
                        dismiss()
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
        )

        BrowserDialog.Sort -> {
            var sel by remember { mutableStateOf(vm.sortBy) }
            var asc by remember { mutableStateOf(vm.ascending) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("Ordenar por") },
                text = {
                    Column {
                        SortBy.entries.forEach { s ->
                            RadioRow(s.label, sel == s) { sel = s }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        RadioRow("Ascendente (A→Z, antiguo→nuevo)", asc) { asc = true }
                        RadioRow("Descendente (Z→A, nuevo→antiguo)", !asc) { asc = false }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.setSort(sel, asc)
                        dismiss()
                    }) { Text("Aplicar") }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
            )
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun DialogOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    selectBase: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dot = initial.lastIndexOf('.')
    val baseLen = if (selectBase && dot > 0) dot else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, baseLen))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LaunchedEffect(Unit) {
                delay(150)
                runCatching { focus.requestFocus() }
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun BatchRenameDialog(items: List<FileItem>, onDismiss: () -> Unit, onConfirm: (RenameRules) -> Unit) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var numberFrom by remember { mutableStateOf("") }
    var ext by remember { mutableStateOf("") }
    val rules = RenameRules(find, replace, prefix, suffix, numberFrom.toIntOrNull(), ext.trim().ifBlank { null })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renombrar ${items.size} elementos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(find, { find = it }, label = { Text("Buscar texto") }, singleLine = true)
                OutlinedTextField(replace, { replace = it }, label = { Text("Reemplazar por") }, singleLine = true)
                OutlinedTextField(prefix, { prefix = it }, label = { Text("Agregar al inicio") }, singleLine = true)
                OutlinedTextField(suffix, { suffix = it }, label = { Text("Agregar al final") }, singleLine = true)
                OutlinedTextField(
                    numberFrom,
                    { v -> numberFrom = v.filter { it.isDigit() } },
                    label = { Text("Numerar desde (vacío = no)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(ext, { ext = it }, label = { Text("Nueva extensión (vacío = igual)") }, singleLine = true)
                Text("Vista previa", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                items.take(4).forEachIndexed { i, it ->
                    Text(
                        "${it.name}  →  ${rules.apply(it.name, i, it.isDirectory)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (items.size > 4) Text("…y ${items.size - 4} más", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(rules) }) { Text("Renombrar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PropertiesDialog(items: List<FileItem>, onDismiss: () -> Unit) {
    val info by produceState<PropInfo?>(null, items) {
        value = withContext(Dispatchers.IO) { runCatching { FileRepo.props(items) }.getOrNull() }
    }
    var hashes by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var hashing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val single = items.singleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(single?.name ?: "${items.size} elementos", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (single != null) {
                        PropRow("Ruta", single.path)
                        PropRow(
                            "Tipo",
                            if (single.isDirectory) "Carpeta"
                            else Opener.mime(single.file).takeIf { it != "*/*" } ?: "Archivo .${single.extension}",
                        )
                        PropRow("Modificado", formatDate(single.lastModified))
                        PropRow(
                            "Permisos",
                            buildString {
                                append(if (single.file.canRead()) "Lectura" else "Sin lectura")
                                append(" · ")
                                append(if (single.file.canWrite()) "Escritura" else "Solo lectura")
                                if (single.isHidden) append(" · Oculto")
                            },
                        )
                    } else {
                        PropRow("Ubicación", items.firstOrNull()?.file?.parent ?: "")
                    }
                    val i = info
                    PropRow(
                        "Tamaño",
                        if (i == null) "Calculando…" else "${formatSize(i.bytes)} (${"%,d".format(i.bytes)} bytes)",
                    )
                    if (single == null || single.isDirectory) {
                        PropRow("Contenido", if (i == null) "Calculando…" else "${i.files} archivos · ${i.folders} carpetas")
                    }
                    if (single != null && !single.isDirectory) {
                        val h = hashes
                        if (h == null) {
                            TextButton(
                                enabled = !hashing,
                                onClick = {
                                    hashing = true
                                    scope.launch {
                                        hashes = withContext(Dispatchers.IO) {
                                            listOf("MD5", "SHA-1", "SHA-256").map { alg ->
                                                alg to runCatching { FileRepo.hash(single.file, alg) }.getOrDefault("Error")
                                            }
                                        }
                                        hashing = false
                                    }
                                },
                            ) { Text(if (hashing) "Calculando…" else "Calcular MD5 / SHA-1 / SHA-256") }
                        } else {
                            h.forEach { (k, v) -> PropRow(k, v) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun PropRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PasswordDialog(title:String,dismiss:()->Unit,submit:(String)->Unit){
    var password by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Column{OutlinedTextField(password,{password=it},label={Text("Contraseña")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation());Text("Se crea una copia. El original se conserva.")}},confirmButton={TextButton(onClick={submit(password)},enabled=password.isNotEmpty()){Text("Continuar")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})
}

@Composable
private fun CompressDialog(items:List<FileItem>,dismiss:()->Unit,submit:(String,String)->Unit){
    var name by remember{mutableStateOf((items.firstOrNull()?.name?.substringBeforeLast('.') ?: "Archivos")+".zip")}
    var password by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=dismiss,title={Text("Crear ZIP")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(name,{name=it},label={Text("Nombre: .zip, .7z, .tar o .tar.gz")});OutlinedTextField(password,{password=it},label={Text("Contraseña opcional (AES)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())}},confirmButton={TextButton(onClick={submit(name,password)},enabled=name.isNotBlank()){Text("Comprimir")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})
}

@Composable
private fun ApkInfoDialog(item:FileItem,dismiss:()->Unit){
    val ctx=LocalContext.current
    val info by produceState<String?>(null,item.path){value=withContext(Dispatchers.IO){runCatching{
        @Suppress("DEPRECATION") val packageInfo=ctx.packageManager.getPackageArchiveInfo(item.path,android.content.pm.PackageManager.GET_PERMISSIONS) ?: throw IllegalArgumentException("No es un APK compatible")
        val application=packageInfo.applicationInfo
        application?.sourceDir=item.path;application?.publicSourceDir=item.path
        "Aplicación: ${application?.loadLabel(ctx.packageManager)}\nPaquete: ${packageInfo.packageName}\nVersión: ${packageInfo.versionName}\n\nPermisos solicitados:\n"+(packageInfo.requestedPermissions?.joinToString("\n") ?: "Ninguno")
    }.getOrElse{it.message ?: "No se pudo inspeccionar el APK"}}}
    AlertDialog(onDismissRequest=dismiss,title={Text(item.name)},text={SelectionContainer{Text(info ?: "Leyendo…",Modifier.verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick=dismiss){Text("Cerrar")}})
}
