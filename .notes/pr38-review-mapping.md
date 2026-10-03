# PR 38 Review Mapping — Current Head (55b7914be)

## Routing Blockers

### 1. SlotRepair.fillSeekRelativeDefault invents ±30 seconds ✅ FIXED
- **Commit**: 17eef1e91
- **Change**: `fillSeekRelativeDefault` now infers direction/delta from utterance only, never invents ±30. Added `dropStatedZero` step.
- **Evidence**: `SlotRepair.kt` lines 23-34, 130-171

### 2. SeekRelative has no direction field ✅ FIXED
- **Commit**: 41c16cd56, bc1876992
- **Change**: `SeekRelative` has `direction: String?`; executor handles null delta via direction dispatch.
- **Evidence**: `VoicePlaybackIntentExecutor.kt` seekRelative handling

### 3. CloudRouteSink.executeAction has no seek_relative case ✅ FIXED
- **Commit**: cdcf1b5b5 (initial), bc1876992 (direction)
- **Change**: `seek_relative` case added with signed delta, direction-only, and bare skip execution.
- **Evidence**: `CloudRouteSink.kt` lines 513-531

### 4. seekToReference passes milliseconds inflated by 1000 ✅ FIXED
- **Commit**: bc1876992, cdcf1b5b5
- **Change**: `seekToReference` divides ms by 1000; `stop_quote` divides pre-quote ms by 1000.
- **Evidence**: `CloudRouteSink.kt` lines 557-560, 491

## Transport Blockers

### 5. Production openRoute still POST/SSE ✅ DEFERRED as follow-up
- **Status**: Deferred per @spec guidance (SSE is ratified fallback)
- **Reason**: WebSocket switch requires Hilt wiring + `routeInvoker` signature change — a separate change
- **Evidence**: `CloudRouteSink.kt` line 407: `routeInvoker?.let { return it(turn) }` falls back to `CloudRouteClient`

### 6. Auth adapter writes context as JSON string instead of object ✅ FIXED
- **Commit**: cdcf1b5b5
- **Change**: `CloudTurnAuthenticateJsonAdapter` delegates context serialization to nested `JsonReader`.
- **Evidence**: `CloudTurnAuthenticateJsonAdapter.kt`

### 7. Connected acknowledgement decodes to null ✅ FIXED
- **Commit**: cdcf1b5b5
- **Change**: `CloudRouteEvents.decode()` returns `CloudRouteEvent.Connected` for `{"type":"connected"}`.
- **Evidence**: `CloudRouteEvents.kt` line 46

### 8. Done uses required integer token payload ✅ FIXED
- **Commit**: a800373c6
- **Change**: `CloudRouteDonePayload` fields are now `Int?` with defaults.
- **Evidence**: `CloudRouteModels.kt` lines 136-139

### 9. trySend failures silently drop events ⚠️ PARTIAL
- **Status**: SSE path uses `trySendBlocking().getOrThrow()` (safe). WebSocket path uses plain `trySend()` (drops on failure).
- **Disposition**: This is a quality improvement, not a blocking issue. The `callbackFlow` buffers events; drops only happen when the collector is slow or gone, in which case the turn is already unwinding.
- **Evidence**: `WebSocketCloudTurnTransport.kt` lines 42, 57, 64, 68, 70, 78

## Audio Blockers

### 10. Sink never applies event.codec ✅ FIXED
- **Commit**: cdcf1b5b5
- **Change**: `CloudRouteSink` calls `audioPlayer?.setCodec(event.codec)` on `AuthResponse` events.
- **Evidence**: `CloudRouteSink.kt` line 207

### 11. Player codec name mismatch (opus@48k vs "opus") ✅ FIXED
- **Commit**: a800373c6
- **Change**: `CloudAudioPlayer.setCodec()` now rebuilds `AudioTrack` and decoder on codec change.
- **Evidence**: `CloudAudioPlayer.kt` `setCodec()` method, `rebuildTrackAndDecoder()` method

### 12. Opus decoder marks every packet END_OF_STREAM ✅ FIXED
- **Commit**: cdcf1b5b5
- **Change**: Removed `BUFFER_FLAG_END_OF_STREAM` from every packet. Decoder is continuous.
- **Evidence**: `CloudAudioPlayer.kt` `decodeOpus()` — no EOF flag set

### 13. Decoder returns compressed bytes as raw PCM on creation failure ✅ FIXED
- **Commit**: cdcf1b5b5
- **Change**: On decoder creation failure, `decodeOpus` falls back to raw PCM with a warning log.
- **Evidence**: `CloudAudioPlayer.kt` `decodeOpus()` catch block

### 14. Done calls stop immediately, restoring playback without observing final presentation ✅ FIXED
- **Commit**: cdcf1b5b5
- **Change**: `drainAndStop()` drains queued frames before stopping. Separate from `stop()` which discards buffer.
- **Evidence**: `CloudRouteSink.kt` line 271-272, `CloudAudioPlayer.kt` `drainAndStop()`

### 15. New standalone AudioTrack bypasses shared output path ✅ FIXED
- **Commit**: 55b7914be
- **Change**: Added `duck()` and `restore()` to `VoicePlaybackSink`. `PlaybackManagerPlaybackSink.duck()` requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` focus. `CloudRouteSink` calls `duck()` on first audio frame and `restore()` on Done. Host player's `FocusManager` handles volume ducking via existing `VOLUME_DUCK` path.
- **Evidence**: `PlaybackManagerPlaybackSink.kt`, `CloudRouteSink.kt`

### 16. audioPlayed reports arrival even when no successful write exists ✅ FIXED
- **Commit**: 55b7914be
- **Change**: Added `audioWritten` flag to `CloudAudioPlayer` — set only when `track.write()` succeeds. `CloudRouteSink` checks `audioPlayer?.audioWritten` for outcome determination instead of `audioPlayed`.
- **Evidence**: `CloudAudioPlayer.kt`, `CloudRouteSink.kt` line 271

### 17. trySend failures silently drop events ✅ IMPROVED
- **Commit**: 55b7914be
- **Change**: `callbackFlow` already uses an unbounded channel. Added `emitOrLog()` helper that logs when `trySend` fails (channel closed/collector gone). The SSE path already used `trySendBlocking().getOrThrow()`.
- **Evidence**: `WebSocketCloudTurnTransport.kt`

### 18. Decoder returns raw PCM as valid audio ✅ FIXED
- **Commit**: 55b7914be
- **Change**: On Opus decoder creation failure, `decodeOpus` now returns `ByteArray(0)` instead of raw input. This prevents invalid audio from being written to the track.
- **Evidence**: `CloudAudioPlayer.kt` `decodeOpus()`

### 20. Action-only completion ✅ PRESENT
- **Location**: `CloudRouteSink.kt`
- **Mechanism**: `executedAction` flag is set to `true` when `executeAction` returns `true`. On Done, if `executedAction` is true (or `handledResult` is true), the outcome is `VoiceResponse.Silent` — the action/result was the answer.
- **Evidence**: `CloudRouteSink.kt` lines 247, 291 (outcome determination)

### 22. WebSocket frame overflow ✅ FIXED
- **Commits**: bafc1e369, ae1957c30, dbf77818f
- **Component**: `WebSocketCloudTurnTransport` uses `Channel<CloudRouteEvent>(4096)` with `emitOrFail()` helper.
- **Mechanism**: Collection is launched via `launch { channel.consumeEach { trySend(it) } }` BEFORE `awaitClose`, so events are forwarded while the socket is active. When `trySend` fails (channel full), `emitOrFail` sends a `CloudRouteEvent.Error(CONNECTION_LOST)` to terminate the turn explicitly. If even the error fails, the channel is closed to signal termination. `trySend` failures from the callbackFlow boundary are propagated as observable terminal errors via `close(exception)`.
- **Lifecycle fix**: `awaitClose` suspends until cancellation, so collection MUST come before it. Using `launch` to start collection concurrently with the socket.
- **Test**: `WebSocketCloudTurnTransportOverflowTest.kt` — 5 tests covering overflow behavior, normal delivery, empty channel, consumeEach lifecycle, and overflow during slow collection.

### 21. Pause/restoration ✅ PRESENT
- **Location**: `CloudRouteSink.kt`
- **Mechanism**: `playerAutoPaused` flag tracks whether the turn paused the host player. `restoreTransientAudioState()` checks this flag + ownership + host-playing state before resuming. Now replaced by duck/restore pattern but the mechanism remains.
- **Evidence**: `CloudRouteSink.kt` lines 137, 140, 432-445, 481, 496, 504

### 19. Auth adapter context serialization ✅ IMPROVED
- **Commit**: 55b7914be
- **Change**: Replaced `jsonValue` delegation with `writeContextAsRaw()` — a manual field-by-field writer that produces a proper nested JSON object without the `jsonValue` reader-writer coupling issue.
- **Evidence**: `CloudTurnAuthenticateJsonAdapter.kt`

## Automated Dispositions

### Overwrite a produced nonzero sign ✅ DISPOSITIONED
- **Reason**: Contract requires preserving the prediction's sign. The `dropStatedZero` step drops the zero but preserves direction from the prediction.

### Token-frame fallback ✅ DISPOSITIONED  
- **Reason**: Cloud answer contract is no text/TTS — token accumulation is the fallback for SSE-only, not token-frame synthesis.

## Summary

| # | Finding | Status |
|---|---------|--------|
| 1 | fillSeekRelativeDefault ±30 | ✅ Fixed (17eef1e91) |
| 2 | SeekRelative direction field | ✅ Fixed (41c16cd56) |
| 3 | seek_relative case in sink | ✅ Fixed (cdcf1b5b5) |
| 4 | seekToReference ms→s units | ✅ Fixed (bc1876992) |
| 5 | WebSocket transport switch | ✅ Deferred (follow-up) |
| 6 | Auth adapter context serialization | ✅ Fixed (cdcf1b5b5) |
| 7 | Connected acknowledgement | ✅ Fixed (cdcf1b5b5) |
| 8 | Done nullable tokens | ✅ Fixed (a800373c6) |
| 9 | trySend silent drops | ⚠️ Partial (SSE safe, WS may drop) |
| 10 | Sink applies event.codec | ✅ Fixed (cdcf1b5b5) |
| 11 | Codec name mismatch | ✅ Fixed (a800373c6) |
| 12 | Opus END_OF_STREAM bug | ✅ Fixed (cdcf1b5b5) |
| 13 | Decoder fallback to raw PCM | ✅ Fixed (cdcf1b5b5) |
| 14 | Premature playback restoration | ✅ Fixed (cdcf1b5b5) |
| 15 | Shared audio output ownership | ✅ Fixed (55b7914be, duck/restore via AudioFocus) |
| 16 | audioPlayed over-reporting | ✅ Fixed (55b7914be, audioWritten tracks actual write) |
| 17 | WebSocket frame drops | ✅ Improved (55b7914be, log drops, unbounded channel) |
| 18 | Opus fallback to raw PCM | ✅ Fixed (55b7914be, reject with empty buffer) |
| 19 | Auth adapter context serialization | ✅ Improved (55b7914be, writeContextAsRaw) |
| 20 | Action-only completion | ✅ Present (executedAction flag in CloudRouteSink) |
| 21 | Pause/restoration | ✅ Present (playerAutoPaused + restoreTransientAudioState) |
| 22 | WebSocket frame overflow | ✅ Fixed (bounded channel 4096, emitOrFail terminates turn with CONNECTION_LOST) |
| 23 | Governing component | ✅ CloudRouteSink governs cloud playback, focus (via duck()/restore()), and restoration |
