# Mutation Receipts for android-utterance-identity

All XML files are raw JUnit test-results from actual gradle runs at head `aab5df416`.
Source was restored to production after each mutant run; green XML is from the same code.

## Mutant 1: hasLetterOrNumber always-true
**Production file:** `modules/services/voice/src/main/kotlin/.../engine/VoiceAsrEngine.kt` line 229
**Change:** Body replaced with `return true` (guard bypassed).
**Test:** `a punctuation-only discard followed by content on the same engine routes the second`
**RED XML:** `artifacts/mutation-receipts/mutant-hasLetterOrNumber-alwaysTrue-VoiceAsrEngineTest.xml`
  - tests="1" failures="1" — the targeted cleanup test fails with `expected:<1> but was:<0>` intents.
**GREEN XML:** `artifacts/mutation-receipts/green-VoiceAsrEngineTest.xml`
  - tests="1" failures="0" — same targeted test passes with production code.

## Mutant 2: buildCompletedIntent restores A-stamp
**Production file:** `modules/services/voice/src/main/kotlin/.../dialog/VoiceDialogManager.kt` line ~158
**Change:** `mapper.map(call)` replaced with `mapper.map(call)?.withLogicalRequest(dialog.logicalRequestId)`.
**Test:** `begin A complete B carries B on the serialized cloud wire`
**RED XML:** `artifacts/mutation-receipts/mutant-dialog-A-stamp-VoiceDialogManagerTest.xml`
  - tests="1" failures="1" — targeted CloudRoute B test fails with `got aaaaaaaa-... expected null`.
**GREEN XML:** `artifacts/mutation-receipts/green-VoiceDialogManagerTest.xml`
  - tests="1" failures="0" — same targeted test passes with production code.

Both mutants compiled and ran successfully. Failures are assertion-level, not compile errors.
Source restored to production (`aab5df416`) after each run.

## Grace round 2 mutants (spec ruling 7a6a62e3 / reviewer 73ea2ad5)

### Mutant: hold released at sink return (transfer disabled)
**Production file:** `CloudRouteSink.kt` — the finally-block handoff.
**Change:** `if (willSpeak)` replaced with `if (false && willSpeak)` — the hold finishes at sink
return instead of transferring to the renderer path.
**Test:** `pre-Connected refusal joins request ID across transport, persisted log, service and renderer`
**RED XML:** `artifacts/mutation-receipts/hold-transfer-sink-return-RED.xml`
  - tests="1" failures="1" — `the hold must still be pending while output is in flight (survived sink return)`.
**GREEN:** full voice suite BUILD SUCCESSFUL with production restored (transfer in place).

### Mutant: success derived by absence-of-failure
**Production file:** `CloudRouteSink.kt` — the CloudTurnResult predicate.
**Change:** `succeeded = sawDoneCompleted && ...` replaced with `succeeded = !refusedTerminal && endReason == TurnEnded`
— Done-with-Interrupted would pass as success.
**Test:** `interrupted terminal discards output while completed terminal drains it`
**RED XML:** `artifacts/mutation-receipts/positive-success-absence-RED.xml`
  - tests="1" failures="1" — `expected:<false> but was:<true>` for the Interrupted terminal.
**GREEN:** full voice suite BUILD SUCCESSFUL with the positive `sawDoneCompleted` predicate restored.

## Grace round 3 mutants (reviewer 664f82d0 / spec e6cb3052)

### Mutant: earcon hold released at admission instead of consumption
**Production file:** `AudioFeedbackRenderer.kt` — Earcon arm.
**Change:** `watchEarconTerminal(...)` replaced with an immediate release at admission.
**Test:** `an Earcon-only response releases the transferred hold at CONSUMPTION, never at admission`
**RED XML:** `artifacts/mutation-receipts/earcon-admission-release-RED.xml`
  - `the hold must survive mere ADMISSION (queued, not yet sounding)`.
**GREEN:** full voice suite BUILD SUCCESSFUL with the watcher restored.

### Round-3 controls (GREEN, no mutant run)
- TTS failure mid-render: gated failing TTS — hold survives in-flight render, releases at the
  generic failure terminal, window expires.
- Cancel/supersede mid-render: renderer.release() cancels the in-flight invocation; hold releases
  at the cancellation terminal; window expires.
- Local playback failure: drain Unconsumed → feedback Earcon(ERROR) and succeeded=false even with
  Done+Completed (reviewer point 4).

## Grace round 4 (reviewer b25287c6)

### Mutant: never-started supersession orphans the hold
**Production file:** `AudioFeedbackRenderer.kt` — supersession site.
**Change:** the `!prior.started` release removed.
**Test:** `superseding a NEVER-STARTED render releases its transferred hold instead of orphaning it`
**RED XML:** `artifacts/mutation-receipts/never-started-supersession-orphan-RED.xml`
**GREEN:** full voice suite BUILD SUCCESSFUL restored.

### Round-4 changes (GREEN)
- Earcon terminal is INVOCATION-OWNED: admission token and consumed verdict live on
  RenderInvocation; the await runs inside the render job (Earcon-only) or as a job child joined by
  the Combined arm; timeout is NOT consumption — non-consumed exits stop the admission and release
  cancelled; supersession/session-end cancellation reaches it via the job.
- Supersession of a never-started render releases its hold at the site (body catch/finally can
  never run).
- The service's execute-cancellation catch releases any already-transferred hold before rethrowing
  (execute→handoff boundary control included).

## Final boundary-order receipts for grace clearance (reviewer b4fc46a6)

Real executor→renderer controls, each asserting the stopped/cleared-before-release ORDER:

- **Generic failure:** `a TTS failure mid-render releases the transferred hold exactly once`
  (CloudRouteSinkTest) — gated failing TTS; asserts the `submission_failed/render_failed` drop is
  recorded BEFORE the Hold release event. GREEN XML: `green-boundary-ordering-CloudRouteSinkTest.xml`.
- **Session cancellation/disposal:** `a cancelled render before acceptance releases the transferred
  hold via the service` (CloudRouteSinkTest) — renderer.release() mid-render; asserts the
  `render_cancelled` drop precedes the Hold release event, then window expiry, exactly-once. Same GREEN XML.
- **Supersession:** `supersession releases the predecessor hold only after its output terminal is
  recorded` (CloudRouteSinkTest) — real executor→renderer supersession of an in-flight gated render;
  asserts the superseded/cancelled drop precedes the Hold release event. Same GREEN XML.
- **No-handoff/disposal:** renderer-level — a disposed renderer REFUSES ownership (render returns
  false; service releases); never-started supersession releases at its site.
  GREEN XML: `green-boundary-nohandoff-AudioFeedbackRendererTest.xml`.

Mutant receipts: `earcon-admission-release-RED.xml`, `never-started-supersession-orphan-RED.xml`
(now committed at this head), plus round-2 receipts.

## Release-before-clear mutant (reviewer 7a04a952)

### Mutant: NonCancellable cleanup gate disabled
**Production file:** `FakeTtsEngine.speak()` — the controllable output fake.
**Change:** the `clearCompletedGate` await in NonCancellable cleanup is disabled (`if (false && ...)`),
so the fake completes immediately without blocking — a premature-release implementation would pass
the "hold survives while blocked" check trivially.
**Test:** `cancellation holds the grace window until output clear completes, then releases exactly once`
**RED XML:** `artifacts/mutation-receipts/release-before-clear-RED.xml`
  - `cleanup must be requested (stop/clear in flight)` — the control detects the missing block.
**GREEN:** full voice suite BUILD SUCCESSFUL with the gate restored.
