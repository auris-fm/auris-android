# Evidence — round 18 (reviewer 3776b651)

EXACT TESTED HEAD: c77e076b3f454d0a38eb687768daf6d3ae8de9b2 (branch android-voice-upload, worktree /Users/dev/code/auris/auris-android/android-voice-upload, PR #57)

SCOPE: SyncManagerImplCredentialFlowTest only (per instruction: no broad suite rerun). Raw JUnit XML in this directory (GREEN/RESTORED run).

- PASS: P1 - legitimate pre-request refresh MOVES the credential - password commit still lands
- PASS: P5 - SAME-account relogin DURING token acquisition refuses - session fence is NECESSARY
- PASS: P3 - refused admission in the real fetch flow issues ZERO network calls
- PASS: P4 - identity switch DURING token acquisition refuses BEFORE the password request
- PASS: P2 - late A password response after B login REFUSES and throws - B credential intact

NAMED TOTAL: 5 real-flow controls, 0 failures, 0 errors.

## NECESSITY demonstration (origin-session fence)

RED run (session fence removed, name check only): P5 - SAME-account relogin DURING token acquisition refuses - session fence is NECESSARY → FAILED. Raw XML preserved at evidence/round18-red/ (committed in the same push).

RESTORED run (fence back): all P1-P5 PASS (this directory).

## Source changes (exact refs at this head)
- SyncManagerImpl.kt updatePassword: refusal messages carry NO account names / session metadata.
- SyncManagerImplCredentialFlowTest.kt: P4 rewritten to drive the SAME injected owner instance (managerWithAcquisitionSwitch helper); P5 added (same-account relogin).

## Scoped verification at this head (exit 0)
- repositories compileDebugKotlin; :app:kspDebugKotlin (full Hilt); spotlessCheck.