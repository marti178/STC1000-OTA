package com.marti.stc1000.mqtt

import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import com.marti.stc1000.data.ConnSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Error de conexión ya traducido para el usuario. [fatal] = reintentar solo no sirve (credenciales, etc.). */
data class ConnError(val message: String, val fatal: Boolean)

object MqttSupport {
    const val CONNECT_TIMEOUT_MS = 12_000L
    const val KEEPALIVE_SEC = 30

    /** El cliente nunca se reutiliza entre conexiones: cada intento arma uno nuevo con id único. */
    fun buildClient(
        s: ConnSettings,
        onDisconnected: (Throwable?) -> Unit = {},
    ): Mqtt3AsyncClient {
        val builder = Mqtt3Client.builder()
            .identifier("stc1000-android-" + UUID.randomUUID().toString().take(8))
            .serverHost(s.host.trim())
            .serverPort(s.port)
            .addDisconnectedListener { ctx -> onDisconnected(ctx.cause) }
        if (s.tls) builder.sslWithDefaultConfig()
        return builder.buildAsync()
    }

    /** Conecta y espera el CONNACK. Tira excepción si falla (usar [describe] para mostrarla). */
    suspend fun connect(client: Mqtt3AsyncClient, s: ConnSettings) {
        val req = client.connectWith()
            .keepAlive(KEEPALIVE_SEC)
            .cleanSession(true)
        if (s.username.isNotEmpty()) {
            req.simpleAuth()
                .username(s.username)
                .password(s.password.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }
        val ack = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { req.send().await() }
            ?: run {
                client.disconnect()
                throw SocketTimeoutException("connect timed out")
            }
        check(ack.returnCode == Mqtt3ConnAckReturnCode.SUCCESS) { "CONNACK ${ack.returnCode}" }
    }

    suspend fun subscribe(client: Mqtt3AsyncClient, topic: String) {
        client.subscribeWith().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE).send().await()
    }

    fun disconnectQuietly(client: Mqtt3AsyncClient?) {
        try {
            if (client != null && client.state != MqttClientState.DISCONNECTED) client.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * Conexión descartable: conecta, se suscribe a [topic], espera el primer mensaje (el retenido)
     * hasta [timeoutMs] y desconecta. Devuelve el texto, o null si no llegó nada. Tira si no conecta.
     */
    suspend fun readOnce(s: ConnSettings, topic: String, timeoutMs: Long): String? {
        val got = CompletableDeferred<String>()
        val client = buildClient(s)
        try {
            client.publishes(MqttGlobalPublishFilter.ALL) { p ->
                if (p.topic.toString() == topic) got.complete(String(p.payloadAsBytes, Charsets.UTF_8))
            }
            connect(client, s)
            subscribe(client, topic)
            return withTimeoutOrNull(timeoutMs) { got.await() }
        } finally {
            disconnectQuietly(client)
        }
    }

    fun describe(t: Throwable, s: ConnSettings): ConnError {
        val chain = generateSequence(t) { it.cause }.take(8).toList()

        chain.filterIsInstance<Mqtt3ConnAckException>().firstOrNull()?.let { e ->
            return when (e.mqttMessage.returnCode) {
                Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD,
                Mqtt3ConnAckReturnCode.NOT_AUTHORIZED,
                -> ConnError("Usuario o clave incorrectos.", fatal = true)
                Mqtt3ConnAckReturnCode.SERVER_UNAVAILABLE ->
                    ConnError("El broker no está disponible en este momento.", fatal = false)
                else -> ConnError("El broker rechazó la conexión (${e.mqttMessage.returnCode}).", fatal = true)
            }
        }
        if (chain.any { it is UnknownHostException }) {
            return ConnError("No se encuentra «${s.host}». Revisá la dirección del server.", fatal = false)
        }
        if (chain.any { it is SSLException }) {
            return ConnError(
                "Falló la conexión segura (TLS). Revisá que el puerto sea el de TLS y que el certificado sea válido.",
                fatal = false,
            )
        }
        val refused = chain.any { it is ConnectException && it.message?.contains("refused", true) == true }
        if (refused) {
            return ConnError(
                "El server rechazó la conexión: ¿Mosquitto está activo y el puerto ${s.port} es el correcto?",
                fatal = false,
            )
        }
        val unreachable = chain.any {
            it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException ||
                it.javaClass.simpleName.contains("ConnectTimeout") ||
                it.javaClass.simpleName.contains("ClosedChannel") ||
                it.message?.contains("unreachable", true) == true
        }
        if (unreachable) {
            return ConnError("No se llega al server: ¿está activo Tailscale (o estás en la red de casa)?", fatal = false)
        }
        val detail = t.message ?: t.javaClass.simpleName
        return ConnError("Error de conexión: $detail", fatal = false)
    }

    fun describeOrRethrow(t: Throwable, s: ConnSettings): ConnError {
        if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
        return describe(t, s)
    }
}
