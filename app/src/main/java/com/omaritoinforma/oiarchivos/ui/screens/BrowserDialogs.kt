package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
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
import com.omaritoinforma.oiarchivos.data.tr

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
        is BrowserDialog.Encrypt ->
            PasswordDialog(
                if (dialog.decrypt) tr("Descifrar archivo") else tr("Cifrar archivo"), dismiss) { password
                    ->
                    vm.encrypt(dialog.item, password, dialog.decrypt)
                    dismiss()
                }
        is BrowserDialog.InspectApk -> ApkInfoDialog(dialog.item, dismiss)

        BrowserDialog.CreateMenu ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(tr("Crear nuevo")) },
                text = {
                    Column {
                        DialogOption(Icons.Filled.CreateNewFolder, tr("Carpeta")) {
                            setDialog(BrowserDialog.Create(folder = true))
                        }
                        DialogOption(Icons.Filled.NoteAdd, tr("Archivo vacío")) {
                            setDialog(BrowserDialog.Create(folder = false))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancelar")) } },
            )

        is BrowserDialog.Create ->
            NameDialog(
                title = if (dialog.folder) tr("Nueva carpeta") else tr("Nuevo archivo"),
                initial = "",
                confirm = tr("Crear"),
                onDismiss = dismiss,
            ) {
                vm.create(it, dialog.folder)
                dismiss()
            }

        is BrowserDialog.Rename ->
            NameDialog(
                title = tr("Renombrar"),
                initial = dialog.item.name,
                confirm = tr("Renombrar"),
                selectBase = !dialog.item.isDirectory,
                onDismiss = dismiss,
            ) {
                vm.rename(dialog.item, it)
                dismiss()
            }

        is BrowserDialog.BatchRename ->
            BatchRenameDialog(dialog.items, dismiss) { rules ->
                vm.batchRename(dialog.items, rules)
                dismiss()
            }

        is BrowserDialog.Delete -> {
            var toTrash by remember { mutableStateOf(vm.useTrash) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(tr("Eliminar")) },
                text = {
                    Column {
                        Text(
                            if (dialog.items.size == 1) tr("¿Eliminar «{0}»?", dialog.items[0].name)
                            else tr("¿Eliminar {0} elementos?", dialog.items.size),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { toTrash = !toTrash },
                        ) {
                            Checkbox(checked = toTrash, onCheckedChange = { toTrash = it })
                            Text(tr("Mover a la papelera (se puede restaurar)"))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.delete(dialog.items, toTrash)
                            dismiss()
                        }) {
                            Text(tr("Eliminar"), color = MaterialTheme.colorScheme.error)
                        }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancelar")) } },
            )
        }

        is BrowserDialog.Properties -> PropertiesDialog(dialog.items, dismiss)

        is BrowserDialog.Compress ->
            CompressDialog(dialog.items, vm.compressionLevel.value, dismiss) { name, password, level ->
                vm.compress(dialog.items, name, password, level)
                dismiss()
            }

        is BrowserDialog.OpenArchive ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(dialog.item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        DialogOption(
                            Icons.Filled.Unarchive, tr("Extraer aquí (en una carpeta nueva)")) {
                                vm.extract(dialog.item)
                                dismiss()
                            }
                        DialogOption(Icons.Filled.OpenInNew, tr("Abrir con otra app")) {
                            Opener.open(ctx, dialog.item.file, chooser = true)
                            dismiss()
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancelar")) } },
            )

        is BrowserDialog.OpenApk ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(dialog.item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        DialogOption(Icons.Filled.Android, tr("Instalar")) {
                            Opener.installApk(ctx, dialog.item.file)
                            dismiss()
                        }
                        DialogOption(Icons.Filled.Unarchive, tr("Extraer contenido")) {
                            vm.extract(dialog.item)
                            dismiss()
                        }
                        DialogOption(Icons.Filled.OpenInNew, tr("Abrir con otra app")) {
                            Opener.open(ctx, dialog.item.file, chooser = true)
                            dismiss()
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancelar")) } },
            )

        BrowserDialog.Sort -> {
            var sel by remember { mutableStateOf(vm.sortBy) }
            var asc by remember { mutableStateOf(vm.ascending) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(tr("Ordenar por")) },
                text = {
                    Column {
                        SortBy.entries.forEach { s -> RadioRow(s.label, sel == s) { sel = s } }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        RadioRow(tr("Ascendente (A→Z, antiguo→nuevo)"), asc) { asc = true }
                        RadioRow(tr("Descendente (Z→A, nuevo→antiguo)"), !asc) { asc = false }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.setSort(sel, asc)
                            dismiss()
                        }) {
                            Text(tr("Aplicar"))
                        }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancelar")) } },
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
    var value by remember {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, baseLen)))
    }
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
            TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
private fun BatchRenameDialog(
    items: List<FileItem>,
    onDismiss: () -> Unit,
    onConfirm: (RenameRules) -> Unit
) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var numberFrom by remember { mutableStateOf("") }
    var ext by remember { mutableStateOf("") }
    val rules =
        RenameRules(
            find, replace, prefix, suffix, numberFrom.toIntOrNull(), ext.trim().ifBlank { null })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Renombrar {0} elementos", items.size)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        find, { find = it }, label = { Text(tr("Buscar texto")) }, singleLine = true)
                    OutlinedTextField(
                        replace,
                        { replace = it },
                        label = { Text(tr("Reemplazar por")) },
                        singleLine = true)
                    OutlinedTextField(
                        prefix,
                        { prefix = it },
                        label = { Text(tr("Agregar al inicio")) },
                        singleLine = true)
                    OutlinedTextField(
                        suffix,
                        { suffix = it },
                        label = { Text(tr("Agregar al final")) },
                        singleLine = true)
                    OutlinedTextField(
                        numberFrom,
                        { v -> numberFrom = v.filter { it.isDigit() } },
                        label = { Text(tr("Numerar desde (vacío = no)")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        ext,
                        { ext = it },
                        label = { Text(tr("Nueva extensión (vacío = igual)")) },
                        singleLine = true)
                    Text(
                        tr("Vista previa"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp))
                    items.take(4).forEachIndexed { i, it ->
                        Text(
                            "${it.name}  →  ${rules.apply(it.name, i, it.isDirectory)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (items.size > 4)
                        Text(tr("…y {0} más", items.size - 4), style = MaterialTheme.typography.bodySmall)
                }
        },
        confirmButton = { TextButton(onClick = { onConfirm(rules) }) { Text(tr("Renombrar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
private fun PropertiesDialog(items: List<FileItem>, onDismiss: () -> Unit) {
    var info by remember(items) { mutableStateOf<PropInfo?>(null) }
    LaunchedEffect(items) {
        info = withContext(Dispatchers.IO) { runCatching { FileRepo.props(items) }.getOrNull() }
    }
    var hashes by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var hashing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val single = items.singleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                single?.name ?: tr("{0} elementos", items.size),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        },
        text = {
            SelectionContainer {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (single != null) {
                            PropRow(tr("Ruta"), single.path)
                            PropRow(
                                tr("Tipo"),
                                if (single.isDirectory) tr("Carpeta")
                                else
                                    Opener.mime(single.file).takeIf { it != "*/*" }
                                        ?: tr("Archivo .{0}", single.extension),
                            )
                            PropRow(tr("Modificado"), formatDate(single.lastModified))
                            PropRow(
                                tr("Permisos"),
                                buildString {
                                    append(if (single.file.canRead()) tr("Lectura") else tr("Sin lectura"))
                                    append(" · ")
                                    append(
                                        if (single.file.canWrite()) tr("Escritura") else tr("Solo lectura"))
                                    if (single.isHidden) append(tr(" · Oculto"))
                                },
                            )
                        } else {
                            PropRow(tr("Ubicación"), items.firstOrNull()?.file?.parent ?: "")
                        }
                        val i = info
                        PropRow(
                            tr("Tamaño"),
                            if (i == null) tr("Calculando…")
                            else tr("{0} ({1} bytes)", formatSize(i.bytes), "%,d".format(i.bytes)),
                        )
                        if (single == null || single.isDirectory) {
                            PropRow(
                                tr("Contenido"),
                                if (i == null) tr("Calculando…")
                                else tr("{0} archivos · {1} carpetas", i.files, i.folders))
                        }
                        if (single != null && !single.isDirectory) {
                            val h = hashes
                            if (h == null) {
                                TextButton(
                                    enabled = !hashing,
                                    onClick = {
                                        hashing = true
                                        scope.launch {
                                            hashes =
                                                withContext(Dispatchers.IO) {
                                                    listOf("MD5", "SHA-1", "SHA-256").map { alg ->
                                                        alg to
                                                            runCatching {
                                                                    FileRepo.hash(single.file, alg)
                                                                }
                                                                .getOrDefault(tr("Error"))
                                                    }
                                                }
                                            hashing = false
                                        }
                                    },
                                ) {
                                    Text(
                                        if (hashing) tr("Calculando…")
                                        else tr("Calcular MD5 / SHA-1 / SHA-256"))
                                }
                            } else {
                                h.forEach { (k, v) -> PropRow(k, v) }
                            }
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
}

@Composable
private fun PropRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PasswordDialog(title: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(tr("Contraseña")) },
                    visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text(tr("Se crea una copia. El original se conserva."))
            }
        },
        confirmButton = {
            TextButton(onClick = { submit(password) }, enabled = password.isNotEmpty()) {
                Text(tr("Continuar"))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancelar")) } })
}

@Composable
private fun CompressDialog(
    items: List<FileItem>,
    initialLevel: com.omaritoinforma.oiarchivos.data.CompressionLevel,
    dismiss: () -> Unit,
    submit: (String, String, com.omaritoinforma.oiarchivos.data.CompressionLevel) -> Unit
) {
    var name by remember {
        mutableStateOf((items.firstOrNull()?.name?.substringBeforeLast('.') ?: tr("Archivos")) + ".zip")
    }
    var password by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(initialLevel) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(tr("Crear ZIP")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text(tr("Nombre: .zip, .7z, .tar o .tar.gz")) })
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(tr("Contraseña opcional (AES)")) },
                    visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text(tr("Nivel de compresión"), style = MaterialTheme.typography.labelMedium)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.omaritoinforma.oiarchivos.data.CompressionLevel.entries.forEach { option ->
                        FilterChip(
                            level == option,
                            onClick = { level = option },
                            label = { Text(option.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit(name, password, level) }, enabled = name.isNotBlank()) {
                Text(tr("Comprimir"))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancelar")) } })
}

@Composable
private fun ApkInfoDialog(item: FileItem, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    var info by remember(item.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.path) {
        info =
            withContext(Dispatchers.IO) {
                runCatching {
                        @Suppress("DEPRECATION")
                        val packageInfo =
                            ctx.packageManager.getPackageArchiveInfo(
                                item.path, android.content.pm.PackageManager.GET_PERMISSIONS)
                                ?: throw IllegalArgumentException(tr("No es un APK compatible"))
                        val application = packageInfo.applicationInfo
                        application?.sourceDir = item.path
                        application?.publicSourceDir = item.path
                        tr("Aplicación: {0}\nPaquete: {1}\nVersión: {2}\n\nPermisos solicitados:\n", application?.loadLabel(ctx.packageManager), packageInfo.packageName, packageInfo.versionName) +
                            (packageInfo.requestedPermissions?.joinToString("\n") ?: tr("Ninguno"))
                    }
                    .getOrElse { it.message ?: tr("No se pudo inspeccionar el APK") }
            }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(item.name) },
        text = {
            SelectionContainer {
                Text(info ?: tr("Leyendo…"), Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Cerrar")) } })
}
