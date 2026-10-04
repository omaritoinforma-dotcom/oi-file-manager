@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.AppAnalysis
import com.omaritoinforma.oiarchivos.data.AppRisk
import com.omaritoinforma.oiarchivos.data.SensitiveGroup
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Analizador de apps: qué apps piden permisos delicados (ubicación, cámara, SMS…). */
@Composable
fun AppAnalysisScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var includeSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppRisk>?>(null) }
    var group by remember { mutableStateOf<SensitiveGroup?>(null) }
    LaunchedEffect(includeSystem) {
        apps = null
        apps = withContext(Dispatchers.IO) { runCatching { AppAnalysis.scan(ctx, includeSystem) }.getOrDefault(emptyList()) }
    }
    ToolPage("Analizar apps", vm) { pad ->
        val list = apps
        if (list == null) LinearProgressIndicator(Modifier.padding(pad))
        else {
            val counts = AppAnalysis.counts(list)
            val shown = if (group == null) list.filter { it.groups.isNotEmpty() } else list.filter { group in it.groups }
            LazyColumn(Modifier.padding(pad)) {
                item {
                    Text(
                        "Qué apps piden permisos delicados. «Concedido» quiere decir que la app ya puede usarlo; «solicitado», que lo pide pero aún no se le dio. Se cambian en la información de cada app.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    ListItem(
                        headlineContent = { Text("Incluir apps del sistema") },
                        trailingContent = { Switch(includeSystem, { includeSystem = it }) })
                }
                item {
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                group == null,
                                onClick = { group = null },
                                label = { Text("Todos (${list.count { it.groups.isNotEmpty() }})") })
                            counts.forEach { (g, n) ->
                                FilterChip(
                                    group == g,
                                    onClick = { group = if (group == g) null else g },
                                    label = { Text("${g.label} ($n)") })
                            }
                        }
                }
                if (shown.isEmpty())
                    item { Text("Ninguna app pide esto.", Modifier.padding(16.dp)) }
                items(shown, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(describe(app)) },
                        modifier =
                            Modifier.clickable {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${app.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            })
                }
            }
        }
    }
}

private fun describe(app: AppRisk): String = buildString {
    if (app.granted.isNotEmpty()) append("Concedido: ").append(app.granted.joinToString(", ") { it.label })
    if (app.requested.isNotEmpty()) {
        if (isNotEmpty()) append("\n")
        append("Solicitado: ").append(app.requested.joinToString(", ") { it.label })
    }
    append("\n${formatSize(app.apkSize)} · Android ${app.targetSdk}")
}
