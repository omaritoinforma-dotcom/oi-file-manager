@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.omaritoinforma.oiarchivos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omaritoinforma.oiarchivos.data.EditorText
import com.omaritoinforma.oiarchivos.data.SafeFiles
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(vm: MainViewModel, path: String) {
    val file = remember(path) { File(path) }
    var value by remember(path) { mutableStateOf<TextFieldValue?>(null) }
    var original by remember(path) { mutableStateOf("") }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var encoding by remember(path) { mutableStateOf("UTF-8") }
    var modifiedTime by remember(path) { mutableLongStateOf(0) }
    var font by vm.editorFont
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var search by remember { mutableStateOf(false) }
    var numbers by vm.editorLineNumbers
    var charsetMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val modified = value?.text?.let { it != original } ?: false
    LaunchedEffect(path, encoding) {
        error = null
        withContext(Dispatchers.IO) {
                runCatching {
                    if (file.length() > 8L * 1024 * 1024)
                        throw IllegalStateException("El editor permite archivos de hasta 8 MB")
                    file.readText(Charset.forName(encoding)) to file.lastModified()
                }
            }
            .onSuccess {
                value = TextFieldValue(it.first)
                original = it.first
                modifiedTime = it.second
            }
            .onFailure { error = it.message }
    }
    fun save(then: () -> Unit = {}) {
        val snapshot = value?.text ?: return
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                    runCatching {
                        if (file.lastModified() != modifiedTime)
                            throw IllegalStateException(
                                "El archivo cambió fuera del editor. Vuelve a abrirlo antes de guardar.")
                        SafeFiles.writeAtomic(file) {
                            it.writeText(snapshot, Charset.forName(encoding))
                        }
                        file.lastModified()
                    }
                }
                .onSuccess {
                    modifiedTime = it
                    original = snapshot
                    vm.toast("Guardado")
                    then()
                }
                .onFailure { vm.toast(it.message ?: "No se pudo guardar") }
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
    // Cambios del menú y de la barra de símbolos sobre la selección actual.
    fun change(edit: (String, Int, Int) -> Triple<String, Int, Int>) {
        val current = value ?: return
        val (text, start, end) = edit(current.text, current.selection.start, current.selection.end)
        value = TextFieldValue(text, TextRange(start, end))
    }
    fun type(insert: String) {
        val current = value ?: return
        val (text, cursor) =
            EditorText.insert(current.text, current.selection.start, current.selection.end, insert)
        value = TextFieldValue(text, TextRange(cursor))
    }
    // Guardado automático (opción del editor de ES): al salir se guarda sin preguntar.
    val leave = {
        when {
            !modified -> vm.back()
            vm.editorAutoSave.value -> save { vm.back() }
            else -> confirm = true
        }
    }
    BackHandler(modified) { leave() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name + if (modified) " •" else "") },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                actions = {
                    TextButton(onClick = { search = !search }) { Text("Buscar") }
                    IconButton(onClick = { save() }, enabled = modified && !saving) {
                        Icon(Icons.Filled.Save, "Guardar")
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }, enabled = value != null && !saving) {
                            Icon(Icons.Filled.MoreVert, "Más")
                        }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Convertir a mayúsculas") },
                                onClick = {
                                    moreMenu = false
                                    change { t, a, b -> EditorText.changeCase(t, a, b, upper = true) }
                                })
                            DropdownMenuItem(
                                text = { Text("Convertir a minúsculas") },
                                onClick = {
                                    moreMenu = false
                                    change { t, a, b -> EditorText.changeCase(t, a, b, upper = false) }
                                })
                            DropdownMenuItem(
                                text = { Text("Duplicar línea") },
                                onClick = {
                                    moreMenu = false
                                    change(EditorText::duplicateLines)
                                })
                        }
                    }
                })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
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
                        val wrap = vm.editorWrap.value
                        val highlight =
                            vm.editorHighlight.value &&
                                current.text.length <= EditorText.HIGHLIGHT_LIMIT
                        val whitespace = vm.editorShowWhitespace.value
                        val marker = MaterialTheme.colorScheme.outline
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
                                .then(
                                    if (wrap) Modifier
                                    else Modifier.horizontalScroll(rememberScrollState()))
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
                                    onValueChange = { new ->
                                        val old = value?.text.orEmpty()
                                        val indented =
                                            if (vm.editorAutoIndent.value)
                                                com.omaritoinforma.oiarchivos.data.EditorText.autoIndent(
                                                    old, new.text, new.selection.end)
                                            else null
                                        value =
                                            if (indented == null) new
                                            else TextFieldValue(indented.first, TextRange(indented.second))
                                    },
                                    enabled = !saving,
                                    textStyle = style,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions =
                                        KeyboardOptions(
                                            capitalization =
                                                if (vm.editorAutoCapitalize.value)
                                                    KeyboardCapitalization.Sentences
                                                else KeyboardCapitalization.None),
                                    visualTransformation =
                                        if (highlight || whitespace)
                                            remember(highlight, whitespace, marker) {
                                                EditorLook(highlight, whitespace, marker)
                                            }
                                        else VisualTransformation.None,
                                    modifier =
                                        if (wrap) Modifier.weight(1f).heightIn(min = 300.dp)
                                        else Modifier.widthIn(min = 300.dp).heightIn(min = 300.dp))
                            }
                        if (vm.editorSymbolBar.value)
                            Row(
                                Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 4.dp)) {
                                    TextButton(
                                        onClick = {
                                            type(
                                                EditorText.tab(
                                                    vm.editorSpacesForTab.value, vm.editorTabSize.value))
                                        },
                                        enabled = !saving) {
                                            Text("Tab")
                                        }
                                    EditorText.symbols(vm.editorSymbols.value).forEach { symbol ->
                                        TextButton(
                                            onClick = { type(symbol) },
                                            enabled = !saving,
                                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                                                Text(symbol, fontFamily = FontFamily.Monospace)
                                            }
                                    }
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
}

/**
 * Aspecto del texto sin cambiar sus posiciones: colores del código (resaltado de sintaxis) y, si se
 * pide, los espacios como «·» y los tabuladores como «→» (ES: «Mostrar espacios en blanco»).
 */
private class EditorLook(
    private val highlight: Boolean,
    private val whitespace: Boolean,
    private val marker: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 100000) return TransformedText(text, OffsetMapping.Identity)
        val raw = text.text
        val builder = AnnotatedString.Builder(if (whitespace) EditorText.showWhitespace(raw) else raw)
        if (highlight) {
            Regex(
                    "\\b(val|var|fun|class|object|import|package|if|else|when|return|for|while|def|function|const|let|true|false|null)\\b")
                .findAll(raw)
                .forEach {
                    builder.addStyle(
                        SpanStyle(color = Color(0xFFB26CFF)), it.range.first, it.range.last + 1)
                }
            Regex("\"[^\"\\n]*\"").findAll(raw).forEach {
                builder.addStyle(
                    SpanStyle(color = Color(0xFF388E3C)), it.range.first, it.range.last + 1)
            }
        }
        if (whitespace)
            Regex("[ \\t]+").findAll(raw).forEach {
                builder.addStyle(SpanStyle(color = marker), it.range.first, it.range.last + 1)
            }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
