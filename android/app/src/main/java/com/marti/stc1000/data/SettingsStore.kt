package com.marti.stc1000.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ConnSettings(
    val host: String = "",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val prefix: String = "stc1000",
    val tls: Boolean = false,
) {
    val isConfigured: Boolean get() = host.isNotBlank()

    fun topic(name: String): String = "${prefix.trim('/')}/$name"
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
        ConnSettings(
            host = p[Keys.host] ?: defaults.host,
            port = p[Keys.port] ?: defaults.port,
            username = p[Keys.user] ?: defaults.username,
            password = p[Keys.passwordEnc]?.let { SecretBox.decrypt(it) } ?: "",
            prefix = p[Keys.prefix] ?: defaults.prefix,
            tls = p[Keys.tls] ?: defaults.tls,
        )
    }

    suspend fun current(): ConnSettings = flow.first()

    suspend fun save(s: ConnSettings) {
        val enc = if (s.password.isEmpty()) null else SecretBox.encrypt(s.password)
        context.dataStore.edit { p ->
            p[Keys.host] = s.host.trim()
            p[Keys.port] = s.port
            p[Keys.user] = s.username.trim()
            if (enc == null) p.remove(Keys.passwordEnc) else p[Keys.passwordEnc] = enc
            p[Keys.prefix] = s.prefix.trim().trim('/').ifEmpty { "stc1000" }
            p[Keys.tls] = s.tls
        }
    }
}
