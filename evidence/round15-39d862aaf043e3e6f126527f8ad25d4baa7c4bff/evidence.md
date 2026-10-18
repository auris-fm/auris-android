# Evidence — voice-sharing account boundary, round 15 (reviewer d8adfb46)

EXACT TESTED HEAD: 39d862aaf043e3e6f126527f8ad25d4baa7c4bff (branch android-voice-upload, worktree /Users/dev/code/auris/auris-android/android-voice-upload, PR #57)

SCOPE: focused account controls only (:modules:services:voice testDebugUnitTest, package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.*). Raw JUnit XML preserved in this directory.

| Suite | tests | failures | errors |
|---|---|---|---|
| au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountTransitionAtomicityTest | 2 | 0 | 0 |
| au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountSessionCoordinatorTest | 3 | 0 | 0 |
| au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountOperationsFencingTest | 20 | 0 | 0 |
| au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountAuthBindingTest | 5 | 0 | 0 |
| au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountOperationsTest | 8 | 0 | 0 |
| au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountRealOwnerTest | 19 | 0 | 0 |
| au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStateTest | 26 | 0 | 0 |

NAMED TOTAL: 83 focused account controls, 0 failures, 0 errors across 7 suite XMLs.

## Controls (named, in VoiceSharingAccountRealOwnerTest.kt)

- T6:79 committed 401 destroys REAL published token, credential advanced EXACTLY once
- T1:125 late-A refresh after B login refused, never writes into B
- T2:143 same-account relogin fences OLD session apply, arms NEW incarnation
- T3:167 install seeds binding UNDER owner lock, fail-closed without account
- T4:181 genuine refresh PRESERVES session incarnation
- T5:201 delayed platform cache copy of stale token NEVER becomes authority
- T7:221 same-session out-of-order refresh completion cannot overwrite newer credential
- T8:236 refused publication hands NO token back
- R1:277 account switch BEFORE admission refused — zero network (SyncManagerImpl.kt:179 throws before downloadTokens; fallback inputs deleted)
- R2:293 external REPLACEMENT reconciles FIRST in every authority transaction; retained-vs-observed comparison (SyncAccountManagerImpl.kt:93-103 reconcileLocked), never B-name-with-A-token
- R3:324 removal revokes session INDEPENDENT of token presence
- R4:345 install ONCE bootstrap; rebinding seeds ledger; null seed REVOKES
- R5:365 publishCredentials commits BOTH tokens in ONE credential move (SyncAccountManagerImpl.kt:56-84)
- R6:379 same-account RELOGIN before admission REFUSES (beginRefresh(observed) session validation, SyncAccountManagerImpl.kt:153-171)
- R7:394 late A password change after B login REFUSES — zero writes (updatePassword callsite SyncManagerImpl.kt:141-151)
- R8:410 rebinding = ATOMIC authority transfer under owner lock (SyncAccountManagerImpl.kt:120-136)
- R9:435 NAMED closed-session identity replacement control: retained credential voided on next authority read while session CLOSED (reconcileLocked unconditional clear, SyncAccountManagerImpl.kt:93-103)

## RED → RESTORED evidence (read back from this round's runs)
- REGRESSION FOUND AND FIXED THIS ROUND: the rebind branch edit dropped the first-install  registration (now SyncAccountManagerImpl.kt:136); 7 controls went RED (signOut-sync, R2, R3, external-removal, T2, R8, R1) in the intermediate run and are GREEN at this head. R8 initially NPE'd/failed on two bogus assertions; corrected to ledger/state assertions.

## Scoped verification at this head (exit codes, run in this worktree)
- :modules:services:repositories:testDebugUnitTest --tests repositories.sync.* → exit 0
- :app:kspDebugKotlin (full Hilt component build) → exit 0
- spotlessCheck → exit 0

Closed policy/parser/outcomes boundaries untouched.