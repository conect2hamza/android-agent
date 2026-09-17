# Architecture

## Shape

```
User
 |
 v
Chat screen  ------------------.
 |                             |
 v                             v
AssistantInterpreter      Tasks / Calendar / Reports / Memory screens
 |   (rules first, model only where rules fall short)     |
 v                                                        |
AssistantCommand  -- a closed set. Nothing else crosses.  |
 |                                                        |
 v                                                        v
AssistantService  ------------------------------>  Repositories
 |                                                        |
 v                                                        v
Repositories -> Room (encrypted free-text fields)   AlarmManager / WorkManager
```

Two modules:

- **`:core`** is pure Kotlin. The data model, the trilingual parser, the scheduling engine, the task
  state machine, the report generator, a hand-written JSON reader and the export format. No Android
  types anywhere, so all of it runs in a plain JUnit test.
- **`:app`** is everything that needs a device: Room, Compose, alarms, notifications, the Keystore, and
  hosting a model.

The split is not ceremony. Every decision the product makes is in `:core`, which means every decision
is testable without an emulator.

## Why the rules run first

`AssistantInterpreter` always runs the deterministic parser. It consults a model only when the rules
report low confidence, and falls back to the rules if the model returns nothing usable.

This one choice satisfies three separate requirements at once:

1. **The app works with no model.** The specification requires core functionality to survive AI
   failure; here there is nothing to survive, because the rules are the primary path.
2. **Battery.** The largest saving available is not quantisation -- it is not running the model. Most
   messages never reach it.
3. **Correctness.** A sub-billion-parameter model is measurably bad at date arithmetic. The rules own
   dates and times even in the hybrid path, and `CommandCodec` re-derives any date or time the model
   returns from the user's original wording.

## The trust boundary

`CommandCodec` is where model output stops being text and becomes data. It:

- accepts only a fixed set of intents and rejects everything else outright;
- re-resolves dates and times with `DateResolver` and `TimeResolver`;
- clamps every number to its legal range and length-limits every string;
- strips control characters;
- returns `null` on any failure, which routes the caller back to the rules.

A wrong parse can waste a turn. It cannot put the wrong thing in your calendar.

## Honest guesses

Some input is genuinely ambiguous, and the parser says so instead of hiding it:

- `4 baje` and `at 4` do not state AM or PM. The resolver applies a documented working-hours heuristic
  and sets `meridiemAssumed`.
- Urdu `kal` means both *tomorrow* and *yesterday*; `parson` likewise in both directions. The direction
  is chosen from the sentence's intent and flagged as `dateDirectionAssumed`.

Each flag lowers the parse confidence and appears in the assistant's confirmation -- "I read the time as
afternoon/evening -- tap Edit to change". A guess presented as a fact is the failure mode that turns
into a missed appointment.

## Time

Wall-clock, not UTC. `4 PM tomorrow` stays 4 PM after you fly somewhere; the epoch second in the
database is an implementation detail of row ordering. Dates are stored as epoch days and times as
minutes past midnight, which keeps `BETWEEN` queries index-friendly.

## Alarms and battery

| Job | Mechanism | Why |
| --- | --- | --- |
| Reminder, start follow-up | Exact alarm | A planner that fires "some time this hour" is not a planner |
| Progress and completion check | Exact alarm | Anchored to the task's own end time |
| Daily summary, weekly report | WorkManager | A summary at 21:04 costs nothing; batching costs much less battery |
| Missed tasks, series top-up | WorkManager, nightly | Lets the rest of the app avoid polling entirely |

Recurring tasks are materialised 60 days ahead, with alarms armed only 7 days ahead. A daily task
therefore costs tens of rows rather than thousands, while each occurrence stays individually
completable, reschedulable and skippable.

When Android 12+ revokes the exact-alarm permission, the scheduler degrades to an inexact window and
the dashboard says reminders may drift. Failing silently would be worse than failing.

## Storage and privacy

- No `INTERNET` permission. Nothing can leave the device even if a future dependency tried.
- Automatic cloud backup and device-to-device transfer are both excluded in the manifest.
- Memories and chat messages are encrypted with an AES-GCM key held in the Android Keystore. The
  structured columns stay readable because the app has to query on them.
- Whole-database encryption would mean SQLCipher: a native dependency with its own licence terms and a
  real cost on low-end devices. Field-level encryption covers the free text, which is the part worth
  protecting.
- Backups are encrypted with a passphrase the user chooses, so the file is useful off-device without
  the Keystore key travelling with it.

## Known limitations

- **`:app` is unbuilt.** See the README. The module was written without access to the Android SDK.
- **No tests in `:app`.** The repositories are concrete classes rather than interfaces, which makes
  them awkward to fake. Extracting interfaces is the first thing to do when adding app-level tests.
- **No inference engine ships.** `LlmRuntime` has only a no-op implementation. See `docs/MODELS.md`.
- **Roman Urdu has no fixed spelling.** The lexicon covers the common variants; it will need widening
  against real usage, and that is the file most likely to need edits after the first week of use.
