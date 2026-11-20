package com.marti.stc1000.mqtt

import android.content.Context
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.marti.stc1000.data.AppDatabase
import com.marti.stc1000.data.ConnSettings
import com.marti.stc1000.data.SettingsStore
import com.marti.stc1000.domain.HistoryParser
import com.marti.stc1000.domain.Samples
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Mantiene UNA conexión MQTT viva mientras la app está a la vista:
 * reconecta con backoff exponencial, reacciona a cambios de red y guarda lo que recibe en Room.
 */
class MqttRepository(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val dao = AppDatabase.get(appContext).samples()
    private val net = NetworkWatcher(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection

    private val _device = MutableStateFlow(DeviceState())
    val device: StateFlow<DeviceState> = _device

    private var supervisor: Job? = null
    private var stopJob: Job? = null

    @Volatile private var client: Mqtt3AsyncClient? = null
    @Volatile private var stepSec: Int = Samples.DEFAULT_STEP_SEC
    @Volatile private var activeSettings: ConnSettings = ConnSettings()

    /** La app pasó a primer plano. */
    @Synchronized
    fun start() {
        stopJob?.cancel()
        stopJob = null
        if (supervisor?.isActive == true) return
        net.start()
        supervisor = scope.launch {
            settingsStore.flow.distinctUntilChanged().collectLatest { s ->
                if (!s.isConfigured) {
                    _connection.value = ConnectionState.Idle
                } else {
                    activeSettings = s
                    runConnection(s)
                }
            }
        }
    }

    /** La app pasó a segundo plano: se corta tras un rato por si el usuario vuelve enseguida. */
    @Synchronized
    fun stop(graceMs: Long = 30_000) {
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(graceMs)
            synchronized(this@MqttRepository) {
                supervisor?.cancel()
                supervisor = null
                net.stop()
            }
            MqttSupport.disconnectQuietly(client)
            client = null
            _connection.value = ConnectionState.Idle
        }
    }

    /** Reintento manual (botón "Reintentar"). */
    fun retryNow() = net.kick()

    /** Publica un comando (`CHECK`, `OTA`, `REBOOT`) retenido y con QoS 1, como pide el contrato. */
    suspend fun sendCommand(command: String): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("No hay conexión con el server."))
        return try {
            c.publishWith()
                .topic(activeSettings.topic("comando"))
                .payload(command.toByteArray(Charsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .send()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun runConnection(s: ConnSettings) {
        var attempt = 0
        val topics = listOf("estado", "temperatura", "display", "firmware", "info", "historial", "ota")
        while (true) {
            if (!net.online.value) {
                _connection.value = ConnectionState.WaitingNetwork
                net.online.first { it }
            }
            net.kicks.tryReceive() // descarta señales viejas
            _connection.value = ConnectionState.Connecting(attempt)

            val dropped = CompletableDeferred<Throwable?>()
            val c = MqttSupport.buildClient(s) { cause -> dropped.complete(cause) }
            client = c
            var error: ConnError? = null
            try {
                c.publishes(MqttGlobalPublishFilter.ALL) { p ->
                    handleMessage(s, p.topic.toString(), String(p.payloadAsBytes, Charsets.UTF_8), p.isRetain)
                }
                MqttSupport.connect(c, s)
                topics.forEach { MqttSupport.subscribe(c, s.topic(it)) }
                attempt = 0
                _connection.value = ConnectionState.Connected

                // Conectado: esperar a que se caiga o a que cambie la red.
                val cause = select<Throwable?> {
                    dropped.onAwait { it }
                    net.kicks.onReceive { null }
                }
                if (cause != null) error = MqttSupport.describe(cause, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = MqttSupport.describe(e, s)
            } finally {
                withContext(NonCancellable) { MqttSupport.disconnectQuietly(c) }
                if (client === c) client = null
            }

            if (error == null) {
                // Cambio de red: reconectar enseguida, sin backoff.
                attempt = 0
                continue
            }
            attempt++
            if (error.fatal) {
                _connection.value = ConnectionState.Error(error.message, fatal = true, retryInSec = null)
                net.kicks.receive() // espera un reintento manual (o un cambio de ajustes)
                attempt = 0
            } else {
                val waitSec = backoffSeconds(attempt)
                waitWithCountdown(error.message, waitSec)
            }
        }
    }

    private suspend fun waitWithCountdown(message: String, waitSec: Int) {
        var left = waitSec
        while (left > 0) {
            _connection.value = ConnectionState.Error(message, fatal = false, retryInSec = left)
            val kicked = withTimeoutOrNull(1_000) { net.kicks.receive() }
            if (kicked != null) return
            left--
        }
    }

    private fun handleMessage(s: ConnSettings, topic: String, text: String, retained: Boolean) {
        val name = topic.removePrefix(s.prefix.trim('/') + "/")
        val now = System.currentTimeMillis() / 1000
        when (name) {
            "estado" -> _device.update { it.copy(online = text.trim().equals("online", ignoreCase = true)) }
            "temperatura" -> {
                val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return
                if (!Samples.isPlausible(v)) return
                _device.update { d ->
                    d.copy(temperature = v, lastLiveAtSec = if (retained) d.lastLiveAtSec else now)
                }
                // Un mensaje retenido no dice cuándo se midió: solo se guarda lo recibido en vivo.
                if (!retained) {
                    Samples.fromLive(v, now, stepSec)?.let { scope.launch { dao.upsertAll(listOf(it)) } }
                }
            }
            "display" -> _device.update { it.copy(display = text.trim()) }
            "firmware" -> _device.update { it.copy(firmware = text.trim()) }
            "info" -> DeviceInfo.parse(text)?.let { i -> _device.update { it.copy(info = i) } }
            "ota" -> _device.update { it.copy(ota = text.trim()) }
            "historial" -> HistoryParser.parse(text)?.let { h ->
                stepSec = h.stepSec
                val samples = Samples.fromHistory(h, now)
                if (samples.isNotEmpty()) scope.launch { dao.upsertAll(samples) }
            }
        }
    }

    companion object {
        /** 2, 4, 8, 16, 32, 60, 60... segundos, con hasta 1 s de azar para no sincronizar reintentos. */
        fun backoffSeconds(attempt: Int): Int {
            val base = minOf(60L, 1L shl attempt.coerceIn(1, 6))
            return (base + Random.nextInt(0, 2)).toInt()
        }
    }
}
