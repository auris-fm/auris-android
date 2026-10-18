# Evidence — round 20 (reviewer 920cda83)

EXACT TESTED HEAD: 16312293325ec8e527e3e37ff85d36e507e74a3b (branch android-voice-upload, worktree /Users/dev/code/auris/auris-android/android-voice-upload, PR #57)

SCOPE: CredentialFlow P1-P5 + P5 name-only mutant/restored (per instruction). No production changes this round.

## SETUP premise closure
- In P4 and P5 the pair (manager, injectedOwner) is created FIRST; the initial seed is injectedOwner.addAccount(email, uuid, refreshToken, accessToken, loginIdentity) — the FIVE-arg REAL method via the EXPLICIT reference. The outer fixture owner does NOT seed these tests (outer helper call deleted from both).
- BEFORE updatePassword each test asserts on injectedOwner: published token == seed token, retained account == seed email, sessionGeneration > 0 (ARMED).
- Read back from the test source at this head before this report; the full setup is visible in the committed test file.

## GREEN/RESTORED run (this directory)

- PASS: P1 - legitimate pre-request refresh MOVES the credential - password commit still lands
- PASS: P5 - SAME-account relogin DURING token acquisition refuses - session fence is NECESSARY
- PASS: P3 - refused admission in the real fetch flow issues ZERO network calls
- PASS: P4 - identity switch DURING token acquisition refuses BEFORE the password request
- PASS: P2 - late A password response after B login REFUSES and throws - B credential intact

NAMED TOTAL: 5 controls, 0 failures, 0 errors.

## MUTANT run (name-only): P5 FAILS — raw XML at evidence/round20-red/ (committed same push).

## Scoped verification (exit 0): repositories compileDebugKotlin; :app:kspDebugKotlin (full Hilt); spotlessCheck.