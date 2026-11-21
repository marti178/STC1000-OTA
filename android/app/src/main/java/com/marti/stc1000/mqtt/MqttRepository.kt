package com.marti.stc1000.mqtt

import android.content.Context
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.marti.stc1000.data.Sample
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val net = NetworkWatcher(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    private fun logEvent(text: String, isError: Boolean = false) {
        val entry = LogEntry(System.currentTimeMillis() / 1000, text, isError)
        _log.update { (it + entry).takeLast(40) }
    }

    private val _device = MutableStateFlow(DeviceState())
    val device: StateFlow<DeviceState> = _device

    /**
     * Lecturas en memoria, solo para dibujar. La app NO guarda nada en el celular: el historial largo
     * lo guarda el server y se vuelve a bajar (retenido) cada vez que se conecta.
     */
    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples

    private fun addSamples(incoming: List<Sample>, nowSec: Long) {
        _samples.update { cur ->
            val cutoff = nowSec - RETAIN_SEC
            Samples.mergeLatestWins(cur, incoming).filter { it.ts >= cutoff }
        }
    }

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
            var identity: List<Any>? = null
            settingsStore.flow.distinctUntilChanged().collectLatest { s ->
                // Si cambia el server o el prefijo, lo que hay en memoria es de otro equipo: se descarta.
                val now = listOf(s.host, s.port, s.prefix, s.tls)
                if (identity != null && identity != now) {
                    _samples.value = emptyList()
                    _device.value = DeviceState()
                    stepSec = Samples.DEFAULT_STEP_SEC
                }
                identity = now
                when {
                    !s.isConfigured -> _connection.value = ConnectionState.Idle
                    s.passwordLost -> {
                        val msg = "La contraseña guardada no se pudo leer. Ingresala de nuevo en Ajustes."
                        logEvent(msg, isError = true)
                        _connection.value = ConnectionState.Error(msg, fatal = true, retryInSec = null)
                    }
                    else -> {
                        activeSettings = s
                        runConnection(s)
                    }
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
                // Si start() ya cancelo este trabajo (la app volvio justo ahora), no se corta la conexion nueva.
                ensureActive()
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
    suspend fun sendCommand(rawCommand: String): Result<Unit> {
        val d = _device.value
        val command = stampCommand(rawCommand, d.firmware ?: d.info?.version, System.currentTimeMillis() / 1000)
        val c = client ?: return Result.failure(IllegalStateException("No hay conexión con el server."))
        return try {
            withTimeout(COMMAND_TIMEOUT_MS) {
                c.publishWith()
                    .topic(activeSettings.topic("comando"))
                    .payload(command.toByteArray(Charsets.UTF_8))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .send()
                    .await()
            }
            Result.success(Unit)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("El server no confirmó el comando a tiempo."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun runConnection(s: ConnSettings) {
        var attempt = 0
        var lastStartMs = 0L
        // historial = últimas 24 h (lo publica el ESP32); historial_largo = hasta 30 días (lo publica el server).
        val topics = listOf(
            "estado", "temperatura", "display", "firmware", "info", "salidas", "historial", "historial_largo", "ota",
        )
        while (true) {
            if (!net.online.value) {
                _connection.value = ConnectionState.WaitingNetwork
                logEvent("Sin red: esperando conexión")
                net.online.first { it }
            }
            // Como mucho un intento cada 5 s, pase lo que pase (cambios de red, caidas rapidas): el broker
            // permite 30 conexiones nuevas por minuto.
            val sinceLast = System.currentTimeMillis() - lastStartMs
            if (sinceLast < MIN_ATTEMPT_GAP_MS) delay(MIN_ATTEMPT_GAP_MS - sinceLast)
            lastStartMs = System.currentTimeMillis()
            net.kicks.tryReceive() // descarta señales viejas
            _connection.value = ConnectionState.Connecting(attempt)
            logEvent("Conectando a ${s.host}:${s.port}" + if (s.tls) " (TLS)" else "")

            val dropped = CompletableDeferred<Throwable?>()
            val c = MqttSupport.buildClient(s) { cause -> dropped.complete(cause) }
            client = c
            var error: ConnError? = null
            var connectedAtMs = 0L
            try {
                c.publishes(MqttGlobalPublishFilter.ALL) { p ->
                    handleMessage(s, p.topic.toString(), String(p.payloadAsBytes, Charsets.UTF_8), p.isRetain)
                }
                MqttSupport.connect(c, s)
                topics.forEach { MqttSupport.subscribe(c, s.topic(it)) }
                connectedAtMs = System.currentTimeMillis()
                // Conexion nueva: las señales de la anterior ya no valen; se vuelve a verificar desde cero.
                _device.update { it.copy(watchSinceSec = System.currentTimeMillis() / 1000, lastSignalAtSec = null, estadoLiveAtSec = null) }
                _connection.value = ConnectionState.Connected
                logEvent("Conectado y suscripto")

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

            // El backoff solo se reinicia si la conexion aguanto un rato: si conecta y se cae enseguida
            // (ACL rota, Funnel que corta), los reintentos siguen espaciandose.
            if (connectedAtMs != 0L && System.currentTimeMillis() - connectedAtMs >= STABLE_AFTER_MS) attempt = 0

            if (error == null) {
                logEvent("Cambió la red: reconectando")
                continue
            }
            attempt++
            logEvent(error.message, isError = true)
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
            "estado" -> _device.update {
                val on = text.trim().equals("online", ignoreCase = true)
                it.copy(
                    online = on,
                    // Un estado en vivo vale como señal si dice online; si dice offline, manda sobre señales anteriores.
                    estadoLiveAtSec = if (retained) it.estadoLiveAtSec else now,
                    lastSignalAtSec = if (!retained && on) now else it.lastSignalAtSec,
                )
            }
            "temperatura" -> {
                // Payload vacio = el display no muestra numero: se borra el valor y no se suma al grafico.
                if (text.isBlank()) {
                    _device.update { d ->
                        d.copy(
                            temperature = null,
                            noReading = true,
                            lastSignalAtSec = if (retained) d.lastSignalAtSec else now,
                        )
                    }
                    return
                }
                val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return
                if (!Samples.isPlausible(v)) return
                _device.update { d ->
                    d.copy(
                        temperature = v,
                        noReading = false,
                        lastLiveAtSec = if (retained) d.lastLiveAtSec else now,
                        lastSignalAtSec = if (retained) d.lastSignalAtSec else now,
                    )
                }
                // Un mensaje retenido no dice cuándo se midió: solo se guarda lo recibido en vivo.
                if (!retained) {
                    Samples.fromLive(v, now, stepSec)?.let { addSamples(listOf(it), now) }
                }
            }
            "display" -> _device.update { it.copy(display = text.trim(), lastSignalAtSec = if (retained) it.lastSignalAtSec else now) }
            // Si cambia la version, la actualizacion termino: se borra el avance.
            "firmware" -> _device.update { d ->
                val v = text.trim()
                if (d.firmware != null && d.firmware != v) d.copy(firmware = v, ota = null, otaAtSec = null, otaPct = null, lastSignalAtSec = if (retained) d.lastSignalAtSec else now) else d.copy(firmware = v)
            }
            "salidas" -> Outputs.parse(text)?.let { o ->
                _device.update { it.copy(outputs = o, lastSignalAtSec = if (retained) it.lastSignalAtSec else now) }
            }
            "info" -> DeviceInfo.parse(text)?.let { i ->
                _device.update { it.copy(info = i, lastSignalAtSec = if (retained) it.lastSignalAtSec else now) }
            }
            // El OTA no es retenido: un retenido viejo no dice nada del momento actual.
            "ota" -> _device.update {
                it.copy(
                    ota = text.trim(),
                    otaAtSec = if (retained) it.otaAtSec else now,
                    otaPct = if (retained) it.otaPct else nextOtaPct(it.otaPct, text),
                    lastSignalAtSec = if (retained) it.lastSignalAtSec else now,
                )
            }
            // El historial largo puede pesar decenas de KB: se parsea y guarda fuera del hilo de red.
            "historial", "historial_largo" -> scope.launch {
                val h = HistoryParser.parse(text) ?: return@launch
                stepSec = h.stepSec
                val samples = Samples.fromHistory(h, now)
                if (samples.isNotEmpty()) {
                    addSamples(samples, now)
                } else if (h.values.isNotEmpty()) {
                    logEvent("Historial ignorado: la fecha no coincide con la hora del celular (revisá el reloj)", isError = true)
                }
            }
        }
    }

    companion object {
        /** Cuánto se mantiene en memoria (el server publica 30 días). */
        private const val RETAIN_SEC = 31L * 24 * 3600
        private const val MIN_ATTEMPT_GAP_MS = 5_000L
        private const val STABLE_AFTER_MS = 30_000L
        private const val COMMAND_TIMEOUT_MS = 10_000L

        /** 2, 4, 8, 16, 32, 60, 60... segundos, con hasta 1 s de azar para no sincronizar reintentos. */
        fun backoffSeconds(attempt: Int): Int {
            val base = minOf(60L, 1L shl attempt.coerceIn(1, 6))
            return (base + Random.nextInt(0, 2)).toInt()
        }
    }
}
