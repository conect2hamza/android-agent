# Room generates implementations reflectively referenced by the runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Broadcast receivers and workers are instantiated by the platform by name.
-keep class com.personal.assistant.notify.** { *; }
-keep class com.personal.assistant.work.** { *; }

# The local model runtime is loaded reflectively so the app installs without it.
-keep class com.personal.assistant.ai.LlmRuntime { *; }
-keep class * implements com.personal.assistant.ai.LlmRuntime { *; }
