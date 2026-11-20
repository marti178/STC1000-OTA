package com.marti.stc1000.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.marti.stc1000.domain.TempUnit
import com.marti.stc1000.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Preferencias de la app (apariencia, unidad, gráfico, rango deseado). No son datos de temperatura. */
data class AppPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val unit: TempUnit = TempUnit.C,
    val smooth: Boolean = true,
    val showAverage: Boolean = true,
    val showMinMax: Boolean = true,
    val rangeEnabled: Boolean = false,
    /** Siempre en °C. */
    val rangeMin: Double = 20.0,
    val rangeMax: Double = 28.0,
)

private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "prefs")

class PrefsStore(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val unit = stringPreferencesKey("unit")
        val smooth = booleanPreferencesKey("smooth")
        val average = booleanPreferencesKey("show_average")
        val minMax = booleanPreferencesKey("show_minmax")
        val rangeOn = booleanPreferencesKey("range_enabled")
        val rangeMin = doublePreferencesKey("range_min")
        val rangeMax = doublePreferencesKey("range_max")
    }

    val flow: Flow<AppPrefs> = context.prefsDataStore.data.map { p ->
        val d = AppPrefs()
        AppPrefs(
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = p[Keys.dynamic] ?: d.dynamicColor,
            unit = p[Keys.unit]?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() } ?: d.unit,
            smooth = p[Keys.smooth] ?: d.smooth,
            showAverage = p[Keys.average] ?: d.showAverage,
            showMinMax = p[Keys.minMax] ?: d.showMinMax,
            rangeEnabled = p[Keys.rangeOn] ?: d.rangeEnabled,
            rangeMin = p[Keys.rangeMin] ?: d.rangeMin,
            rangeMax = p[Keys.rangeMax] ?: d.rangeMax,
        )
    }

    suspend fun update(block: (AppPrefs) -> AppPrefs) {
        context.prefsDataStore.edit { p ->
            val cur = AppPrefs(
                theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                dynamicColor = p[Keys.dynamic] ?: false,
                unit = p[Keys.unit]?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() } ?: TempUnit.C,
                smooth = p[Keys.smooth] ?: true,
                showAverage = p[Keys.average] ?: true,
                showMinMax = p[Keys.minMax] ?: true,
                rangeEnabled = p[Keys.rangeOn] ?: false,
                rangeMin = p[Keys.rangeMin] ?: 20.0,
                rangeMax = p[Keys.rangeMax] ?: 28.0,
            )
            val n = block(cur)
            p[Keys.theme] = n.theme.name
            p[Keys.dynamic] = n.dynamicColor
            p[Keys.unit] = n.unit.name
            p[Keys.smooth] = n.smooth
            p[Keys.average] = n.showAverage
            p[Keys.minMax] = n.showMinMax
            p[Keys.rangeOn] = n.rangeEnabled
            p[Keys.rangeMin] = n.rangeMin
            p[Keys.rangeMax] = n.rangeMax
        }
    }
}
