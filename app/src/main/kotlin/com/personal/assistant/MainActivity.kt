package com.personal.assistant

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.personal.assistant.data.repository.AppSettings
import com.personal.assistant.ui.AssistantApp
import com.personal.assistant.ui.theme.AssistantTheme
import android.os.Build

/**
 * Extends [FragmentActivity] rather than ComponentActivity because BiometricPrompt requires a
 * fragment host. FragmentActivity is itself a ComponentActivity, so Compose's setContent and the
 * activity-result APIs are unaffected.
 */
class MainActivity : FragmentActivity() {

    private val container: AppContainer
        get() = (application as AssistantApplication).container

    /**
     * Notifications are the app's whole follow-up mechanism, so the permission is requested on first
     * launch. A denial is not fatal -- the app keeps working and the dashboard shows a warning banner
     * explaining that reminders will not arrive.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result is read back from the notification manager, not stored here. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Collected inside composition rather than pinned to the application scope: an eagerly
        // started collector there is never cancelled, so every rotation would leave another one
        // running for the life of the process.
        val settingsFlow = container.settingsRepository.observe()

        setContent {
            val settings by settingsFlow.collectAsState(initial = AppSettings())
            var unlocked by remember { mutableStateOf(false) }

            AssistantTheme(choice = settings.theme) {
                if (settings.appLockEnabled && !unlocked) {
                    com.personal.assistant.ui.LockScreen(
                        activity = this,
                        onUnlocked = { unlocked = true },
                    )
                } else {
                    AssistantApp(
                        container = container,
                        settings = settings,
                        openTaskId = intent?.getLongExtra(
                            com.personal.assistant.notify.NotificationActions.EXTRA_TASK_ID,
                            -1L,
                        )?.takeIf { it > 0 },
                    )
                }
            }

            LaunchedEffect(settings.appLockEnabled) {
                if (!settings.appLockEnabled) unlocked = true
            }
        }
    }
}
