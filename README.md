# Personal Offline AI Assistant

A private, offline personal assistant for Android. You talk to it in a chat screen, in English, Urdu or
Roman Urdu, and it turns what you say into scheduled tasks, reminders, progress tracking and reports.
Everything stays on the phone: there is no account, no server, and no network permission.

Built to the Software Requirements Specification v1.0 in this repository's history.

---

## The one idea worth understanding

> The AI understands. Application logic executes. The database remembers. The scheduler reminds.
> Reports analyse.

The language layer can only ever *propose* one of a fixed set of commands. It never writes to the
database, never sets an alarm, and never composes the numbers you read in a report. That is what makes
the next two claims possible at once:

- **The app works with no language model installed at all.** A deterministic parser
  (`core/.../nlp`) handles English, Urdu script and Roman Urdu. It is not a degraded fallback mode --
  it is the default path, and it is covered by 135 unit tests.
- **A model can be added without touching anything else.** `AiProvider` and `LlmRuntime` are the seam.

## What it does

| Area | Covered |
| --- | --- |
| Chat | Natural-language task creation, rescheduling, completion, progress, queries |
| Languages | English, Urdu script, Roman Urdu, and mixed input |
| Tasks | Full lifecycle, priorities, categories, notes, recurring series |
| Reminders | Lead-up, "are you starting it?", progress check, "did you finish?", missed follow-up |
| Calendar | Day, week and month views with per-day status markers |
| Reports | Daily, weekly and monthly, with charts drawn on a Compose canvas |
| Tracking | Real elapsed time replayed from an append-only activity log |
| Memory | Everything the assistant remembers, visible and individually deletable |
| Data | JSON and CSV export, passphrase-encrypted backup, validated import |
| Security | Keystore-backed field encryption, optional app lock, no cloud backup |

## Build

```bash
./gradlew :core:test      # works anywhere with a JVM
./gradlew :app:assembleDebug   # needs the Android SDK
```

`:app` is only included in the build when an Android SDK is actually present (`ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`). That is deliberate: it means `:core` still
builds and tests on a machine or CI image with no SDK installed.

Requirements for the app module: Android SDK 35, JDK 17 or newer, and network access to Google's Maven
repository.

### Build status, stated plainly

`:core` is compiled and tested. `:app` has **not been compiled** -- it was written in an environment
with no access to the Android SDK or to Google's Maven repository, so AndroidX, Room and the Android
Gradle Plugin could not be resolved. Expect to fix compilation errors on the first real build. The
logic that decides anything -- parsing, scheduling, status transitions, reporting, import validation --
lives in `:core` precisely so it could be verified independently of that.

## Layout

```
core/    Pure Kotlin. No Android dependency. The parser, scheduler, state machine,
         report engine, JSON reader and export format. Fully unit-tested.
app/     Android. Room, Compose, alarms, notifications, Keystore, model hosting.
docs/    Architecture notes and the third-party licence inventory.
```

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) -- how the pieces fit, and why the design choices went
  the way they did.
- [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md) -- dependency and licence inventory, required before any
  public or commercial release.
- [`docs/MODELS.md`](docs/MODELS.md) -- how to add a local language model, and what to benchmark before
  choosing one.

## Licence

See [`LICENSE`](LICENSE). Third-party components have their own terms; "open source" is not the same as
"any use is permitted", and the inventory in `docs/THIRD_PARTY.md` exists to keep that distinction
visible.
