@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.CharBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(vm: MainViewModel, path: String, documentOrigin: DocumentOrigin? = null) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    var origin by remember(path, documentOrigin) { mutableStateOf(documentOrigin) }
    var recovery by remember(path) { mutableStateOf<String?>(null) }
    var saveError by remember(path) { mutableStateOf<String?>(null) }
    var value by remember(path) { mutableStateOf<TextFieldValue?>(null) }
    var original by remember(path) { mutableStateOf("") }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var reloadConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var encoding by remember(path) { mutableStateOf("UTF-8") }
    var modifiedTime by remember(path) { mutableLongStateOf(0) }
    var localFingerprint by remember(path) { mutableStateOf<DocumentFingerprint?>(null) }
    var font by remember { mutableIntStateOf(14) }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var search by remember { mutableStateOf(false) }
    var numbers by remember { mutableStateOf(true) }
    var charsetMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val modified = value?.text?.let { it != original } ?: false
    var copySnapshot by remember { mutableStateOf<Pair<String, String>?>(null) }
    val createCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(documentOrigin?.mimeType ?: "text/plain")) { uri ->
        val snapshot = copySnapshot
        copySnapshot = null
        if (uri != null && snapshot != null) {
            saving = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val sourceUri = origin?.documentUri?.let(android.net.Uri::parse)
                        if (sourceUri != null && sourceUri.authority == uri.authority &&
                            android.provider.DocumentsContract.getDocumentId(sourceUri) ==
                            android.provider.DocumentsContract.getDocumentId(uri))
                            throw IllegalStateException("Elige un documento nuevo; el original se conserva")
                        val encoded = Charset.forName(snapshot.second).newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(snapshot.first))
                        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
                        val store = AndroidDocumentStore(ctx)
                        store.adoptEmptyCreatedDocument(uri.toString())
                        store.openWriteNew(uri.toString()).use { output ->
                            for (start in bytes.indices step 131072) {
                                currentCoroutineContext().ensureActive()
                                output.write(bytes, start, minOf(131072, bytes.size - start))
                            }
                            output.flush()
                        }
                        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        val readBack = DocumentTransactions.fingerprint(store, uri.toString())
                        if (readBack.size != bytes.size.toLong() || readBack.sha256 != expected)
                            throw IllegalStateException("El proveedor no confirmó la copia completa. Tus cambios siguen en el editor.")
                    }
                }.onSuccess { vm.toast("Copia guardada y verificada. El original se conserva."); saveError = null }
                 .onFailure { saveError = it.message ?: "No se pudo guardar la copia" }
                saving = false
            }
        }
    }
    LaunchedEffect(path, encoding) {
        error = null
        value = null
        withContext(Dispatchers.IO) {
                runCatching {
                    if (file.length() > 8L * 1024 * 1024)
                        throw IllegalStateException("El editor permite archivos de hasta 8 MB")
                    val bytes = ByteArrayOutputStream().apply {
                        file.inputStream().use { input ->
                            val buffer = ByteArray(131072)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (size().toLong() + count > 8L * 1024 * 1024)
                                    throw IllegalStateException("El editor permite archivos de hasta 8 MB")
                                write(buffer, 0, count)
                            }
                        }
                    }.toByteArray()
                    val fingerprint = DocumentTransactions.fingerprint(LocalDocumentStore(), file.absolutePath, 8L * 1024 * 1024)
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    if (fingerprint.size != bytes.size.toLong() || fingerprint.sha256 != digest)
                        throw IllegalStateException("El archivo cambió al abrirlo; vuelve a intentarlo")
                    val text = try {
                        Charset.forName(encoding).newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (failure: java.nio.charset.CharacterCodingException) {
                        throw IllegalStateException("El archivo no es texto válido con $encoding. Prueba otra codificación.", failure)
                    }
                    text to fingerprint
                }
            }
            .onSuccess {
                value = TextFieldValue(it.first)
                original = it.first
                localFingerprint = it.second
                modifiedTime = it.second.modified
            }
            .onFailure { error = it.message }
    }
    fun save(then: () -> Unit = {}) {
        val snapshot = value?.text ?: return
        val source = origin
        saving = true
        saveError = null
        scope.launch {
            withContext(Dispatchers.IO) {
                    runCatching {
                        if (source != null) {
                            val store = AndroidDocumentStore(ctx)
                            val originalId = store.requireSafeReplacement(source.parentUri, source.documentUri)
                            val saved = DocumentTransactions.saveText(store, source.parentUri,
                                originalId, source.fingerprint, snapshot, source.mimeType,
                                Charset.forName(encoding))
                            // The preview remains a convenience; a failure here does not undo a verified save.
                            runCatching { SafeFiles.writeAtomic(file) { it.writeText(snapshot, Charset.forName(encoding)) } }
                            return@runCatching saved
                        }
                        if (file.lastModified() != modifiedTime)
                            throw IllegalStateException(
                                "El archivo cambió fuera del editor. Vuelve a abrirlo antes de guardar.")
                        val current = DocumentTransactions.fingerprint(LocalDocumentStore(), file.absolutePath, 8L * 1024 * 1024)
                        if (current.size != localFingerprint?.size || current.sha256 != localFingerprint?.sha256)
                            throw IllegalStateException("El archivo cambió fuera del editor. Vuelve a abrirlo antes de guardar.")
                        val encoded = Charset.forName(encoding).newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(snapshot))
                        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
                        SafeFiles.writeAtomic(file) {
                            it.writeBytes(bytes)
                        }
                        localFingerprint = DocumentTransactions.fingerprint(LocalDocumentStore(), file.absolutePath)
                        null
                    }
                }
                .onSuccess {
                    if (it != null && source != null) {
                        origin = source.copy(documentUri = it.id, fingerprint = it.fingerprint)
                        recovery = "Respaldo anterior: ${android.net.Uri.parse(it.backupId).lastPathSegment ?: it.backupId}"
                    }
                    modifiedTime = file.lastModified()
                    original = snapshot
                    vm.toast(if (source != null) "Guardado en el documento original y verificado. Respaldo conservado." else "Guardado")
                    then()
                }
                .onFailure {
                    saveError = it.message ?: "No se pudo guardar"
                    if (it is DocumentSaveException) {
                        it.backupId?.let { backup -> recovery = "Original conservado en: $backup" }
                        if (it.restoredId != null && source != null) {
                            val fingerprint = withContext(Dispatchers.IO) { runCatching {
                                DocumentTransactions.fingerprint(AndroidDocumentStore(ctx), it.restoredId)
                            }.getOrNull() }
                            origin = source.copy(documentUri = it.restoredId, fingerprint = fingerprint ?: source.fingerprint)
                        }
                    }
                }
            saving = false
        }
    }
    fun next() {
        val current = value ?: return
        if (find.isEmpty()) return
        val pos =
            current.text.indexOf(find, current.selection.end, ignoreCase = true).let {
                if (it < 0) current.text.indexOf(find, ignoreCase = true) else it
            }
        if (pos < 0) vm.toast("No se encontró el texto")
        else value = current.copy(selection = TextRange(pos, pos + find.length))
    }
    fun reloadOriginal() {
        val source = origin ?: return
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val store = AndroidDocumentStore(ctx)
                    val before = DocumentTransactions.fingerprint(store, source.documentUri, 8L * 1024 * 1024)
                    if (before.size > 8L * 1024 * 1024) throw IllegalStateException("El editor permite archivos de hasta 8 MB")
                    val bytes = ByteArrayOutputStream().apply {
                        store.openRead(source.documentUri).use { input ->
                            val buffer = ByteArray(131072)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (size().toLong() + count > 8L * 1024 * 1024)
                                    throw IllegalStateException("El editor permite archivos de hasta 8 MB")
                                write(buffer, 0, count)
                            }
                        }
                    }.toByteArray()
                    val after = DocumentTransactions.fingerprint(store, source.documentUri, 8L * 1024 * 1024)
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    if (before.sha256 != after.sha256 || after.sha256 != digest || after.size != bytes.size.toLong())
                        throw IllegalStateException("El documento cambió al recargar; tus cambios siguen en el editor")
                    val text = Charset.forName(encoding).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                    SafeFiles.writeAtomic(file) { it.writeBytes(bytes) }
                    text to after
                }
            }.onSuccess {
                value = TextFieldValue(it.first)
                original = it.first
                origin = source.copy(fingerprint = it.second)
                modifiedTime = file.lastModified()
                saveError = null
            }.onFailure { saveError = it.message ?: "No se pudo recargar; tus cambios siguen en el editor" }
            saving = false
        }
    }
    BackHandler(modified && !saving) { confirm = true }
    BackHandler(saving) { vm.toast("Espera a que termine el guardado") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((origin?.name ?: file.name) + if (modified) " •" else "") },
                navigationIcon = {
                    IconButton(onClick = { if (modified) confirm = true else vm.back() }, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        value?.text?.let { copySnapshot = it to encoding; createCopy.launch(origin?.name ?: file.name) }
                    }, enabled = value != null && !saving) { Text("Guardar copia") }
                    TextButton(onClick = { search = !search }) { Text("Buscar") }
                    IconButton(onClick = { save() }, enabled = modified && !saving) {
                        Icon(Icons.Filled.Save, "Guardar")
                    }
                })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                origin?.let { Text("Documento original: ${it.name}", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall) }
                saveError?.let { Text(it, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error) }
                if (saveError != null && origin != null)
                    TextButton(onClick = { reloadConfirm = true }, enabled = !saving) { Text("Recargar original") }
                recovery?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(
                            onClick = { charsetMenu = true }, enabled = !modified && !saving) {
                                Text(encoding)
                            }
                        DropdownMenu(charsetMenu, { charsetMenu = false }) {
                            listOf("UTF-8", "UTF-16", "ISO-8859-1", "windows-1252").forEach { cs ->
                                DropdownMenuItem(
                                    text = { Text(cs) },
                                    onClick = {
                                        encoding = cs
                                        charsetMenu = false
                                    })
                            }
                        }
                    }
                    TextButton(onClick = { font = (font - 1).coerceAtLeast(10) }) { Text("A−") }
                    TextButton(onClick = { font = (font + 1).coerceAtMost(28) }) { Text("A+") }
                    FilterChip(
                        numbers, onClick = { numbers = !numbers }, label = { Text("Líneas") })
                }
                if (search) {
                    OutlinedTextField(
                        find,
                        { find = it },
                        label = { Text("Buscar") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    OutlinedTextField(
                        replace,
                        { replace = it },
                        label = { Text("Reemplazar con") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    Row {
                        TextButton(onClick = { next() }) { Text("Siguiente") }
                        TextButton(
                            onClick = {
                                val current = value
                                if (current != null && find.isNotEmpty()) {
                                    val selected =
                                        current.text.substring(
                                            current.selection.min, current.selection.max)
                                    if (selected.equals(find, true)) {
                                        val new =
                                            current.text.replaceRange(
                                                current.selection.min,
                                                current.selection.max,
                                                replace)
                                        value =
                                            TextFieldValue(
                                                new,
                                                TextRange(current.selection.min + replace.length))
                                    } else next()
                                }
                            }) {
                                Text("Reemplazar")
                            }
                        TextButton(
                            onClick = {
                                val current = value
                                if (current != null && find.isNotEmpty())
                                    value =
                                        TextFieldValue(
                                            current.text.replace(find, replace, ignoreCase = true))
                            }) {
                                Text("Todos")
                            }
                    }
                }
                when {
                    error != null ->
                        Text(
                            error!!,
                            Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.error)
                    value == null -> CircularProgressIndicator()
                    else -> {
                        val current = value!!
                        val lineCount = current.text.count { it == '\n' } + 1
                        val style =
                            TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = font.sp,
                                lineHeight = (font + 6).sp,
                                color = MaterialTheme.colorScheme.onSurface)
                        Row(
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)) {
                                if (numbers)
                                    Text(
                                        (1..lineCount.coerceAtMost(10000)).joinToString("\n"),
                                        style =
                                            style.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant),
                                        modifier = Modifier.padding(end = 12.dp))
                                BasicTextField(
                                    value = current,
                                    onValueChange = { value = it },
                                    enabled = !saving,
                                    textStyle = style,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    visualTransformation =
                                        remember(current.text.length) { CodeColor() },
                                    modifier = Modifier.weight(1f).heightIn(min = 300.dp))
                            }
                        Text(
                            "$lineCount líneas · ${current.text.length} caracteres",
                            Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Cambios sin guardar") },
            text = { Text("¿Guardar antes de salir?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        save { vm.back() }
                    }) {
                        Text("Guardar")
                    }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        original = value?.text.orEmpty()
                        vm.back()
                    }) {
                        Text("Descartar")
                    }
            })
    if (reloadConfirm)
        AlertDialog(onDismissRequest = { reloadConfirm = false },
            title = { Text("Recargar documento original") },
            text = { Text("Los cambios del editor se sustituirán por el contenido actual del documento. Puedes guardar una copia antes de recargar.") },
            confirmButton = { TextButton(onClick = { reloadConfirm = false; reloadOriginal() }) { Text("Recargar") } },
            dismissButton = { TextButton(onClick = { reloadConfirm = false }) { Text("Cancelar") } })
}

private class CodeColor : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 100000) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        Regex(
                "\\b(val|var|fun|class|object|import|package|if|else|when|return|for|while|def|function|const|let|true|false|null)\\b")
            .findAll(text.text)
            .forEach {
                builder.addStyle(
                    SpanStyle(color = Color(0xFFB26CFF)), it.range.first, it.range.last + 1)
            }
        Regex("\"[^\"\\n]*\"").findAll(text.text).forEach {
            builder.addStyle(
                SpanStyle(color = Color(0xFF388E3C)), it.range.first, it.range.last + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
