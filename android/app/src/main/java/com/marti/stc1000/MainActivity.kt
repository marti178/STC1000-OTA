package com.marti.stc1000

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marti.stc1000.data.CsvExporter
import com.marti.stc1000.ui.ChartsScreen
import com.marti.stc1000.ui.DeviceScreen
import com.marti.stc1000.ui.HomeScreen
import com.marti.stc1000.ui.MainViewModel
import com.marti.stc1000.ui.SettingsScreen
import com.marti.stc1000.ui.Stc1000Theme

private enum class Tab(val label: String) {
    HOME("Inicio"),
    CHARTS("Gráficos"),
    DEVICE("Equipo"),
    SETTINGS("Ajustes"),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Para abrir directo una pestaña (útil al probar): am start ... --es tab GRAFICOS
        val requestedTab = intent?.getStringExtra("tab")?.let { name ->
            Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.label.equals(name, ignoreCase = true) }
        }
        val settingsSubTab = intent?.getIntExtra("subtab", 0) ?: 0

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val test by vm.test.collectAsStateWithLifecycle()
            val message by vm.message.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            val context = LocalContext.current

            Stc1000Theme(
                mode = state.prefs.theme,
                dynamicColor = state.prefs.dynamicColor,
                unit = state.prefs.unit,
            ) {
                // Primera vez (sin servidor configurado) se abre directo en Ajustes.
                var tab by rememberSaveable { mutableStateOf(requestedTab) }
                val current = tab ?: if (!state.loaded || state.settings.isConfigured) Tab.HOME else Tab.SETTINGS

                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeMessage()
                    }
                }

                fun export() {
                    if (state.allSamples.isEmpty()) {
                        vm.toast("Todavía no hay lecturas para exportar.")
                    } else {
                        runCatching { context.startActivity(CsvExporter.shareIntent(context, state.allSamples)) }
                            .onFailure { vm.toast("No se pudo abrir el menú de compartir.") }
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = current == t,
                                    onClick = { tab = t },
                                    icon = {
                                        Icon(
                                            when (t) {
                                                Tab.HOME -> Icons.Default.Home
                                                Tab.CHARTS -> Icons.Default.Menu
                                                Tab.DEVICE -> Icons.Default.Info
                                                Tab.SETTINGS -> Icons.Default.Settings
                                            },
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val mod = Modifier.padding(padding)
                    when (current) {
                        Tab.HOME -> HomeScreen(state, vm::selectPeriod, vm::retry, mod)
                        Tab.CHARTS -> ChartsScreen(state, vm::selectPeriod, ::export, mod)
                        Tab.DEVICE -> DeviceScreen(state, vm::sendCommand, mod)
                        Tab.SETTINGS -> SettingsScreen(
                            initial = state.settings,
                            loaded = state.loaded,
                            test = test,
                            prefs = state.prefs,
                            hasData = state.allSamples.isNotEmpty(),
                            onSave = vm::saveSettings,
                            onTest = vm::testConnection,
                            onFormChanged = vm::clearTest,
                            onPrefs = vm::updatePrefs,
                            onExport = ::export,
                            modifier = mod,
                            startTab = settingsSubTab,
                        )
                    }
                }
            }
        }
    }
}
