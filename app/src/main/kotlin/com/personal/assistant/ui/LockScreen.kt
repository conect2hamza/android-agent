package com.personal.assistant.ui

import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Optional app lock, delegated entirely to the platform.
 *
 * `DEVICE_CREDENTIAL` is included alongside biometrics so a user with no fingerprint enrolled can still
 * turn the lock on with their PIN. The app stores no secret of its own for this -- there is no separate
 * passcode to forget, and no hash for anyone to attack.
 */
@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }

    fun prompt() {
        val manager = BiometricManager.from(activity)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Nothing to authenticate against: never lock the user out of their own data.
            onUnlocked()
            return
        }
        prompting = true
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    error = errString.toString()
                }
            },
        )
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Assistant")
                .setSubtitle("Your tasks and memories are locked")
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }

    LaunchedEffect(Unit) { prompt() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Assistant is locked", style = MaterialTheme.typography.headlineSmall)
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        Button(onClick = { prompt() }, enabled = !prompting, modifier = Modifier.padding(top = 24.dp)) {
            Text("Unlock")
        }
    }
}
