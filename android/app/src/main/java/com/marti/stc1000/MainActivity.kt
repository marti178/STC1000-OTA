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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marti.stc1000.ui.DeviceScreen
import com.marti.stc1000.ui.HomeScreen
import com.marti.stc1000.ui.MainViewModel
import com.marti.stc1000.ui.SettingsScreen
import com.marti.stc1000.ui.Stc1000Theme

private enum class Tab(val label: String) { HOME("Inicio"), DEVICE("Equipo"), SETTINGS("Ajustes") }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Stc1000Theme {
                val state by vm.state.collectAsStateWithLifecycle()
                val test by vm.test.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }

                // Primera vez (sin servidor configurado) se abre directo en Ajustes.
                var tab by rememberSaveable { mutableStateOf<Tab?>(null) }
                val current = tab ?: if (!state.loaded || state.settings.isConfigured) Tab.HOME else Tab.SETTINGS

                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeMessage()
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
                        Tab.DEVICE -> DeviceScreen(state, vm::sendCommand, mod)
                        Tab.SETTINGS -> SettingsScreen(
                            initial = state.settings,
                            loaded = state.loaded,
                            test = test,
                            onSave = vm::saveSettings,
                            onTest = vm::testConnection,
                            onFormChanged = vm::clearTest,
                            modifier = mod,
                        )
                    }
                }
            }
        }
    }
}
