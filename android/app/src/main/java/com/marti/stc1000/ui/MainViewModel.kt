package com.marti.stc1000.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marti.stc1000.Stc1000App
import com.marti.stc1000.data.ConnSettings
import com.marti.stc1000.data.Sample
import com.marti.stc1000.domain.Period
import com.marti.stc1000.domain.Stats
import com.marti.stc1000.mqtt.ConnectionState
import com.marti.stc1000.mqtt.DeviceState
import com.marti.stc1000.mqtt.MqttSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val loaded: Boolean = false,
    val settings: ConnSettings = ConnSettings(),
    val connection: ConnectionState = ConnectionState.Idle,
    val device: DeviceState = DeviceState(),
    val period: Period = Period.TODAY,
    val samples: List<Sample> = emptyList(),
    val stats: Stats? = null,
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
            kotlinx.coroutines.delay(30_000)
        }
    }

    private val samples = combine(period, ticker, repo.samples) { p, now, all ->
        val from = p.startEpoch(now)
        all.filter { it.ts >= from }
    }

    val state: StateFlow<MainUiState> = combine(
        stcApp.settings.flow, repo.connection, repo.device, period, samples,
    ) { settings, conn, device, p, list ->
        MainUiState(
            loaded = true,
            settings = settings,
            connection = conn,
            device = device,
            period = p,
            samples = list,
            stats = Stats.compute(list),
            nowSec = System.currentTimeMillis() / 1000,
        )
    }.combine(repo.samples) { ui, all -> ui.copy(lastSample = all.lastOrNull()) }
        .combine(ticker) { ui, now -> ui.copy(nowSec = now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun selectPeriod(p: Period) {
        period.value = p
    }

    fun saveSettings(s: ConnSettings) {
        viewModelScope.launch { stcApp.settings.save(s) }
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
                    else -> TestState.Done(true, "Conexión correcta. Equipo: ${estado.trim()}.")
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
}
