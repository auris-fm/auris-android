# Evidence — round 19 (reviewer 01464744)

EXACT TESTED HEAD: bd8cdd5fb1627fcfc7758bea9c06ab301dd6a5f1 (branch android-voice-upload, worktree /Users/dev/code/auris/auris-android/android-voice-upload, PR #57)

SCOPE: SyncManagerImplCredentialFlowTest P1-P5 + P5 name-only mutant/restored (per instruction: no broad rerun).

## GREEN/RESTORED run (this directory)

- PASS: P1 - legitimate pre-request refresh MOVES the credential - password commit still lands
- PASS: P5 - SAME-account relogin DURING token acquisition refuses - session fence is NECESSARY
- PASS: P3 - refused admission in the real fetch flow issues ZERO network calls
- PASS: P4 - identity switch DURING token acquisition refuses BEFORE the password request
- PASS: P2 - late A password response after B login REFUSES and throws - B credential intact

NAMED TOTAL: 5 controls, 0 failures, 0 errors.

## MUTANT run (name-only; session fence removed): P5 FAILS — raw XML at evidence/round19-red/ (committed same push). This is the necessity receipt for the original-session fence.

## PREMISE closure (01464744)
- managerWithAcquisitionSwitch returns (manager, injectedOwner); the switch lambda runs with the injectedOwner as EXPLICIT receiver; lifecycle uses the FIVE-argument REAL addAccount(email, uuid, refreshToken, accessToken, loginIdentity); P4/P5 final authority readbacks use injectedOwner — no test-helper two-arg addAccount, no implicit-receiver binding to the outer fixture.

## Source at this head
- Refusal messages metadata-free; admission validated vs ORIGINAL session + request token BEFORE password HTTP (SyncManagerImpl.kt updatePassword, unchanged from round-18 source closure).

## Scoped verification (exit 0): repositories compileDebugKotlin; :app:kspDebugKotlin (full Hilt); spotlessCheck.