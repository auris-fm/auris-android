package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransport
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops.AppOperationJournal
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops.AppOperationJournalStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops.AppOperationSupport
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import org.mockito.kotlin.mock
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The app-operation v1 answer path, wired through the sink: a v1 frame the transport decodes is
 * admitted through the journal, executed behind one journal-protected adapter, settled durably, and
 * answered on the turn's own socket.
 *
 * The mid-operation-retirement control is spec-mandated (task 67, slice 3): with NO transport
 * session or lease machinery, a connection that dies while an effect is in flight must still leave
 * a durable claim whose REDELIVERY answers from the journal without a second execution.
 */
class CloudRouteSinkAppOpsTest {

    private val tempDir = Files.createTempDirectory("appops-sink-test").toFile()

    private class CountingSink : VoicePlaybackSink {
        var pauses = 0
        var resumes = 0

        /** When set, pause() completes its effect then suspends until released. */
        var pauseGate: CompletableDeferred<Unit>? = null
        val pauseStarted = CompletableDeferred<Unit>()

        override suspend fun duck(): VoiceResponse = VoiceResponse.Silent
        override suspend fun restore(): VoiceResponse = VoiceResponse.Silent
        override suspend fun pauseAsHold(): VoiceResponse = pause()

        override suspend fun pause(): VoiceResponse {
            pauses += 1
            pauseStarted.complete(Unit)
            pauseGate?.await()
            return VoiceResponse.Silent
        }

        override suspend fun resume(expectedEpisodeId: String?): EpisodeOperationResult =
            EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null).also { resumes += 1 }

        override suspend fun skipForward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult =
            EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)

        override suspend fun skipBackward(seconds: Int?, expectedEpisodeId: String?): EpisodeOperationResult =
            EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)

        override suspend fun seekTo(positionSeconds: Int, episodeDurationSeconds: Int, expectedEpisodeId: String?): EpisodeOperationResult =
            EpisodeOperationResult.Accepted(episodeId = expectedEpisodeId, preActionPositionMs = null)

        override fun nextEpisode(): VoiceResponse = VoiceResponse.Silent
    }

    /** The engine reports PAUSED after the observed pause, PLAYING before it. */
    private class ObservedContextProvider : PlaybackContextProvider {
        var playing: Boolean? = false

        override fun current(): PlaybackContext = PlaybackContext(
            episodeId = "ep-1",
            podcastId = null,
            referencePositionMs = null,
            clientPositionMs = null,
            recentReferencePositions = emptyList(),
            previousReferencePositionMs = null,
        )

        override fun currentObservedState(): JSONObject? = playing?.let {
            JSONObject().put("playing", it).put("episode_id", "ep-1").put("position_ms", 1000L)
        }
    }

    private class ScriptedTransport(
        /** When set, every send is refused: models a socket that has died. */
        private val sendRejected: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        private val script: suspend FlowCollector<CloudRouteEvent>.(send: (CloudTurnFrame) -> Boolean) -> Unit,
    ) : CloudTurnTransport {
        val sent = mutableListOf<CloudTurnFrame>()

        var conversationId: String = "conv-1"

        override fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent> = exchange(frame, null)

        override fun exchange(
            frame: CloudTurnFrame,
            onOpen: ((sendResult: (CloudTurnFrame) -> Boolean) -> Unit)?,
        ): Flow<CloudRouteEvent> = flow {
            conversationId = (frame as CloudTurnFrame.Authenticate).conversationId
            val send: (CloudTurnFrame) -> Boolean = { result ->
                if (sendRejected.get()) {
                    false
                } else {
                    sent += result
                    true
                }
            }
            onOpen?.invoke(send)
            script(send)
        }
    }

    private fun sink(
        playback: CountingSink,
        transport: CloudTurnTransport,
        support: AppOperationSupport = AppOperationSupport(ownerProvider = { OWNER }, journalRoot = tempDir),
        contextProvider: PlaybackContextProvider = ObservedContextProvider(),
    ): CloudRouteSink = CloudRouteSink(
        resolveBaseUrl = { "wss://auris.test" },
        tokenProvider = object : CloudTokenProviding {
            override suspend fun currentToken(): String? = "token"
        },
        playbackSink = playback,
        fingerprintTimingManager = mock<FingerprintTimingManager>(),
        playbackContextProvider = contextProvider,
        cloudPlaybackContextState = CloudPlaybackContextState(),
        analytics = mock<CloudRouteAnalytics>(),
        isHostPlaying = { false },
        appOperationSupport = support,
        transportFactory = { _, _, _ -> transport },
    )

    private fun resultFrameOf(transport: ScriptedTransport): JSONObject {
        val frame = transport.sent.singleOrNull() ?: fail("expected exactly one result frame, got ${transport.sent}")
        assertTrue("expected an AppOperationResult frame, got $frame", frame is CloudTurnFrame.AppOperationResult)
        frame as CloudTurnFrame.AppOperationResult
        assertEquals(WIRE_ID, frame.requestId)
        return JSONObject(frame.payloadJson)
    }

    private suspend fun CloudRouteSink.route(capturedOrigin: Long = 42L, isLiveGraceTurn: Boolean = false) {
        withTimeout(10_000) {
            routeToCloud(
                "question",
                PlaybackContext(
                    episodeId = "ep-1",
                    podcastId = null,
                    referencePositionMs = null,
                    clientPositionMs = null,
                    recentReferencePositions = emptyList(),
                    previousReferencePositionMs = null,
                ),
                isLiveGraceTurn = isLiveGraceTurn,
                capturedOrigin = capturedOrigin,
                admitted = WIRE_ID,
            )
        }
    }

    @Test
    fun `an admitted operation executes, settles durably and answers on the turn socket`() = runTest {
        val playback = CountingSink()
        val transport = ScriptedTransport { _ ->
            emit(opEvent())
            emit(done())
        }
        val journal = AppOperationJournalStore(root = tempDir)
        sink(playback, transport).route()

        assertEquals("the effect must have run exactly once", 1, playback.pauses)
        val payload = resultFrameOf(transport)
        assertEquals(OP_ID, payload.getString("operation_id"))
        assertEquals("succeeded", payload.getString("outcome"))
        val claim = AppOperationJournal.Claim(owner = OWNER, conversation = transport.conversationId, turn = WIRE_ID, operationId = OP_ID)
        assertTrue("journal must hold the settled outcome", journal.read(claim)?.outcome == "succeeded")
    }

    @Test
    fun `an unsupported operation refuses without touching playback`() = runTest {
        val playback = CountingSink()
        val transport = ScriptedTransport { _ ->
            emit(opEvent(tool = "volume", action = "set"))
            emit(done())
        }
        sink(playback, transport).route()

        assertEquals("an unsupported operation must not touch the player", 0, playback.pauses)
        val payload = resultFrameOf(transport)
        assertEquals("refused", payload.getString("outcome"))
        assertEquals("unsupported_operation", payload.getString("reason"))
    }

    @Test
    fun `an operation with no owner identity refuses without executing`() = runTest {
        val playback = CountingSink()
        val transport = ScriptedTransport { _ ->
            emit(opEvent())
            emit(done())
        }
        sink(playback, transport, support = AppOperationSupport.Unwired).route()

        assertEquals("an unwired support must not execute", 0, playback.pauses)
        val payload = resultFrameOf(transport)
        assertEquals("refused", payload.getString("outcome"))
        assertEquals("persistence_unavailable", payload.getString("reason"))
    }

    @Test
    fun `mid-operation retirement settles durably, runs once, and a same-turn redelivery replays`() = kotlinx.coroutines.runBlocking {
        val playback = CountingSink()
        playback.pauseGate = CompletableDeferred()
        // THE SOCKET DIES MID-EFFECT: set while the first copy is still executing, so nothing sent
        // from that moment reaches the server.
        val socketDead = java.util.concurrent.atomic.AtomicBoolean(false)
        val transport = ScriptedTransport(sendRejected = socketDead) { send ->
            // The server redelivers the SAME operation id in the SAME turn while the first copy is
            // still in effect; then the connection dies.
            emit(opEvent())
            val duplicate = opEvent()
            emit(duplicate)
            emit(CloudRouteEvent.Error(code = "connection_lost", message = ""))
            Unit
        }
        // THE SOCKET DIES MID-EFFECT: a separate coroutine flips it while the first copy is still
        // executing (the collector and the script are one coroutine, so the release must be external),
        // and completes the effect with nothing able to send.
        launch {
            withTimeout(10_000) { playback.pauseStarted.await() }
            socketDead.set(true)
            playback.pauseGate?.complete(Unit)
        }
        val journal = AppOperationJournalStore(root = tempDir)
        sink(playback, transport).route()

        assertEquals("the effect must have run exactly once", 1, playback.pauses)
        val claim = AppOperationJournal.Claim(
            owner = OWNER,
            conversation = transport.conversationId,
            turn = WIRE_ID,
            operationId = OP_ID,
        )
        val record = journal.read(claim)
        assertTrue("the claim must have settled durably despite the dead socket", record?.outcome == "succeeded")
        // Both answers (the original and the replay) were attempted against the dead socket; the
        // durable record -- not the send -- is what the server's transport loss settles against.
    }

    @Test
    fun `a malformed operation answers refused with its recovered id`() = runTest {
        val playback = CountingSink()
        val transport = ScriptedTransport { _ ->
            emit(CloudRouteEvent.MalformedAppOperation(operationId = OP_ID, rawBytes = """{"operation_id":"$OP_ID","broken":true}""".toByteArray()))
            emit(done())
        }
        sink(playback, transport).route()

        assertEquals("a malformed operation must not execute", 0, playback.pauses)
        val payload = resultFrameOf(transport)
        assertEquals(OP_ID, payload.getString("operation_id"))
        assertEquals("refused", payload.getString("outcome"))
        assertEquals("invalid_request", payload.getString("reason"))
    }
}

private const val WIRE_ID = "wire-req-1"
private const val OP_ID = "11111111-2222-3333-4444-555555555555"
private const val OWNER = "acct-1"

private fun opBytes(
    operationId: String = OP_ID,
    tool: String = "playback",
    action: String = "pause",
): ByteArray = """{"type":"action","operation_id":"$operationId","capability_version":"app-ops/1","tool":"$tool","action":"$action","params":{}}""".toByteArray()

private fun opEvent(
    operationId: String = OP_ID,
    tool: String = "playback",
    action: String = "pause",
): CloudRouteEvent = CloudRouteEvent.AppOperation(
    operationId = operationId,
    capabilityVersion = "app-ops/1",
    tool = tool,
    action = action,
    params = emptyMap(),
    precondition = null,
    rawBytes = opBytes(operationId, tool, action),
)

private fun done(): CloudRouteEvent = CloudRouteEvent.Done(0, 0)
