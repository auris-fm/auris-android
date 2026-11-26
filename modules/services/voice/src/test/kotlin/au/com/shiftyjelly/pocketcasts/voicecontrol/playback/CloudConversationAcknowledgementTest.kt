package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnFrame
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnTransport
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A conversation becomes RESUMABLE only once the server has acknowledged it with `connected`.
 *
 * The handle is client-generated, so nothing stops this client from resuming a conversation the worker
 * never created. A create whose exchange failed before `connected` leaves the worker with no such
 * conversation, and a follow-up that RESUMES it asks to continue something that does not exist. The
 * rule in the shared contract is therefore: after a create that ended without `connected`, the next
 * follow-up CREATES with a fresh id. Retries of the same logical request are unaffected -- they reuse
 * the retained turn, so their id, mode and request identity stay identical.
 *
 * These cases drive the REAL sink through the same entry point production uses, so what is pinned is
 * the frame that would go on the wire, not an admission object's fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudConversationAcknowledgementTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val createWireId = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    private val followUpWireId = "7c9e6679-7425-40b0-944f-9a3f2c7c0e11"

    /** The conversation pair each dispatched frame carried, in dispatch order. */
    private class Dispatched(val conversationId: String?, val mode: CloudConversationMode?)

    private fun sink(
        signal: GracePeriodSignal,
        origin: Long,
        admission: CloudConversationAdmission = CloudConversationAdmission().apply { beginOrigin(origin) },
        route: (CloudRouteTurn) -> Flow<CloudRouteEvent>,
    ) = CloudRouteSink(
        transportFactory = { _, _, _ ->
            object : CloudTurnTransport {
                override fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent> = flow {
                    val auth = frame as? CloudTurnFrame.Authenticate
                    val turn = auth?.let {
                        CloudRouteTurn(
                            request = it.request,
                            sourceLanguage = it.sourceLanguage,
                            context = it.context,
                            requestId = it.requestId,
                            capabilities = it.capabilities,
                            routeHint = it.routeHint,
                            conversationId = it.conversationId,
                            conversationMode = it.conversationMode,
                            capturedOrigin = 0L,
                        )
                    }!!
                    emitAll(route(turn))
                }
            }
        },
        resolveBaseUrl = { "https://cloud.example.com" },
        tokenProvider = object : CloudTokenProviding {
            override suspend fun currentToken(): String = "test-token"
        },
        playbackSink = mock(),
        fingerprintTimingManager = mock(),
        playbackContextProvider = object : PlaybackContextProvider {
            override fun current() = PlaybackContext()
        },
        conversationAdmission = admission,
        cloudPlaybackContextState = CloudPlaybackContextState(),
        analytics = mock(),
        isHostPlaying = { false },
        audioPlayer = null,
        gracePeriodSignal = signal,
    currentPlaybackPositionMs = { null },
    wallClockNowMs = { 0L },
    playerPauseOpen = { false },
    playerPauseReleasedAtMs = { null },
    )

    @Test
    fun `a follow-up creates a new conversation when the first create never reached connected`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val dispatched = mutableListOf<Dispatched>()
        val firstEntered = CompletableDeferred<Unit>()
        val firstEnds = CompletableDeferred<Unit>()
        var call = 0
        val sink = sink(signal, origin) { turn ->
            dispatched += Dispatched(turn.conversationId, turn.conversationMode)
            call += 1
            if (call == 1) {
                flow {
                    // The exchange dies BEFORE the handshake completes: no Connected, no terminal.
                    firstEntered.complete(Unit)
                    firstEnds.await()
                    throw java.io.IOException("socket closed before the handshake")
                }
            } else {
                flow {
                    emit(CloudRouteEvent.Connected("pcm@16k"))
                    emit(CloudRouteEvent.Done(0, 0))
                }
            }
        }

        val first = async {
            // A transport failure before any terminal surfaces as a throw by design. The STATE this case
            // needs is "the create ended without connected", which is exactly what that throw leaves.
            runCatching {
                sink.routeToCloud(
                    request = "first",
                    context = PlaybackContext(),
                    isLiveGraceTurn = false,
                    capturedOrigin = origin,
                    isWakeOriginated = true,
                    admitted = createWireId,
                )
            }
        }
        try {
            firstEntered.await()
            firstEnds.complete(Unit)
            runCurrent()
            // The transport boundary rethrows: a turn that dies before any terminal must fail visibly
            // rather than being reported as an ordinary result. That is why the real path is driven
            // instead of a fake -- the failure is the state this case is about.
            assertTrue(
                "the first turn must actually have failed, or the state under test was never reached",
                first.await().isFailure,
            )

            // A follow-up in the SAME live grace window, not wake-originated: the shape that resumes
            // when an acknowledged handle is available. This is the production predicate
            // (VoicePlaybackIntentExecutor.kt:300-302), asserted rather than assumed.
            assertTrue("the grace window must still be live for this case to mean anything", signal.isCurrent(origin))
            assertTrue("the grace window must still be active", signal.isActive.value)
            val second = async {
                sink.routeToCloud(
                    request = "follow up",
                    context = PlaybackContext(),
                    isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                    capturedOrigin = origin,
                    isWakeOriginated = false,
                    admitted = followUpWireId,
                )
            }
            runCurrent()
            second.await()

            // A THIRD turn, after the replacement create SUCCEEDED. The replacement is now a real
            // conversation, so this one continues it -- otherwise every later follow-up in the window
            // keeps opening fresh conversations and the user loses context for the rest of the exchange.
            assertTrue(
                "the grace window is still live for a third turn",
                signal.isCurrent(origin) && signal.isActive.value,
            )
            val third = async {
                sink.routeToCloud(
                    request = "third",
                    context = PlaybackContext(),
                    isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                    capturedOrigin = origin,
                    isWakeOriginated = false,
                    admitted = "8f14e45f-ceea-467a-9c8f-1b2d3e4a5b6c",
                )
            }
            runCurrent()
            third.await()
        } finally {
            firstEnds.complete(Unit)
            signal.onAppBackgrounded()
        }

        assertEquals("all three turns reached the wire", 3, dispatched.size)
        assertEquals(
            "the third turn RESUMES the replacement conversation",
            CloudConversationMode.Resume,
            dispatched[2].mode,
        )
        assertEquals(
            "and it is the REPLACEMENT's conversation, not the dead one",
            dispatched[1].conversationId,
            dispatched[2].conversationId,
        )
        assertEquals("the first turn creates the conversation", CloudConversationMode.Create, dispatched[0].mode)
        assertEquals(
            "a create that never reached `connected` must not make its conversation resumable",
            CloudConversationMode.Create,
            dispatched[1].mode,
        )
        assertNotEquals(
            "the follow-up must not reuse the unacknowledged conversation",
            dispatched[0].conversationId,
            dispatched[1].conversationId,
        )
    }

    @Test
    fun `a follow-up resumes a conversation that did reach connected`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val dispatched = mutableListOf<Dispatched>()
        val sink = sink(signal, origin) { turn ->
            dispatched += Dispatched(turn.conversationId, turn.conversationMode)
            flow {
                emit(CloudRouteEvent.Connected("pcm@16k"))
                emit(CloudRouteEvent.Done(0, 0))
            }
        }

        val first = async {
            sink.routeToCloud(
                request = "first",
                context = PlaybackContext(),
                isLiveGraceTurn = false,
                capturedOrigin = origin,
                isWakeOriginated = true,
                admitted = createWireId,
            )
        }
        runCurrent()
        first.await()
        assertTrue("the grace window must still be live", signal.isCurrent(origin))
        assertTrue("the grace window must still be active", signal.isActive.value)
        val second = async {
            sink.routeToCloud(
                request = "follow up",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                capturedOrigin = origin,
                isWakeOriginated = false,
                admitted = followUpWireId,
            )
        }
        runCurrent()
        second.await()

        assertEquals("both turns reached the wire", 2, dispatched.size)
        assertEquals(CloudConversationMode.Create, dispatched.first().mode)
        assertEquals(CloudConversationMode.Resume, dispatched.last().mode)
        assertEquals(
            "the acknowledged conversation is continued, not replaced",
            dispatched.first().conversationId,
            dispatched.last().conversationId,
        )
    }

    /**
     * What production ACTUALLY derives for a follow-up captured while the window's first turn is still
     * on the wire.
     *
     * The executor notes a prepared request only AFTER a dispatch returns
     * (VoicePlaybackIntentExecutor.kt:332), so `hasPreparedRequest` is still false and the follow-up is
     * derived WAKE-ORIGINATED (:292-308) -- which always begins a conversation. So in that ordering
     * production creates a second conversation BY DESIGN, and no acknowledgement arriving in between
     * changes it.
     *
     * This case pins that derivation rather than asserting a resume the flags cannot produce. An earlier
     * version of this test hand-passed `isWakeOriginated = false`, which made it green while testing an
     * ordering production never enters -- the shared-oracle trap in its flag form.
     */
    @Test
    fun `a follow-up captured while the first turn is in flight is derived wake-originated`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val createOnWire = CompletableDeferred<Unit>()
        val letCreateFinish = CompletableDeferred<Unit>()
        val dispatched = mutableListOf<Dispatched>()
        val sink = sink(signal, origin) { turn ->
            dispatched += Dispatched(turn.conversationId, turn.conversationMode)
            flow {
                createOnWire.complete(Unit)
                letCreateFinish.await()
                emit(CloudRouteEvent.Connected("pcm@16k"))
                emit(CloudRouteEvent.Done(0, 0))
            }
        }
        val first = async {
            sink.routeToCloud(
                request = "first",
                context = PlaybackContext(),
                isLiveGraceTurn = false,
                capturedOrigin = origin,
                isWakeOriginated = true,
                admitted = createWireId,
            )
        }
        createOnWire.await()

        // The derivation, read from the signal exactly as the executor reads it.
        val firstOfWindow = !signal.hasPreparedRequest(origin)
        assertTrue("the window has no noted request yet, because noting happens after dispatch", firstOfWindow)
        assertTrue(
            "so the follow-up is derived wake-originated, and begins its own conversation",
            signal.isWakeOriginated(origin, isFirstRequestOfWindow = firstOfWindow),
        )

        letCreateFinish.complete(Unit)
        first.await()
        signal.onAppBackgrounded()
    }

    /**
     * A successor window opens while the previous window's create is STILL unacknowledged.
     *
     * Only one origin is ever admitted (`prepare` refuses `origin != currentOrigin`,
     * CloudConversationAdmission.kt:71), and admitting a newer one retires what the older held. So the
     * successor's create legitimately takes the slot, and the older handshake must NOT promote it --
     * which is what keying [CloudConversationAdmission.acknowledge] by id guarantees. This pins that:
     * the successor keeps its own conversation, and a late acknowledgement for the superseded id
     * changes nothing.
     */
    @Test
    fun `a late handshake for a superseded conversation does not make the successor resumable`() = runTest {
        val admission = CloudConversationAdmission()
        admission.beginOrigin(1)
        val older = requireNotNull(admission.prepare(origin = 1, isLiveGraceTurn = false, isWakeOriginated = true))

        // The successor window supersedes the first while its create has not been acknowledged.
        admission.beginOrigin(2)
        val newer = requireNotNull(admission.prepare(origin = 2, isLiveGraceTurn = false, isWakeOriginated = true))
        assertNotEquals("the successor begins its own conversation", older.conversationId, newer.conversationId)

        // The OLD exchange's handshake arrives late. It must not promote the successor's id.
        admission.acknowledge(older.conversationId)
        val followUp = requireNotNull(admission.prepare(origin = 2, isLiveGraceTurn = true, isWakeOriginated = false))
        assertEquals(
            "the successor is still unacknowledged, so a continuation creates rather than resuming it",
            CloudConversationMode.Create,
            followUp.mode,
        )
        assertNotEquals(
            "and it must never adopt the SUPERSEDED conversation the late handshake named",
            older.conversationId,
            followUp.conversationId,
        )

        // Once the SUCCESSOR is acknowledged, its own continuation resumes it.
        admission.acknowledge(newer.conversationId)
        val afterAck = requireNotNull(admission.prepare(origin = 2, isLiveGraceTurn = true, isWakeOriginated = false))
        assertEquals(CloudConversationMode.Resume, afterAck.mode)
        assertEquals(newer.conversationId, afterAck.conversationId)
    }

    /**
     * A turn prepared as RESUME whose conversation is FORGOTTEN before it is dispatched.
     *
     * The worker no longer has that conversation, so resuming it would fail the follow-up for nothing.
     * It becomes a fresh CREATE instead -- decided at dispatch, against what is true then.
     */
    @Test
    fun `a prepared resume whose conversation expires before dispatch becomes a fresh create`() = runTest {
        val admission = CloudConversationAdmission()
        admission.beginOrigin(1)
        // Establish and acknowledge a conversation, then prepare a continuation that resumes it.
        val first = requireNotNull(admission.prepare(origin = 1, isLiveGraceTurn = false, isWakeOriginated = true))
        admission.acknowledge(first.conversationId)
        val continuation = requireNotNull(admission.prepare(origin = 1, isLiveGraceTurn = true, isWakeOriginated = false))
        assertEquals(CloudConversationMode.Resume, continuation.mode)

        val turn = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn(
            request = "follow up",
            context = au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext(),
            requestId = followUpWireId,
            conversationId = continuation.conversationId,
            conversationMode = continuation.mode,
            capturedOrigin = 1,
        )
        admission.retainOrPrepare(followUpWireId, isRetry = false, continuesGraceWindow = true) { turn }

        // The worker forgets the conversation BEFORE the turn is dispatched.
        admission.forgetConversation(first.conversationId)
        val claim = requireNotNull(admission.claimDispatch(turn, isRetry = false))

        assertEquals(
            "a conversation the worker no longer has must not be resumed",
            CloudConversationMode.Create,
            claim.turn.conversationMode,
        )
        assertNotEquals(
            "and the fresh conversation is a different one",
            first.conversationId,
            claim.turn.conversationId,
        )
    }

    /**
     * The expiry conversion must REACH THE WIRE, not just exist in admission.
     *
     * A follow-up prepared as a Resume whose conversation the worker then forgot is converted to a fresh
     * Create at dispatch -- but the sink re-validates every prepared turn against the handle, and with the
     * handle gone that validation refused the turn outright. So the conversion was documented and unit
     * tested while production silently dropped the user's follow-up. This drives the real sink.
     */
    @Test
    fun `a follow-up whose conversation expired before dispatch still reaches the wire as a create`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val admission = CloudConversationAdmission().apply { beginOrigin(origin) }
        val dispatched = mutableListOf<Dispatched>()
        var call = 0
        val sink = sink(signal, origin, admission) { turn ->
            dispatched += Dispatched(turn.conversationId, turn.conversationMode)
            call += 1
            flow {
                emit(CloudRouteEvent.Connected("pcm@16k"))
                // The SECOND exchange loses the conversation: the worker no longer has it.
                if (call == 2) {
                    emit(CloudRouteEvent.Error(code = "conversation_expired", message = ""))
                } else {
                    emit(CloudRouteEvent.Done(0, 0))
                }
            }
        }

        // Establish a conversation, then prepare a continuation that resumes it.
        sink.routeToCloud(
            request = "first",
            context = PlaybackContext(),
            isLiveGraceTurn = false,
            capturedOrigin = origin,
            isWakeOriginated = true,
            admitted = createWireId,
        )
        val established = requireNotNull(dispatched.single().conversationId)
        val prepared = requireNotNull(
            sink.prepareTurn(
                request = "follow up",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                capturedOrigin = origin,
                isWakeOriginated = false,
                hint = null,
                logicalRequestId = followUpWireId,
                isRetry = false,
                sourceLanguage = null,
            ),
        )
        assertEquals(CloudConversationMode.Resume, prepared.conversationMode)

        // The worker forgets it before the follow-up is sent.
        admission.forgetConversation(established)
        sink.dispatchPrepared(prepared)

        assertEquals(
            "the follow-up must still reach the wire -- dropping it loses the user's question",
            2,
            dispatched.size,
        )
        assertEquals(
            "and it goes as a fresh CREATE, not a resume of a conversation that is gone",
            CloudConversationMode.Create,
            dispatched[1].mode,
        )
        assertNotEquals(established, dispatched[1].conversationId)
    }

    /**
     * The ordering where dispatch-time re-resolution IS reachable: a continuation prepared while an
     * earlier turn of the SAME window is still on the wire, after the window's first request has been
     * noted.
     *
     * The first turn completes and notes the window's request, so a later follow-up is derived
     * NON-wake (VoicePlaybackIntentExecutor.kt:292-308). A second turn is then dispatched and, while it
     * is in flight, a third is prepared -- its flags derived from the signal, not hand-passed. The
     * second turn's `connected` lands between that prepare and its dispatch, and the third must resume.
     */
    @Test
    fun `a continuation prepared during a later in-flight turn resumes once that turn is acknowledged`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val dispatched = mutableListOf<Dispatched>()
        val secondOnWire = CompletableDeferred<Unit>()
        val handshake = CompletableDeferred<Unit>()
        val handshakeSeen = CompletableDeferred<Unit>()
        val letSecondFinish = CompletableDeferred<Unit>()
        var call = 0
        val sink = sink(signal, origin) { turn ->
            dispatched += Dispatched(turn.conversationId, turn.conversationMode)
            call += 1
            when (call) {
                2 -> flow {
                    secondOnWire.complete(Unit)
                    handshake.await()
                    emit(CloudRouteEvent.Connected("pcm@16k"))
                    handshakeSeen.complete(Unit)
                    letSecondFinish.await()
                    emit(CloudRouteEvent.Done(0, 0))
                }

                else -> flow {
                    emit(CloudRouteEvent.Connected("pcm@16k"))
                    emit(CloudRouteEvent.Done(0, 0))
                }
            }
        }

        // Turn 1: the wake's own request. Completing it notes the window's request, as the executor does.
        sink.routeToCloud(
            request = "first",
            context = PlaybackContext(),
            isLiveGraceTurn = false,
            capturedOrigin = origin,
            isWakeOriginated = true,
            admitted = createWireId,
        )
        signal.notePreparedRequest(origin)

        // Turn 2 stays on the wire.
        val second = async {
            sink.routeToCloud(
                request = "second",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                capturedOrigin = origin,
                isWakeOriginated = signal.isWakeOriginated(origin, isFirstRequestOfWindow = !signal.hasPreparedRequest(origin)),
                admitted = followUpWireId,
            )
        }
        secondOnWire.await()

        // Turn 3 is PREPARED now, with flags derived exactly as production derives them.
        val firstOfWindow = !signal.hasPreparedRequest(origin)
        assertFalse("the window's first request has been noted, so this is a continuation", firstOfWindow)
        val wakeOriginated = signal.isWakeOriginated(origin, isFirstRequestOfWindow = firstOfWindow)
        assertFalse("and it is therefore NOT wake-originated", wakeOriginated)
        val prepared = requireNotNull(
            sink.prepareTurn(
                request = "third",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                capturedOrigin = origin,
                isWakeOriginated = wakeOriginated,
                hint = null,
                logicalRequestId = "9a0364b9-e9f8-4d2c-b1a7-3c5e7f901234",
                isRetry = false,
                sourceLanguage = null,
            ),
        )

        // Turn 2's handshake lands BETWEEN that prepare and turn 3's dispatch.
        handshake.complete(Unit)
        handshakeSeen.await()
        letSecondFinish.complete(Unit)
        second.await()

        sink.dispatchPrepared(prepared)
        signal.onAppBackgrounded()

        assertEquals("all three turns reached the wire", 3, dispatched.size)
        assertEquals(
            "the third RESUMES: the conversation was acknowledged before it was sent",
            CloudConversationMode.Resume,
            dispatched[2].mode,
        )
        assertEquals(
            "and it continues the conversation turn 2 established",
            dispatched[1].conversationId,
            dispatched[2].conversationId,
        )
    }

    /**
     * A Create RESENT after its own `connected` must not demote the conversation it established.
     *
     * The resend goes through the same sending step as the first attempt. Marking the acknowledged id
     * pending again would make every later follow-up create a fresh conversation, losing the user's
     * context for the rest of the exchange. Nothing in production raises the retry flag today, so this
     * path is driven by tests; the guard exists so retry wiring cannot inherit the demotion.
     */
    @Test
    fun `resending a create after its handshake keeps the conversation resumable`() = runTest {
        // A LONG window: this case is about admission, and a 100 ms one would close under it.
        val signal = GracePeriodSignal(timeoutMs = 60_000L)
        signal.onWakeWordDetected()
        // A window that has a cloud turn in flight is NOT closed by a non-terminal end: the hold is
        // preserved, which is what keeps a follow-up's window open here.
        val windowHold = requireNotNull(signal.holdCloudTurn())
        val origin = signal.currentGeneration
        val admission = CloudConversationAdmission().apply { beginOrigin(origin) }
        val dispatched = mutableListOf<Dispatched>()
        var call = 0
        val sink = sink(signal, origin, admission) { turn ->
            dispatched += Dispatched(turn.conversationId, turn.conversationMode)
            call += 1
            if (call == 2) {
                // THE RESEND GETS NO SECOND HANDSHAKE. The worker already admitted this request, so it
                // answers `duplicate_request` rather than sending `connected` again -- which is exactly why
                // a demotion caused by the resend would never be repaired.
                flow { emit(CloudRouteEvent.Error(code = "duplicate_request", message = "")) }
            } else {
                flow {
                    emit(CloudRouteEvent.Connected("pcm@16k"))
                    emit(CloudRouteEvent.Done(0, 0))
                }
            }
        }

        // Prepare the wake's own turn, dispatch it, and let its handshake acknowledge the conversation.
        val prepared = requireNotNull(
            sink.prepareTurn(
                request = "first",
                context = PlaybackContext(),
                isLiveGraceTurn = false,
                capturedOrigin = origin,
                isWakeOriginated = true,
                hint = null,
                logicalRequestId = createWireId,
                isRetry = false,
                sourceLanguage = null,
            ),
        )
        sink.dispatchPrepared(prepared)
        val established = requireNotNull(dispatched.single().conversationId)

        // PREPARE the follow-up while the conversation is acknowledged, so it is a RESUME. This is the
        // ordering that matters: the demotion happens AFTER this prepare, and the follow-up is sent after
        // it. Preparing it later would let admission mint a Create for its own reason and the case would
        // pass without exercising the guard at all.
        signal.notePreparedRequest(origin)
        assertTrue("the window is still live", signal.isCurrent(origin) && signal.isActive.value)
        val followUp = requireNotNull(
            sink.prepareTurn(
                request = "follow up",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                capturedOrigin = origin,
                isWakeOriginated = signal.isWakeOriginated(origin, isFirstRequestOfWindow = !signal.hasPreparedRequest(origin)),
                hint = null,
                logicalRequestId = followUpWireId,
                isRetry = false,
                sourceLanguage = null,
            ),
        )
        assertEquals("prepared as a RESUME of the acknowledged conversation", CloudConversationMode.Resume, followUp.conversationMode)

        // RESEND the create after its handshake arrived. The follow-up is already prepared and waiting, so
        // nothing re-decides it afterwards: what admission holds when it is sent is what it sends.
        sink.dispatchPrepared(prepared, isRetry = true)
        assertEquals("the resend must actually reach the wire", 2, dispatched.size)
        assertEquals("the resend keeps its own identity", established, dispatched[1].conversationId)
        assertEquals(CloudConversationMode.Create, dispatched[1].mode)

        // Send the follow-up as the executor does. Its pair was fixed while the conversation was
        // acknowledged; what must survive the resend is that the CONVERSATION IT NAMES is still resumable.
        sink.dispatchPrepared(followUp)
        signal.onAppBackgrounded()
        signal.finishCloudTurn(windowHold)

        // And a LATER CONTINUATION, in its OWN live window, must still resume the same conversation. This
        // is where a demotion costs the user their thread: admission would mint a fresh conversation for
        // every remaining turn of the exchange. It is prepared under the wake's own window, which has
        // already had its turn, so it is a continuation rather than another wake. The follow-up of a real
        // exchange shares this window; a second wake would be a new act and would create by contract.
        val followUpOrigin = origin
        val later = requireNotNull(
            sink.prepareTurn(
                request = "later",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(followUpOrigin) && signal.isActive.value,
                capturedOrigin = followUpOrigin,
                isWakeOriginated = signal.isWakeOriginated(followUpOrigin, isFirstRequestOfWindow = !signal.hasPreparedRequest(followUpOrigin)),
                hint = null,
                logicalRequestId = "9a0364b9-e9f8-4d2c-b1a7-3c5e7f901234",
                isRetry = false,
                sourceLanguage = null,
            ),
        )
        // Not assumed: a control that states its own flags can cover a path production never takes.
        assertTrue("the later turn is inside a live window", signal.isCurrent(followUpOrigin) && signal.isActive.value)
        assertFalse(
            "and it is a continuation, not a wake",
            signal.isWakeOriginated(followUpOrigin, isFirstRequestOfWindow = !signal.hasPreparedRequest(followUpOrigin)),
        )
        assertEquals(
            "a continuation prepared AFTER the resend still resumes the acknowledged conversation",
            CloudConversationMode.Resume,
            later.conversationMode,
        )
        assertEquals(established, later.conversationId)

        assertEquals(3, dispatched.size)
        assertEquals(
            "the resend must not demote the acknowledged conversation",
            CloudConversationMode.Resume,
            dispatched[2].mode,
        )
        assertEquals(established, dispatched[2].conversationId)
    }

    /**
     * The Create to Resume conversion AT DISPATCH, on the recovery path.
     *
     * A create dies unacknowledged, so the continuation is prepared as a CREATE of a replacement. The
     * replacement's handshake then lands before the continuation is sent, and it must adopt that now-real
     * conversation rather than opening a third one. Without the conversion the continuation stays a Create
     * and the user's thread splits.
     */
    @Test
    fun `a continuation prepared as a create adopts the replacement once it is acknowledged`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val admission = CloudConversationAdmission().apply { beginOrigin(origin) }
        val dispatched = mutableListOf<Dispatched>()
        val firstEnds = CompletableDeferred<Unit>()
        val replacementOnWire = CompletableDeferred<Unit>()
        val handshake = CompletableDeferred<Unit>()
        val handshakeSeen = CompletableDeferred<Unit>()
        val letReplacementFinish = CompletableDeferred<Unit>()
        var call = 0
        val sink = sink(signal, origin, admission) { turn ->
            dispatched += Dispatched(turn.conversationId, turn.conversationMode)
            call += 1
            when (call) {
                1 -> flow {
                    // The first create dies BEFORE any handshake.
                    firstEnds.await()
                    throw java.io.IOException("socket closed before the handshake")
                }

                2 -> flow {
                    replacementOnWire.complete(Unit)
                    handshake.await()
                    emit(CloudRouteEvent.Connected("pcm@16k"))
                    handshakeSeen.complete(Unit)
                    letReplacementFinish.await()
                    emit(CloudRouteEvent.Done(0, 0))
                }

                else -> flow {
                    emit(CloudRouteEvent.Connected("pcm@16k"))
                    emit(CloudRouteEvent.Done(0, 0))
                }
            }
        }

        val first = async {
            runCatching {
                sink.routeToCloud(
                    request = "first",
                    context = PlaybackContext(),
                    isLiveGraceTurn = false,
                    capturedOrigin = origin,
                    isWakeOriginated = true,
                    admitted = createWireId,
                )
            }
        }
        runCurrent()
        firstEnds.complete(Unit)
        assertTrue("the first create must actually have failed", first.await().isFailure)
        signal.notePreparedRequest(origin)

        // The replacement create goes on the wire and stays there.
        val replacement = async {
            sink.routeToCloud(
                request = "replacement",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                capturedOrigin = origin,
                isWakeOriginated = signal.isWakeOriginated(origin, isFirstRequestOfWindow = !signal.hasPreparedRequest(origin)),
                admitted = followUpWireId,
            )
        }
        replacementOnWire.await()

        // Prepare the continuation NOW: the replacement is unacknowledged, so this is a CREATE.
        val prepared = requireNotNull(
            sink.prepareTurn(
                request = "continuation",
                context = PlaybackContext(),
                isLiveGraceTurn = signal.isCurrent(origin) && signal.isActive.value,
                capturedOrigin = origin,
                isWakeOriginated = signal.isWakeOriginated(origin, isFirstRequestOfWindow = !signal.hasPreparedRequest(origin)),
                hint = null,
                logicalRequestId = "9a0364b9-e9f8-4d2c-b1a7-3c5e7f901234",
                isRetry = false,
                sourceLanguage = null,
            ),
        )
        assertEquals(
            "prepared as a CREATE, because the replacement has not been acknowledged yet",
            CloudConversationMode.Create,
            prepared.conversationMode,
        )

        // The replacement's handshake lands BETWEEN that prepare and the continuation's dispatch.
        handshake.complete(Unit)
        handshakeSeen.await()
        letReplacementFinish.complete(Unit)
        replacement.await()

        sink.dispatchPrepared(prepared)
        signal.onAppBackgrounded()

        assertEquals(3, dispatched.size)
        assertEquals(
            "the continuation adopts the acknowledged replacement instead of opening a third conversation",
            CloudConversationMode.Resume,
            dispatched[2].mode,
        )
        assertEquals(
            "and it continues the REPLACEMENT's conversation",
            dispatched[1].conversationId,
            dispatched[2].conversationId,
        )
    }
}
