package com.marti.stc1000.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ConnSettings(
    val host: String = "",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val prefix: String = "stc1000",
    val tls: Boolean = false,
    /** Habia una contrasena guardada pero no se pudo descifrar (Keystore invalidado, equipo nuevo). */
    val passwordLost: Boolean = false,
) {
    /** Sin la contrasena: un `toString()` automatico la imprimiria en cualquier log. */
    override fun toString(): String =
        "ConnSettings(host=$host, port=$port, username=$username, prefix=$prefix, tls=$tls)"

    val isConfigured: Boolean get() = host.isNotBlank()

    fun topic(name: String): String = "${prefix.trim('/')}/$name"

    companion object {
        /** Nombre DNS o IP: letras sin tilde, números, puntos y guiones. */
        val HOST_REGEX = Regex("^[A-Za-z0-9.\\-]+$")
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Configuración de conexión. La contraseña se guarda cifrada con [SecretBox]. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val user = stringPreferencesKey("user")
        val passwordEnc = stringPreferencesKey("password_enc")
        val prefix = stringPreferencesKey("prefix")
        val tls = booleanPreferencesKey("tls")
    }

    val flow: Flow<ConnSettings> = context.dataStore.data.map { p ->
        val defaults = ConnSettings()
        val enc = p[Keys.passwordEnc]
        val decrypted = enc?.let { SecretBox.decrypt(it) }
        ConnSettings(
            // Una dirección guardada con caracteres inválidos (autocorrector, copiado mal) se descarta:
            // la app queda "sin configurar" y abre Ajustes en vez de reintentar para siempre.
            host = (p[Keys.host] ?: defaults.host).trim().takeIf { ConnSettings.HOST_REGEX.matches(it) } ?: "",
            port = p[Keys.port] ?: defaults.port,
            username = p[Keys.user] ?: defaults.username,
            password = decrypted ?: "",
            passwordLost = enc != null && decrypted == null,
            prefix = p[Keys.prefix] ?: defaults.prefix,
            tls = p[Keys.tls] ?: defaults.tls,
        )
    }.flowOn(Dispatchers.IO) // descifrar toca el Keystore: nada de eso en el hilo de la UI

    suspend fun current(): ConnSettings = flow.first()

    /** Devuelve false si no se pudo cifrar la contrasena (Keystore roto): no se guarda nada. */
    suspend fun save(s: ConnSettings): Boolean {
        val enc = if (s.password.isEmpty()) {
            null
        } else {
            // Si falla, se borra la clave del Keystore y se prueba una vez con una nueva.
            runCatching { SecretBox.encrypt(s.password) }
                .recoverCatching {
                    SecretBox.resetKey()
                    SecretBox.encrypt(s.password)
                }
                .getOrNull() ?: return false
        }
        context.dataStore.edit { p ->
            p[Keys.host] = s.host.trim()
            p[Keys.port] = s.port
            p[Keys.user] = s.username.trim()
            if (enc == null) p.remove(Keys.passwordEnc) else p[Keys.passwordEnc] = enc
            p[Keys.prefix] = s.prefix.trim().trim('/').ifEmpty { "stc1000" }
            p[Keys.tls] = s.tls
        }
        return true
    }
}
