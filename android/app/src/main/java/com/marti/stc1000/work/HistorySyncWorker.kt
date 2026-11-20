package com.marti.stc1000.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marti.stc1000.data.AppDatabase
import com.marti.stc1000.data.SettingsStore
import com.marti.stc1000.domain.HistoryParser
import com.marti.stc1000.domain.Samples
import com.marti.stc1000.mqtt.MqttSupport
import kotlinx.coroutines.CancellationException

/**
 * Cada ~3 h: conecta un momento, lee el `historial` retenido y lo fusiona en Room.
 * Si no hay conexión (p. ej. Tailscale apagado) termina bien y espera a la próxima corrida.
 */
class HistorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).current()
        if (!settings.isConfigured) return Result.success()
        return try {
            val text = MqttSupport.readOnce(settings, settings.topic("historial"), READ_TIMEOUT_MS)
            val history = text?.let { HistoryParser.parse(it) }
            if (history != null) {
                val now = System.currentTimeMillis() / 1000
                val samples = Samples.fromHistory(history, now)
                if (samples.isNotEmpty()) AppDatabase.get(applicationContext).samples().upsertAll(samples)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.success()
        }
    }

    companion object {
        const val NAME = "history-sync"
        private const val READ_TIMEOUT_MS = 15_000L
    }
}
