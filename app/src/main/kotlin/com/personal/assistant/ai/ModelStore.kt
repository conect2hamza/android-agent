package com.personal.assistant.ai

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import java.io.File

/** A model file the user has installed. */
data class InstalledModel(
    val id: String,
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
) {
    val sizeMb: Long get() = sizeBytes / (1024 * 1024)
}

/**
 * Manages model files in the app's private storage.
 *
 * Models live under `filesDir` and never in shared storage: a file in Downloads is readable by other
 * apps and would survive uninstall, neither of which suits an app whose selling point is that nothing
 * leaves the device.
 */
class ModelStore(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    fun installed(): List<InstalledModel> = directory
        .listFiles { file -> file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS }
        ?.map { file ->
            InstalledModel(
                id = file.nameWithoutExtension,
                displayName = file.nameWithoutExtension.replace('-', ' ').replace('_', ' '),
                file = file,
                sizeBytes = file.length(),
            )
        }
        ?.sortedBy { it.displayName }
        ?: emptyList()

    fun find(id: String): InstalledModel? = installed().firstOrNull { it.id == id }

    fun delete(id: String): Boolean = find(id)?.file?.delete() ?: false

    fun totalBytes(): Long = installed().sumOf { it.sizeBytes }

    /**
     * Whether this device can plausibly host a model of [sizeBytes].
     *
     * The check is against the system's own per-app memory class rather than total RAM, and it demands
     * real headroom: a model that loads and then has the app killed on the next screen rotation is worse
     * than one that was never offered.
     */
    fun canHost(sizeBytes: Long): Boolean {
        val activityManager = context.getSystemService<ActivityManager>() ?: return false
        val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        if (info.lowMemory) return false
        val budgetBytes = activityManager.largeMemoryClass.toLong() * 1024 * 1024
        return sizeBytes * HEADROOM_FACTOR < budgetBytes && sizeBytes < info.availMem / 2
    }

    companion object {
        const val DIRECTORY = "models"
        val SUPPORTED_EXTENSIONS = setOf("gguf", "task", "bin", "tflite")

        /** Weights are not the whole cost; the context and KV cache need room too. */
        private const val HEADROOM_FACTOR = 1.5
    }
}
