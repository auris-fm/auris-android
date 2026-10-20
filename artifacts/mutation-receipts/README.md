# Mutation Receipts for android-utterance-identity

All XML files are raw JUnit test-results from actual gradle runs at head `aab5df416`.
Source was restored to production after each mutant run; green XML is from the same code.

## Mutant 1: hasLetterOrNumber always-true
**Production file:** `modules/services/voice/src/main/kotlin/.../engine/VoiceAsrEngine.kt` line 229
**Change:** Body replaced with `return true` (guard bypassed).
**Test:** `a punctuation-only discard followed by content on the same engine routes the second`
**RED XML:** `artifacts/mutation-receipts/mutant-hasLetterOrNumber-alwaysTrue-VoiceAsrEngineTest.xml`
  - tests="47" failures="1" — the cleanup test fails with `expected:<1> but was:<0>` intents.
**GREEN XML:** `artifacts/mutation-receipts/green-VoiceAsrEngineTest.xml`
  - tests="47" failures="0" — same test passes with production code.

## Mutant 2: buildCompletedIntent restores A-stamp
**Production file:** `modules/services/voice/src/main/kotlin/.../dialog/VoiceDialogManager.kt` line ~158
**Change:** `mapper.map(call)` replaced with `mapper.map(call)?.withLogicalRequest(dialog.logicalRequestId)`.
**Test:** `begin A complete B carries B on the serialized cloud wire`
**RED XML:** `artifacts/mutation-receipts/mutant-dialog-A-stamp-VoiceDialogManagerTest.xml`
  - tests="14" failures="1" — CloudRoute test fails with `got aaaaaaaa-... expected null`.
**GREEN XML:** `artifacts/mutation-receipts/green-VoiceDialogManagerTest.xml`
  - tests="14" failures="0" — same test passes with production code.

Both mutants compiled and ran successfully. Failures are assertion-level, not compile errors.
Source restored to production (`aab5df416`) after each run.
