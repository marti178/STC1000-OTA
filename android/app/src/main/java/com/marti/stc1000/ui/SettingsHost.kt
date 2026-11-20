package com.marti.stc1000.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.marti.stc1000.BuildConfig
import com.marti.stc1000.data.AppPrefs
import com.marti.stc1000.data.ConnSettings
import com.marti.stc1000.domain.TempUnit
import com.marti.stc1000.domain.ThemeMode
import java.util.Locale

private val TABS = listOf("Conexión", "Preferencias", "Acerca de")

/** Ajustes con tres submenús: Conexión, Preferencias y Acerca de. */
@Composable
fun SettingsScreen(
    initial: ConnSettings,
    loaded: Boolean,
    test: TestState,
    prefs: AppPrefs,
    hasData: Boolean,
    onSave: (ConnSettings) -> Unit,
    onTest: (ConnSettings) -> Unit,
    onFormChanged: () -> Unit,
    onPrefs: ((AppPrefs) -> AppPrefs) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    startTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Ajustes", null, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
        SettingsTabs(tab) { tab = it }
        when (tab) {
            0 -> ConnectionTab(initial, loaded, test, onSave, onTest, onFormChanged, Modifier.weight(1f))
            1 -> PreferencesTab(prefs, hasData, onPrefs, onExport, Modifier.weight(1f))
            else -> AboutTab(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTabs(selected: Int, onSelect: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected, containerColor = MaterialTheme.colorScheme.background) {
        TABS.forEachIndexed { i, t ->
            Tab(selected = selected == i, onClick = { onSelect(i) }, text = { Text(t, maxLines = 1) })
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PreferencesTab(
    prefs: AppPrefs,
    hasData: Boolean,
    onPrefs: ((AppPrefs) -> AppPrefs) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Apariencia", style = MaterialTheme.typography.titleMedium)
                Text("Tema", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = prefs.theme == m,
                            onClick = { onPrefs { it.copy(theme = m) } },
                            label = { Text(m.label) },
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        "Colores del sistema",
                        "Usa los colores de tu fondo de pantalla (Android 12 o más).",
                        prefs.dynamicColor,
                    ) { v -> onPrefs { it.copy(dynamicColor = v) } }
                }
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Unidad de temperatura", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TempUnit.entries.forEach { u ->
                        FilterChip(
                            selected = prefs.unit == u,
                            onClick = { onPrefs { it.copy(unit = u) } },
                            label = { Text("${u.label} (${u.symbol})") },
                        )
                    }
                }
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gráfico", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Curva suave", "Une los puntos con una curva en vez de rectas.", prefs.smooth) { v ->
                    onPrefs { it.copy(smooth = v) }
                }
                SwitchRow("Línea de promedio", "Muestra el promedio del período.", prefs.showAverage) { v ->
                    onPrefs { it.copy(showAverage = v) }
                }
                SwitchRow("Marcas de mínima y máxima", "Señala los puntos más bajo y más alto.", prefs.showMinMax) { v ->
                    onPrefs { it.copy(showMinMax = v) }
                }
            }
        }

        RangeCard(prefs, onPrefs)

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Datos", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Exporta las lecturas que la app tiene ahora (hasta 30 días, bajadas del server) a un archivo CSV para abrirlo en Excel o compartirlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onExport, enabled = hasData, modifier = Modifier.fillMaxWidth()) {
                    Text("Exportar lecturas a CSV")
                }
            }
        }
    }
}

/** Rango de temperatura deseado: pinta la banda en el gráfico y avisa cuando se sale. */
@Composable
private fun RangeCard(prefs: AppPrefs, onPrefs: ((AppPrefs) -> AppPrefs) -> Unit) {
    val unit = prefs.unit
    fun show(c: Double) = String.format(Locale.US, "%.1f", unit.convert(c))
    var minText by remember(unit) { mutableStateOf(show(prefs.rangeMin)) }
    var maxText by remember(unit) { mutableStateOf(show(prefs.rangeMax)) }

    val lo = minText.replace(',', '.').toDoubleOrNull()
    val hi = maxText.replace(',', '.').toDoubleOrNull()
    val valid = lo != null && hi != null && lo < hi

    fun apply(newMin: String, newMax: String) {
        val a = newMin.replace(',', '.').toDoubleOrNull()
        val b = newMax.replace(',', '.').toDoubleOrNull()
        if (a != null && b != null && a < b) {
            onPrefs { it.copy(rangeMin = unit.toCelsius(a), rangeMax = unit.toCelsius(b)) }
        }
    }

    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Rango deseado", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                "Avisar si me salgo del rango",
                "Marca el gráfico y muestra un aviso en Inicio mientras la app está abierta.",
                prefs.rangeEnabled,
            ) { v -> onPrefs { it.copy(rangeEnabled = v) } }
            if (prefs.rangeEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { v ->
                            minText = v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(6)
                            apply(minText, maxText)
                        },
                        label = { Text("Mínima (${unit.symbol})") },
                        singleLine = true,
                        isError = !valid,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { v ->
                            maxText = v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(6)
                            apply(minText, maxText)
                        },
                        label = { Text("Máxima (${unit.symbol})") },
                        singleLine = true,
                        isError = !valid,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!valid) {
                    Text(
                        "La mínima tiene que ser menor que la máxima.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutTab(modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("STC-1000", style = MaterialTheme.typography.titleLarge)
                Text("Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Muestra la temperatura de un termostato STC-1000 que un ESP32 publica por MQTT.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cómo funciona", style = MaterialTheme.typography.titleMedium)
                Bullet("La app se conecta al broker MQTT de tu casa mientras está abierta.")
                Bullet("No guarda lecturas en el celular: el historial lo guarda el server y se baja cada vez que conectás.")
                Bullet("La contraseña se guarda cifrada con el Keystore de Android.")
                Bullet("Si el equipo, el server o internet fallan, el Registro de conexión (pestaña Equipo) dice qué pasó.")
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
