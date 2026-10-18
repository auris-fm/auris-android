# Evidence — round 17 (reviewer 83471c65)

EXACT TESTED HEAD: d6870cff9103e5818c702b6d81c9cf10cd868e09 (branch android-voice-upload, worktree /Users/dev/code/auris/auris-android/android-voice-upload, PR #57)

SCOPE: SyncManagerImplCredentialFlowTest only (per instruction: no repeat of the unchanged 143-control suite). Raw JUnit XML in this directory.

- PASS: P1 - legitimate pre-request refresh MOVES the credential - password commit still lands
- PASS: P3 - refused admission in the real fetch flow issues ZERO network calls
- PASS: P4 - identity switch DURING token acquisition refuses BEFORE the password request
- PASS: P2 - late A password response after B login REFUSES and throws - B credential intact

NAMED TOTAL: 4 real-flow controls, 0 failures, 0 errors.

## Source change (exact ref at this head)
- modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/sync/SyncManagerImpl.kt:141-165 — original identity BEFORE acquisition; admission validated vs original session + actual request token BEFORE the password HTTP; frozen through the commit; explicit refusals.

## Scoped verification at this head (exit 0)
- repositories compileDebugKotlin + compileDebugUnitTestKotlin
- :app:kspDebugKotlin (full Hilt build)
- spotlessCheck

Unchanged suites intentionally NOT rerun (per 83471c65); their preserved artifacts: evidence/round16-6ef6cd0c6*/ (143 controls 0/0/0).