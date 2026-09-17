package com.personal.assistant

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.personal.assistant.notify.NotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.register(this)

        // Startup work is off the main thread and deliberately small: registering channels, re-arming
        // the summary jobs and topping up recurring series. Nothing here loads a model or reads tasks
        // into memory, so a cold start stays fast.
        container.applicationScope.launch(Dispatchers.IO) {
            container.alarmScheduler.rescheduleSummaries()
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    // The specification is explicit that the model must not run in the background.
                    container.applicationScope.launch(Dispatchers.IO) { container.releaseModel() }
                }
            },
        )
    }
}
