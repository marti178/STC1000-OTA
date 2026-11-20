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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.marti.stc1000.data.ConnSettings

@Composable
fun SettingsScreen(
    initial: ConnSettings,
    loaded: Boolean,
    test: TestState,
    onSave: (ConnSettings) -> Unit,
    onTest: (ConnSettings) -> Unit,
    onFormChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var form by remember(loaded) { mutableStateOf(initial) }
    var portText by remember(loaded) { mutableStateOf(initial.port.toString()) }
    var showPassword by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val port = portText.toIntOrNull()
    val portOk = port != null && port in 1..65535
    val valid = form.host.isNotBlank() && portOk && form.prefix.isNotBlank()
    fun current() = form.copy(port = port ?: form.port)

    fun change(block: () -> Unit) {
        block()
        saved = false
        onFormChanged()
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Conexión al server MQTT", style = MaterialTheme.typography.titleLarge)
        Text(
            "Para ver los datos fuera de casa, activá Tailscale en el celular y usá como servidor la IP " +
                "de Tailscale del server (100.x.y.z). En casa también sirve la IP de la red local.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = form.host,
            onValueChange = { v -> change { form = form.copy(host = v) } },
            label = { Text("Servidor (IP o nombre)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { v -> change { portText = v.filter(Char::isDigit).take(5) } },
            label = { Text("Puerto") },
            singleLine = true,
            isError = !portOk,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Usar TLS")
                Text(
                    "Conexión cifrada (normalmente puerto 8883). Necesita un certificado válido en el broker.",
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
        OutlinedTextField(
            value = form.username,
            onValueChange = { v -> change { form = form.copy(username = v) } },
            label = { Text("Usuario") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = { v -> change { form = form.copy(password = v) } },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Ocultar" else "Ver") }
            },
            supportingText = { Text("Se guarda cifrada con el Keystore de Android.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.prefix,
            onValueChange = { v -> change { form = form.copy(prefix = v) } },
            label = { Text("Prefijo de tópicos") },
            singleLine = true,
            isError = form.prefix.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )

        when (test) {
            TestState.Idle -> {}
            TestState.Running -> Text("Probando conexión…")
            is TestState.Done -> Text(
                test.message,
                color = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onTest(current()) },
                enabled = valid && test != TestState.Running,
                modifier = Modifier.weight(1f),
            ) { Text("Probar conexión") }
            Button(
                onClick = {
                    onSave(current())
                    saved = true
                },
                enabled = valid,
                modifier = Modifier.weight(1f),
            ) { Text("Guardar") }
        }
        if (saved) {
            Text("Guardado. La app conecta sola cada vez que la abrís.", color = MaterialTheme.colorScheme.primary)
        }
    }
}
