# Evidence — round 16 (reviewer 539a7d64)

EXACT TESTED HEAD: 6ef6cd0c61988011bf12594fd5d06df0715af825 (branch android-voice-upload, worktree /Users/dev/code/auris/auris-android/android-voice-upload, PR #57)

SCOPE: focused controls — repositories sync suite + voice upload.account package. Raw JUnit XML in this directory (immutable run artifacts from the runs at this head).

| Suite | tests | failures | errors |
|---|---|---|---|
| VoiceSharingAccountTransitionAtomicityTest | 2 | 0 | 0 |
| VoiceSharingAccountSessionCoordinatorTest | 3 | 0 | 0 |
| VoiceSharingAccountOperationsFencingTest | 20 | 0 | 0 |
| AccountManagerStatusInfoTest | 7 | 0 | 0 |
| PocketCastsAccountAuthenticatorTest | 4 | 0 | 0 |
| PodcastRefresherImplTest | 14 | 0 | 0 |
| StarredSyncTest | 4 | 0 | 0 |
| SyncManagerImplCredentialFlowTest | 3 | 0 | 0 |
| SyncManagerImplTest | 5 | 0 | 0 |
| UpNextSyncTest | 23 | 0 | 0 |
| VoiceSharingAccountAuthBindingTest | 5 | 0 | 0 |
| VoiceSharingAccountOperationsTest | 8 | 0 | 0 |
| VoiceSharingAccountRealOwnerTest | 19 | 0 | 0 |
| VoiceSharingAccountStateTest | 26 | 0 | 0 |

NAMED TOTAL: 143 controls, 0 failures, 0 errors.

## REAL request-flow controls (SyncManagerImplCredentialFlowTest — NEW, reviewer 539a7d64)
- P1 legitimate pre-request owner-lease refresh through the REAL SyncManagerImpl fetch flow (platform cache invalidated; real Retrofit over MockWebServer): credential 1→2, then password commit LANDS (t3, credential 3).
- P2 password response arrives AFTER a real B platform swap during the request: refusal EXPLICIT (IllegalStateException 'refused'), B credential intact, A tokens never written.
- P3 refused admission in the REAL getAccessToken flow: RefreshTokenExpiredException and server.requestCount == 0 — the actual zero-network fetch receipt.

## Changed source (exact refs at this head)
- updatePassword admission AFTER token acquisition + explicit refusal: modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/sync/SyncManagerImpl.kt:141-158 (check{} at :152-154).
- Admission session validation: SyncAccountManagerImpl.kt:153-171 beginRefresh(observed).

## Scoped verification at this head (exit 0 each)
- :modules:services:repositories:testDebugUnitTest --tests repositories.sync.*
- :modules:services:voice:testDebugUnitTest --tests ...upload.account.*
- :app:kspDebugKotlin (full Hilt build)
- spotlessCheck

Prior-round artifacts preserved at evidence/round15-39d862aaf043e3e6f126527f8ad25d4baa7c4bff/ (83 controls, T1-T8/R1-R9 named map).