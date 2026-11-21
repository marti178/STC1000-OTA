package com.marti.stc1000.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marti.stc1000.Stc1000App
import com.marti.stc1000.data.AppPrefs
import com.marti.stc1000.data.ConnSettings
import com.marti.stc1000.data.Sample
import com.marti.stc1000.domain.DaySummary
import com.marti.stc1000.domain.Period
import com.marti.stc1000.domain.Stats
import com.marti.stc1000.domain.Summaries
import com.marti.stc1000.mqtt.ConnectionState
import com.marti.stc1000.mqtt.DeviceState
import com.marti.stc1000.mqtt.LogEntry
import com.marti.stc1000.mqtt.MqttSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val loaded: Boolean = false,
    val settings: ConnSettings = ConnSettings(),
    val prefs: AppPrefs = AppPrefs(),
    val connection: ConnectionState = ConnectionState.Idle,
    val device: DeviceState = DeviceState(),
    val log: List<LogEntry> = emptyList(),
    val period: Period = Period.TODAY,
    /** Todo lo que hay en memoria (hasta 31 días). */
    val allSamples: List<Sample> = emptyList(),
    /** Solo las del período elegido. */
    val samples: List<Sample> = emptyList(),
    val stats: Stats? = null,
    val inRange: Double? = null,
    val days: List<DaySummary> = emptyList(),
    /** Cambio de temperatura en ~30 min, en °C. */
    val trend: Double? = null,
    val lastSample: Sample? = null,
    val nowSec: Long = System.currentTimeMillis() / 1000,
)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Done(val ok: Boolean, val message: String) : TestState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val stcApp = app as Stc1000App
    private val repo = stcApp.repository

    private val period = MutableStateFlow(Period.TODAY)
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            kotlinx.coroutines.delay(5_000)
        }
    }

    private data class Base(
        val settings: ConnSettings,
        val prefs: AppPrefs,
        val conn: ConnectionState,
        val device: DeviceState,
        val log: List<LogEntry>,
    )

    private val base = combine(
        stcApp.settings.flow, stcApp.prefs.flow, repo.connection, repo.device, repo.log,
    ) { settings, prefs, conn, device, log -> Base(settings, prefs, conn, device, log) }

    val state: StateFlow<MainUiState> = combine(
        base, period, repo.samples, ticker,
    ) { b, p, all, now ->
        val start = p.startEpoch(now)
        // La muestra rotulada con la hora de inicio es el promedio del intervalo ANTERIOR: no entra.
        val list = all.filter { it.ts > start }
        MainUiState(
            loaded = true,
            settings = b.settings,
            prefs = b.prefs,
            connection = b.conn,
            device = b.device,
            log = b.log,
            period = p,
            allSamples = all,
            samples = list,
            stats = Stats.compute(list),
            inRange = if (b.prefs.rangeEnabled) {
                Stats.inRangeFraction(list, b.prefs.rangeMin, b.prefs.rangeMax)
            } else {
                null
            },
            days = Summaries.byDay(list),
            trend = Summaries.trend(all),
            lastSample = all.lastOrNull(),
            nowSec = now,
        )
    }
        .flowOn(Dispatchers.Default) // estadisticas y resumenes sobre miles de muestras: fuera del hilo principal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun selectPeriod(p: Period) {
        period.value = p
    }

    /** [onDone] recibe true si se guardó; si falla (Keystore roto) se avisa con el snackbar. */
    fun saveSettings(s: ConnSettings, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runCatching { stcApp.settings.save(s) }.getOrDefault(false)
            if (!ok) _message.value = "No se pudo guardar la contraseña de forma segura. Probá de nuevo."
            onDone(ok)
        }
    }

    fun updatePrefs(block: (AppPrefs) -> AppPrefs) {
        viewModelScope.launch { stcApp.prefs.update(block) }
    }

    fun retry() = repo.retryNow()

    fun consumeMessage() {
        _message.value = null
    }

    fun clearTest() {
        _test.value = TestState.Idle
    }

    /** "Probar conexión" con lo que hay en el formulario (sin guardar). */
    fun testConnection(s: ConnSettings) {
        if (_test.value == TestState.Running) return
        _test.value = TestState.Running
        viewModelScope.launch {
            _test.value = try {
                val estado = MqttSupport.readOnce(s, s.topic("estado"), 4_000)
                when {
                    estado == null -> TestState.Done(
                        true,
                        "Conectado al broker, pero el equipo no publicó su estado. ¿El prefijo «${s.prefix}» es el correcto?",
                    )
                    else -> TestState.Done(true, "Conexión correcta. Último estado guardado del equipo: ${estado.trim()}.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Done(false, MqttSupport.describe(e, s).message)
            }
        }
    }

    fun sendCommand(command: String, okText: String) {
        viewModelScope.launch {
            val r = repo.sendCommand(command)
            _message.value = if (r.isSuccess) okText else "No se pudo enviar: ${r.exceptionOrNull()?.message ?: "error"}"
        }
    }

    fun toast(text: String) {
        _message.value = text
    }
}
