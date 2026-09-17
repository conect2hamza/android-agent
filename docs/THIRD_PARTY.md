# Third-party components

The specification is explicit that "open source" does not mean "any use is permitted", and that this
inventory must exist before the app is distributed publicly or commercially. This file is that
inventory. **It records what the project depends on today and what must still be checked.**

Versions are the ones pinned in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml); that file
is the source of truth if the two ever disagree.

## Runtime dependencies shipped inside the APK

| Component | Version | Licence | Source | Commercial use | Attribution |
| --- | --- | --- | --- | --- | --- |
| Kotlin standard library | 2.0.21 | Apache-2.0 | JetBrains | Permitted | Notice file |
| kotlinx-coroutines | 1.9.0 | Apache-2.0 | JetBrains | Permitted | Notice file |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | Google | Permitted | Notice file |
| AndroidX Lifecycle (runtime, viewmodel, process) | 2.8.6 | Apache-2.0 | Google | Permitted | Notice file |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | Google | Permitted | Notice file |
| Jetpack Compose (BOM) | 2024.10.01 | Apache-2.0 | Google | Permitted | Notice file |
| Compose Material 3 | via BOM | Apache-2.0 | Google | Permitted | Notice file |
| Compose Material Icons Extended | via BOM | Apache-2.0 | Google | Permitted | Notice file |
| AndroidX Navigation Compose | 2.8.3 | Apache-2.0 | Google | Permitted | Notice file |
| AndroidX Room | 2.6.1 | Apache-2.0 | Google | Permitted | Notice file |
| AndroidX WorkManager | 2.9.1 | Apache-2.0 | Google | Permitted | Notice file |
| AndroidX Biometric | 1.2.0-alpha05 | Apache-2.0 | Google | Permitted | Notice file |

All of the above are Apache-2.0. The obligation is a notice: ship the licence text and attributions in
the app (a "Licences" entry in Settings) before any public release.

## Build-time only -- not shipped

| Component | Version | Licence |
| --- | --- | --- |
| Android Gradle Plugin | 8.6.1 | Apache-2.0 |
| Kotlin Gradle Plugin / Compose compiler plugin | 2.0.21 | Apache-2.0 |
| KSP | 2.0.21-1.0.25 | Apache-2.0 |
| Gradle | 8.10.2 | Apache-2.0 |
| JUnit 4 | 4.13.2 | EPL-1.0 (test only) |

JUnit is EPL-1.0, which has terms Apache-2.0 does not. It is a test dependency and is never packaged
into the APK, so it carries no distribution obligation -- but do not promote it to `implementation`
without re-reading it.

## Deliberately avoided

| Component | Why not |
| --- | --- |
| A charting library | Four chart shapes, ~150 lines of canvas drawing. A library adds APK weight, a licence to audit, and in several cases a WebView -- for an offline screen showing at most 31 points. |
| A JSON library | `org.json` is on Android but absent from the JVM test classpath; kotlinx.serialization and Moshi pull a codegen plugin into a module whose purpose is building anywhere. `core/util/MiniJson.kt` is ours, is hostile to malformed input by design, and is unit-tested. |
| A DI framework | About two dozen objects that never change shape at runtime. Hilt would add build time and APK weight to an app whose goals are staying small and starting fast. |
| SQLCipher | A native dependency with its own licensing terms and a real cost on low-end devices. Field-level Keystore encryption covers the free text instead. Revisit if whole-file encryption becomes a hard requirement. |

## Not yet chosen -- **review required before shipping a model**

No inference engine and no model weights are bundled. Both are the highest-risk licensing decisions in
the project, and both must be reviewed here before any build that includes them.

| Component | Status | What must be checked |
| --- | --- | --- |
| Inference runtime (llama.cpp, MediaPipe LLM, ONNX Runtime, ExecuTorch...) | Not chosen | Licence; whether static linking imposes obligations; native binary size per ABI; export-control implications of shipping crypto-adjacent native code |
| Model weights (e.g. Qwen3 0.6B) | Not chosen | **Weight licences are not software licences.** Check the specific model card for commercial-use restrictions, redistribution rights, attribution and naming requirements, acceptable-use clauses, and whether outputs carry conditions |

Shipping weights inside the APK and letting the user supply their own file are different legal
positions. The current design -- the user places a file in the app's private models directory -- avoids
redistribution entirely, and that is worth keeping unless there is a strong reason not to.

## Maintaining this file

Update it in the same commit as any dependency change. A licence inventory that lags the build is
worse than none, because it is trusted and wrong.
