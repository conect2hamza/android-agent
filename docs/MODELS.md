# Adding a local language model

No inference engine and no weights ship with version 1.0. That is a decision, not an omission: the
specification requires a model to be chosen only after benchmarking on real devices, and requires every
third-party licence to be reviewed before release. Bundling something now would pre-empt both, and the
app does not need one to work.

## What exists already

- `LlmRuntime` -- the engine interface. One implementation ships: `NoLlmRuntime`, which reports absence.
- `LocalLlmProvider` -- hosts a runtime, builds the prompt, validates the output, and releases the
  weights when idle.
- `ModelStore` -- manages model files in the app's private storage and refuses ones the device cannot
  host.
- `PromptBuilder` / `CommandCodec` -- the prompt and the validation boundary. Both are unit-tested, and
  neither changes when the engine does.

## Adding one

1. Implement `LlmRuntime` against your engine of choice.
2. Pass it in `AppContainer.interpreter()` in place of `NoLlmRuntime`. That single constructor argument
   is the whole integration.
3. Add the engine and the weights to `docs/THIRD_PARTY.md` **before** building a release.

The model is loaded on demand, released after 90 seconds idle, and released again whenever the app goes
to the background. Keep it that way: the specification forbids a resident model, and it is the single
largest factor in the app's memory and battery footprint.

## What to benchmark before choosing

Run these on the lowest-end device you intend to support, not on a flagship:

| Measure | Why it decides the answer |
| --- | --- |
| Peak RSS while generating | Decides whether the app survives a rotation mid-answer |
| Load time, cold | Paid on every message after an idle release |
| Tokens per second | The user is watching a spinner for this |
| Battery per 100 messages | Compare against the rules-only path, which is the honest baseline |
| Structured-output accuracy | Percentage of replies that survive `CommandCodec` |
| English / Urdu / Roman Urdu quality | Measured on real sentences, not translated test data |

## The comparison that matters

Measure the model against the deterministic parser, not against nothing. The parser already handles the
specification's own example sentences and the 44 cases in `RuleBasedParserTest`. A model earns its
place in the APK only by handling phrasing the parser cannot -- and it must be weighed against the RAM,
battery and latency it costs on every message it touches.

If a candidate model does not clearly beat the rules on real input, the right decision is to ship
without it.
