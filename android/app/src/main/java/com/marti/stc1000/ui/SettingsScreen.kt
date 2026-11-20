package com.marti.stc1000.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.marti.stc1000.data.ConnSettings

/** Teclado sin autocorrector ni mayúsculas automáticas: para hosts, usuarios y prefijos. */
private val RAW_TEXT = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

@Composable
fun ConnectionTab(
    initial: ConnSettings,
    loaded: Boolean,
    test: TestState,
    onSave: (ConnSettings, (Boolean) -> Unit) -> Unit,
    onTest: (ConnSettings) -> Unit,
    onFormChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El formulario sobrevive a rotar la pantalla y a cambiar de pestana. La contrasena no se guarda en el
    // estado de la pantalla (ver formSaver): si hace falta, se vuelve a leer de los ajustes guardados.
    var form by rememberSaveable(stateSaver = formSaver(initial.password)) { mutableStateOf(initial) }
    var portText by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var populated by rememberSaveable { mutableStateOf(false) }
    var passwordTouched by rememberSaveable { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    // Se carga una sola vez cuando llegan los ajustes guardados.
    LaunchedEffect(loaded) {
        if (loaded && !populated) {
            form = initial
            portText = initial.port.toString()
            populated = true
        }
    }

    val port = portText.toIntOrNull()
    val portOk = port != null && port in 1..65535
    val hostOk = form.host.isBlank() || ConnSettings.HOST_REGEX.matches(form.host.trim())
    val valid = form.host.isNotBlank() && hostOk && portOk && form.prefix.isNotBlank()
    // Si no se toco la contrasena, se conserva la guardada (no se borra por error).
    fun current() = form.copy(
        port = port ?: form.port,
        password = if (!passwordTouched && form.password.isEmpty()) initial.password else form.password,
    )

    fun change(block: () -> Unit) {
        block()
        saved = false
        onFormChanged()
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Servidor", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = form.host,
                    onValueChange = { v -> change { form = form.copy(host = v) } },
                    label = { Text("Dirección (nombre o IP)") },
                    supportingText = {
                        Text(
                            if (hostOk) "Ej.: mi-server.tu-red.ts.net, o una IP de la red local"
                            else "Solo letras sin tilde, números, puntos y guiones.",
                        )
                    },
                    singleLine = true,
                    isError = !hostOk,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = RAW_TEXT.copy(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { v -> change { portText = v.filter(Char::isDigit).take(5) } },
                    label = { Text("Puerto") },
                    singleLine = true,
                    isError = !portOk,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Conexión cifrada (TLS)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Necesaria si el server está publicado en internet. Requiere un certificado válido.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.tls,
                        onCheckedChange = { on ->
                            change {
                                form = form.copy(tls = on)
                                if (on && portText == "1883") portText = "8883"
                                if (!on && portText == "8883") portText = "1883"
                            }
                        },
                    )
                }
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Acceso", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { v -> change { form = form.copy(username = v) } },
                    label = { Text("Usuario") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = RAW_TEXT,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { v ->
                        change {
                            passwordTouched = true
                            form = form.copy(password = v)
                        }
                    },
                    isError = initial.passwordLost && !passwordTouched,
                    label = { Text("Contraseña") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Ocultar" else "Ver") }
                    },
                    supportingText = {
                        Text(
                            if (initial.passwordLost && !passwordTouched) {
                                "La contraseña guardada no se pudo leer: escribila de nuevo."
                            } else {
                                "Se guarda cifrada con el Keystore de Android."
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tópicos", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = form.prefix,
                    onValueChange = { v -> change { form = form.copy(prefix = v) } },
                    label = { Text("Prefijo") },
                    supportingText = { Text("Tiene que ser el mismo que usa el firmware (por defecto stc1000).") },
                    keyboardOptions = RAW_TEXT,
                    singleLine = true,
                    isError = form.prefix.isBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        when (test) {
            TestState.Idle -> {}
            TestState.Running -> StatusMessage("Probando conexión…", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            is TestState.Done -> if (test.ok) {
                StatusMessage(test.message, MaterialTheme.status.okContainer, MaterialTheme.colorScheme.onSurface)
            } else {
                StatusMessage(test.message, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onTest(current()) },
                enabled = valid && test != TestState.Running,
                modifier = Modifier.weight(1f),
            ) { Text("Probar conexión") }
            Button(
                onClick = {
                    saved = false
                    onSave(current()) { ok -> saved = ok }
                },
                enabled = valid,
                modifier = Modifier.weight(1f),
            ) { Text("Guardar") }
        }
        if (saved) {
            Text(
                "Guardado. La app conecta sola cada vez que la abrís.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.status.ok,
            )
        }
    }
}

@Composable
private fun StatusMessage(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    androidx.compose.material3.Card(
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Guarda el formulario en el estado de la pantalla SIN la contrasena (nunca va a un Bundle). */
private fun formSaver(passwordFallback: String) = Saver<ConnSettings, Any>(
    save = { listOf(it.host, it.port, it.username, it.prefix, it.tls) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        val v = it as List<Any>
        ConnSettings(
            host = v[0] as String,
            port = v[1] as Int,
            username = v[2] as String,
            prefix = v[3] as String,
            tls = v[4] as Boolean,
            password = passwordFallback,
        )
    },
)
