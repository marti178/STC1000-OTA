package com.marti.stc1000.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observa la red por defecto (Wi-Fi, datos o VPN/Tailscale).
 * - [online]: hay red por defecto.
 * - [kicks]: señal "reconectá ya" cuando cambia la red por defecto, o cuando alguien la pide a mano.
 */
class NetworkWatcher(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    val kicks = Channel<Unit>(Channel.CONFLATED)

    private var current: Network? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = current != null && current != network
            current = network
            _online.value = true
            if (changed) kicks.trySend(Unit)
        }

        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                _online.value = false
            }
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        try {
            _online.value = cm.activeNetwork != null
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: SecurityException) {
            _online.value = true // sin permiso: asumimos que hay red y dejamos que MQTT falle solo
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
        }
        registered = false
        current = null
    }

    fun kick() {
        kicks.trySend(Unit)
    }
}
