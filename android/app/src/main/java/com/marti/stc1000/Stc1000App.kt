package com.marti.stc1000

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.marti.stc1000.data.AppDatabase
import com.marti.stc1000.data.SettingsStore
import com.marti.stc1000.mqtt.MqttRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Stc1000App : Application() {
    lateinit var repository: MqttRepository
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = MqttRepository(this)

        // Conectado solo mientras la app está a la vista (en segundo plano Android igual cortaría el socket).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = repository.start()
            override fun onStop(owner: LifecycleOwner) = repository.stop()
        })

        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            // Se conservan 90 días de muestras.
            val cutoff = System.currentTimeMillis() / 1000 - 90L * 24 * 3600
            AppDatabase.get(this@Stc1000App).samples().deleteOlderThan(cutoff)
        }
    }
}
