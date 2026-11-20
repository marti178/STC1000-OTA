package com.marti.stc1000

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.marti.stc1000.data.SettingsStore
import com.marti.stc1000.mqtt.MqttRepository

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

        // Versiones anteriores guardaban las lecturas en una base local; ya no se usa.
        deleteDatabase("stc1000.db")
    }
}
