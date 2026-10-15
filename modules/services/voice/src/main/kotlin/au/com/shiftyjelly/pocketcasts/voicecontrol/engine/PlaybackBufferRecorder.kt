package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import androidx.media3.common.C
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * THE POSITION EACH SOURCE SUPPLIES, because they are not all the same quantity and the difference
 * decides whether two of them can be compared:
 *
 * ```
 * episode        PcmChunk.positionSec          MEDIA position      (PositionAxis.Media)
 * cloud answer   AudioTrack.getTimestamp        a MONOTONIC TIME    (PositionAxis.Monotonic)
 * earcon         none                           no position at all
 * TTS            none                           no position at all
 * capture (mic)  getTimestamp(CLOCK_MONOTONIC)   a TIME (see OboeAudioCapture.captureClock)
 * ```
 *
 * Two consequences a reader should not have to rediscover:
 *  - SUBMISSION ORDER SUFFICES FOR WHAT THIS CLASS CONTAINS. Its contract is "the PCM the client
 *    actually submitted for playback", and the filter's first question is what the reference holds,
 *    which submission order answers exactly. A wrong submission order corrupts WHAT is held.
 *  - IT PLACES A WINDOW IN TIME ONLY WHEN ONE AXIS IS PRESENT. The cloud answer now supplies a
 *    render clock, so a window of ONLY cloud audio can be related to wall time and the gap between it
 *    and a microphone segment is the player's buffer latency -- the quantity an echo-bleed filter is
 *    deciding. The episode tap supplies a MEDIA position, which is not a time, so those two cannot be
 *    combined: a window containing both has NO position and is reported as such rather than as a
 *    subtraction across two origins.
 *
 * The render clock changed what a consumer can ASK of the stored samples, not what this class stores.
 * That distinction is why the two consequences above are separate claims rather than one bug, and it
 * is also why adding the clock introduced a new failure mode -- a mixed window -- rather than only
 * removing an old gap. A new source of time is a new way for two clocks to disagree.
 *
 * Rolling buffer of recently-played audio for cross-correlation.
 *
 * It holds the PCm the client actually submitted for playback, so the signal filter can reject
 * playback bleed at the microphone. Three renderers contribute, and this class records each where
 * the client owns the buffer:
 *
 *  - episode playback, via the existing [FingerprintPcmTap] sink-submission tap (see [start]);
 *  - cloud answer audio, written at its `AudioTrack` submission point (`CloudAudioPlayer`);
 *  - local feedback (earcon/TTS), pending a later increment because neither exposes PCM today.
 *
 * Timestamps stay aligned because every source feeds the same single writer; the correlation only
 * needs a bounded alignment window, so a submission reference is sufficient — it does not need to
 * track samples already physically played.
 */
@Singleton
class PlaybackBufferRecorder @Inject constructor(
    private val fingerprintPcmTap: FingerprintPcmTap,
) {
    // Monotonic millisecond clock, deltas only (production default is System.nanoTime-based, which is
    // constructible off-device; only differences are consumed, never the absolute origin). A retired
    // producer's surviving tail is audio the hardware is STILL playing: it may stay matchable only
    // until that playback finishes. The tail's sample count bounds WHEN it can still sound, but with
    // no further writes nothing advances time -- so expiry is measured against this clock at every
    // consumer, not against later activity. Internal seam for deterministic controls; NOT a constructor
    // parameter because Dagger must resolve every injected parameter without Kotlin defaults.
    internal var monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L }

    private val buffer: FloatArray = FloatArray(SAMPLE_RATE * BUFFER_DURATION_SECONDS)

    /**
     * Which stream contributed each ring slot. Acoustic retirement is per producer: a stopped answer
     * must invalidate ITS samples and observations without touching an episode's live reference.
     * [UNTAGGED] marks slots no producer owns any more (retired samples, or never written).
     */
    private val ownerTags = LongArray(buffer.size) { UNTAGGED }
    private var writePos = 0
    private var filled = false

    /**
     * Tickets whose producer ended THEIR OWN stream ([retireProducer]): a write or render observation
     * carrying a closed ticket is refused, so a callback held across the stop cannot resurrect audio
     * the identity no longer owns nor re-enter state the successor already holds.
     */
    private val closedTickets = HashSet<Long>()

    /** Identities whose acoustic tail is still owed expiry; admission closure itself is ticket-based. */
    private val retiredProducerIds = HashSet<Long>()

    /**
     * Admission GENERATION. Every ticket embeds the generation it was minted under; [retire] bumps it,
     * which invalidates EVERY outstanding admission atomically -- including a callback from a producer
     * whose FIRST write was still held, the case a historical id-ledger cannot distinguish from a new
     * successor. A continuing owner (a live episode across a route reset) renews explicitly through
     * [renewAdmission]; a stale holder cannot, because it carries only a dead ticket.
     */
    private var admissionGeneration = 0L
    private var ticketSequence = 0L

    /**
     * When each retired producer's surviving tail stops being plausible playback: retirement instant
     * + the tail's own duration. Enforced by [expireRetiredTailsLocked] at every consumer, so a stale
     * tail ages out on elapsed TIME even if no later write ever arrives.
     */
    private val tailExpiryMs = HashMap<Long, Long>()

    /**
     * A producer's RIGHT TO WRITE, captured when the producer/callback is CREATED and carried by every
     * write and render observation. The recorder alone decides whether a ticket is live, so no held
     * callback can present an "old" identity as a future one: validity requires BOTH the current
     * admission generation and a ticket its owner has not closed.
     */
    class Admission internal constructor(val producerId: Long, val ticket: Long) {
        /** Generation this ticket was minted under; compared against the recorder's current one. */
        internal val generation: Long get() = ticket ushr 32
    }

    // Ticket -> admission registry for incarnations still owned by a live producer; bounded by
    // retirement, cleared wholesale when the generation bumps.
    private val liveByTicket = HashMap<Long, Admission>()

    /** Opens admission for a NEW producer identity at the current generation. Call at producer creation. */
    fun openAdmission(producerId: Long): Admission = synchronized(lock) { newAdmissionLocked(producerId) }

    /**
     * Renews a CONTINUING owner after its ticket was invalidated by a wholesale [retire] (route change,
     * service stop). This is an active-owner act: the caller demonstrably still drives live audio. A
     * held callback cannot renew -- it possesses only the dead ticket and gets no reference here.
     * The previous ticket is closed so anything released with it stays refused.
     */
    fun renewAdmission(previous: Admission): Admission = synchronized(lock) { renewAdmissionLocked(previous) }

    /** Caller holds [lock]. See [renewAdmission]. */
    private fun renewAdmissionLocked(previous: Admission): Admission {
        closedTickets += previous.ticket
        liveByTicket.remove(previous.ticket)
        return newAdmissionLocked(previous.producerId)
    }

    private fun newAdmissionLocked(producerId: Long): Admission {
        ticketSequence += 1L
        val admission = Admission(producerId, (admissionGeneration shl 32) or (ticketSequence and 0xFFFFFFFFL))
        // Registry is bounded by retirement: retire()/retireProducerLocked remove entries with their
        // streams, so lookups at delivery find only incarnations that were minted, never resurrected ids.
        liveByTicket[admission.ticket] = admission
        return admission
    }

    /** Current admission generation, for owners deciding whether their ticket survived a reset. */
    fun admissionGenerationSnapshot(): Long = synchronized(lock) { admissionGeneration }

    /** Caller holds [lock]: is this ticket still entitled to write? */
    private fun isAdmissionLiveLocked(admission: Admission): Boolean = admission.generation == admissionGeneration && admission.ticket !in closedTickets

    /**
     * Guards [buffer], [writePos], [filled] and [lastPositionMs].
     *
     * The class doc's "single writer" is true of the SUBMISSION ORDER, not of the thread: the tap
     * collector runs on Main, [CloudAudioPlayer] writes from the sink's IO coroutine, the route
     * observer retires on Main, and [snapshot] is read from the ASR coroutine. [write] advances one
     * field at a time, so a reader sees a consistent cursor; [retire] is a MULTI-FIELD transition
     * (fill + copy + writePos + filled), so a reader interleaving it can size the window from one
     * field and populate it from another.
     */
    private val lock = Any()

    private var tapJob: Job? = null

    /**
     * Whether the [sampleCount] newest retained samples are contiguous on the reported axis.
     *
     * A hole ceases to matter when the ring has overwritten the audio BEFORE it: from that point every
     * retained sample lies on one side of it. The oldest retained position is the newest position minus
     * the window's duration, so the hole still matters exactly while it begins after that.
     *
     * Caller holds [lock].
     */
    private fun windowIsContiguous(sampleCount: Int): Boolean {
        val hole = holeThroughPositionMs ?: return true
        val newest = lastPositionMs ?: return true
        val oldest = newest - (sampleCount * 1_000L) / SAMPLE_RATE
        return oldest >= hole
    }

    /** Player-timeline position (ms) of the newest timestamped sample; null until one is written. */
    private var lastPositionMs: Long? = null

    /**
     * Per-producer resampling continuation. Decoder chunks are ordinary-sized; converting each chunk
     * independently floors its output length and restarts interpolation at source index zero, so the
     * SAME stream split differently would store a different duration and accumulate drift. A single
     * shared slot does not suffice either: producers interleave (episode while a same-rate answer
     * plays), and clearing on every switch re-introduces exactly that per-boundary loss. The state is
     * therefore keyed by (admission ticket, sourceSampleRateHz): a renewed stream never inherits the dead
     * incarnation's carry, while an A->B->A alternation of DIFFERENT owners still resumes each where it
     * left off. Entries leave the map only through their OWN producer's retirement
     * ([retireProducer]), explicit epoch supersession at the tap collector, or whole-recorder
     * retirement ([retire]) -- never unrelated write pressure.
     * Touched only inside `synchronized(lock)` via [write]/[retireProducer]/[retire].
     */
    private class Continuation {
        var carry = FloatArray(0)
        var offsetFrac = 0.0
    }

    // Entries leave ONLY through their own lifecycle: [retireProducer] when a stream ends, explicit
    // epoch supersession at the tap collector, or whole-recorder [retire]. Pressure-based eviction is
    // NOT ownership: a still-live producer would lose its continuation after unrelated writes.
    private val continuations = HashMap<ProducerClockKey, Continuation>()

    /** Producer identity paired with its source clock: a same-rate successor is a DIFFERENT key. */
    private data class ProducerClockKey(val ticket: Long, val sampleRateHz: Int)

    /**
     * Downmix + phase-carrying linear resample to [SAMPLE_RATE]. [Continuation.carry] holds the source
     * samples the previous chunk left unconsumed and [Continuation.offsetFrac] the fractional position
     * inside them where the next output sample begins, so the stored stream is a function of the
     * SOURCE, not of how the producer partitioned it. Touched only inside `synchronized(lock)` via
     * [write], and retired with its producer identity.
     */
    private fun normalizeStream(
        pcm: FloatArray,
        sourceSampleRateHz: Int,
        sourceChannels: Int,
        continuation: Continuation,
    ): FloatArray {
        if (sourceChannels == 1 && sourceSampleRateHz == SAMPLE_RATE) return pcm
        val mono = downmixToMono(pcm, sourceChannels)
        if (sourceSampleRateHz == SAMPLE_RATE) return mono
        val resampleCarry = continuation.carry
        val resampleOffsetFrac = continuation.offsetFrac
        val input = if (resampleCarry.isEmpty()) mono else resampleCarry + mono
        val ratio = sourceSampleRateHz.toDouble() / SAMPLE_RATE
        // Capacity WITHOUT the interpolation headroom: the final position may clamp its neighbour to
        // itself (bounded edge error of at most one sample) rather than being dropped -- dropping it
        // would lose up to one OUTPUT at EVERY chunk boundary, which is exactly the partition-dependence
        // this carry state exists to remove.
        val outFrames = ((input.size - resampleOffsetFrac) / ratio).toInt().coerceAtLeast(0)
        val out = FloatArray(outFrames)
        for (o in 0 until outFrames) {
            val srcPos = resampleOffsetFrac + o * ratio
            val i = srcPos.toInt()
            val frac = (srcPos - i).toFloat()
            val a = input[i]
            val b = if (i + 1 < input.size) input[i + 1] else a
            out[o] = a + (b - a) * frac
        }
        // Rebase onto the next chunk: everything from floor(last consumed position) onward carries,
        // and the fractional remainder becomes the new origin.
        val consumed = resampleOffsetFrac + outFrames * ratio
        val cut = consumed.toInt().coerceAtMost(input.size)
        continuation.carry = input.copyOfRange(cut, input.size)
        continuation.offsetFrac = consumed - cut
        return out
    }

    /**
     * Whether the retained samples are still CONTIGUOUS on the reported axis.
     *
     * `reference()` derives the window's start by subtracting its duration from the newest position --
     * arithmetic that is exact only while the samples are adjacent. A tick whose start does not meet the
     * previous tick's end means audio between them never reached this recorder, and the tap's DROP_OLDEST
     * overflow is the ordinary way that happens: `tryEmit` discards the OLDEST chunk silently, so the
     * collector's stream has a hole nothing announces.
     *
     * Across such a hole the derived span covers a range whose middle is audio from a different moment,
     * so the correlator would match the microphone against samples that were never played together and
     * the filter could reject genuine user speech as bleed. A gap therefore removes the anchor: the
     * window keeps its samples for offset-based correlation and asserts nothing about when they sounded.
     *
     * IT IS A PROPERTY OF THE RETAINED RANGE, NOT A LATCH ON THE RECORDER. A hole is remembered by the
     * POSITION at which it occurred, and forgotten once the ring has overwritten past it: from then on
     * no retained sample was separated by that hole, so the window is contiguous again and the anchor is
     * honest. A recorder-wide latch would keep the anchor suppressed forever after one dropped chunk,
     * discarding a good alignment for the rest of the session.
     */
    private var holeThroughPositionMs: Long? = null

    /** The end of the newest tick on the reported axis, to compare against the next tick's start. */
    private var expectedNextPositionMs: Long? = null

    /**
     * Subscribes to the player's sink-submission tap so episode PCM is recorded once, as it is
     * accepted by the sink. Call once per capture session; the returned job is owned by [scope].
     *
     * The chunk's presentation time is carried into the write, so the reference is aligned by the
     * player's own timeline rather than by arrival order. Arrival order is adequate only while one
     * source is writing; episode and cloud answer can interleave, and without the timestamp they
     * would be concatenated in delivery order rather than placed at the position they were played.
     */
    fun start(scope: CoroutineScope): Job {
        tapJob?.cancel()
        // ADMISSION IS STAMPED AT EMISSION (@spec/@reviewer): the tap asks for a ticket when each chunk
        // is CREATED, on the playback thread. Chunks already queued when a wholesale retirement happened
        // carry DEAD tickets and are refused at delivery -- consumption must never mint or renew, or
        // stale in-flight work would be laundered into a new generation. Renewal here covers only
        // FUTURE emissions of the live episode owner.
        var currentAdmission: Admission? = null
        fingerprintPcmTap.admissionStamper = { epoch, carried ->
            synchronized(lock) {
                val carriedAdmission = carried?.let { liveByTicket[it] }
                when {
                    carried == null -> {
                        // New stream epoch: the previous incarnation (if any) is superseded and retired;
                        // a seek or new episode is a NEW producer identity, audio never migrates.
                        currentAdmission?.let { retireProducerLocked(it) }
                        val opened = newAdmissionLocked(epoch)
                        currentAdmission = opened
                        opened.ticket
                    }

                    carriedAdmission != null && !isAdmissionLiveLocked(carriedAdmission) -> {
                        // The live owner's carried ticket died (wholesale retirement): renew for future
                        // emissions only. Queued chunks keep the dead ticket.
                        val renewed = renewAdmissionLocked(carriedAdmission)
                        currentAdmission = renewed
                        renewed.ticket
                    }

                    else -> carried
                }
            }
        }
        return fingerprintPcmTap.chunks
            .onEach { chunk ->
                // A chunk emitted with no stamper installed carries no ticket; a queued chunk whose
                // ticket died carries one that is no longer registered. Neither is accepted -- the
                // recorder refuses by construction, and nothing here mints or renews admission.
                val admission = chunk.admissionTicket?.let { liveByTicket[it] } ?: return@onEach
                write(
                    pcm = chunkToFloats(chunk),
                    // The tap's position is seconds of media time; the reference tracks milliseconds.
                    positionMs = (chunk.positionSec * 1_000.0).toLong(),
                    // The decoder's own format: the recorder normalizes, the tap stays a pass-through.
                    sourceSampleRateHz = chunk.sampleRate,
                    sourceChannels = chunk.channelCount,
                    // The ticket captured WHEN THIS CHUNK WAS EMITTED: dead across retirement, never
                    // re-minted at delivery.
                    admission = admission,
                )
            }
            .launchIn(scope)
            .also { tapJob = it }
    }

    fun stop() {
        tapJob?.cancel()
        tapJob = null
        // The collector no longer owns an episode stream: stop stamping emissions so a later start()
        // (or another recorder instance sharing the tap singleton) does not inherit this owner's state.
        fingerprintPcmTap.admissionStamper = null
    }

    /**
     * Enforces retired-tail expiry at a consumer under [lock]: once a retired producer's deadline
     * passes, its surviving tail is zeroed and untagged, its remaining records go, and its admission
     * flag clears (the identity is then fully dead: nothing it names can be stored any more until an
     * unrelated whole-recorder [retire]). A stale tail must not stay matchable merely because no new
     * write arrived to age it out -- elapsed TIME, not later activity, bounds how long hardware could
     * still be sounding it.
     *
     * Caller holds [lock].
     */
    private fun expireRetiredTailsLocked(nowMs: Long) {
        if (tailExpiryMs.isEmpty()) return
        val due = tailExpiryMs.entries.filter { it.value <= nowMs }.map { it.key }
        if (due.isEmpty()) return
        for (ticket in due) {
            for (idx in ownerTags.indices) {
                if (ownerTags[idx] == ticket) {
                    buffer[idx] = 0f
                    ownerTags[idx] = UNTAGGED
                }
            }
            blocks.removeAll { it.ticket == ticket }
            if (renderObservation?.ticket == ticket) renderObservation = null
            tailExpiryMs.remove(ticket)
            // Admission stays closed after expiry: the identity is retired, expired, and empty. A NEW
            // stream reusing the same id would be indistinguishable from a resurrecting callback, so
            // resurrection-by-id-reuse is refused until [retire] resets the recorder wholesale.
        }
    }

    /**
     * Retires ONE producer's stream completely -- conversion state AND acoustic reference:
     *  - its resampling continuation leaves the map;
     *  - its ring samples older than the audible delay tail are zeroed and untagged, so speech after
     *    the stop cannot be matched against audio that is already gone and dropped as bleed;
     *  - its newest [DELAY_TAIL_SAMPLES] worth survive: hardware still plays them out, and the
     *    correlator legitimately searches that range;
     *  - its recorded blocks go, and a render observation whose blocks retired goes with them.
     * Nothing another producer owns is touched -- a cloud answer stopping under a live episode must
     * not reset the episode's phase or trim its reference.
     */
    fun retireProducer(admission: Admission) {
        synchronized(lock) { retireProducerLocked(admission) }
    }

    /** Caller holds [lock]. See [retireProducer]. */
    private fun retireProducerLocked(admission: Admission) {
        // ACOUSTIC OWNERSHIP IS KEYED BY THE ADMISSION INCARNATION (@reviewer/@spec): a renewed stream
        // shares its logical producer id with the dead one, and old expiry/retirement keyed by that id
        // would zero audio the RENEWED ticket wrote. The ticket is globally unique per incarnation, so
        // ring tags, deadlines, block withdrawal, and observation withdrawal all name the incarnation.
        val ticket = admission.ticket
        run {
            liveByTicket.remove(ticket)
            closedTickets += ticket
            retiredProducerIds += ticket
            // IDEMPOTENT IN TIME (@reviewer): a duplicate stop must NOT restart the deadline -- a stale
            // second call would extend the retired tail's matchable lifetime. putIfAbsent keeps the
            // ORIGINAL expiry; the refusal flag is already closed.
            tailExpiryMs.putIfAbsent(ticket, monotonicNowMs() + DELAY_TAIL_SAMPLES * 1_000L / SAMPLE_RATE)
            continuations.keys.removeAll { it.ticket == ticket }
            // The producer's OWN newest tail stays matchable; everything older from it is dead air.
            var surviving = DELAY_TAIL_SAMPLES
            for (step in 0 until buffer.size) {
                val idx = ((writePos - 1 - step) % buffer.size + buffer.size) % buffer.size
                if (!filled && idx == writePos && step > 0) break
                if (ownerTags[idx] == ticket) {
                    if (surviving > 0) {
                        surviving--
                    } else {
                        buffer[idx] = 0f
                        ownerTags[idx] = UNTAGGED
                    }
                }
            }
            blocks.removeAll { it.ticket == ticket }
            // An observation whose stream just retired describes audio the microphone can no longer
            // hear as echo; keeping it would let the correlator trust a dead anchor. Keyed by ticket:
            // a successor sharing the producer id keeps ITS anchor.
            if (renderObservation?.ticket == ticket) {
                renderObservation = null
            } else if (renderObservation != null && blocks.none { it.trackGeneration == renderObservation!!.trackGeneration }) {
                renderObservation = null
            }
        }
    }

    /**
     * Retires the reference at a real stop or route change, keeping only the acoustic delay tail.
     *
     * Clearing the whole buffer would drop the tail that legitimately still reaches the microphone
     * just after playback stops; keeping it whole would let a stale reference match later user
     * speech and discard it as bleed. So the newest [DELAY_TAIL_SAMPLES] survive and everything
     * older is retired. The delay window is the same range the correlator searches (50–500 ms), so
     * the tail is the part that could still be heard at the microphone.
     */
    fun retire() {
        synchronized(lock) {
            // Whole-recorder retirement (stop or route change) ends EVERY producer's stream through
            // this recorder, so all continuations go. A single stream ending is [retireProducer].
            continuations.clear()
            // GLOBAL RETIREMENT INVALIDATES EVERY OUTSTANDING ADMISSION ATOMICALLY (@spec/@reviewer).
            // Bumping the admission generation kills all tickets minted before this instant -- including
            // a callback from a producer whose FIRST write was still held, which no historical id-ledger
            // could distinguish from a genuinely new successor. Continuing owners renew explicitly via
            // [renewAdmission]; stale holders cannot. Ticket bookkeeping resets because old-generation
            // tickets are dead by generation alone.
            admissionGeneration += 1L
            closedTickets.clear()
            // Every outstanding ticket is dead by generation; the delivery-time registry goes with them.
            // A continuing owner re-stamps at its NEXT emission via renewAdmissionLocked, which re-opens
            // into the fresh generation -- queued chunks keep their now-dead tickets and are refused.
            liveByTicket.clear()
            // DEADLINES SURVIVE THE RESET FOR RETAINED TAILS (@reviewer): any producer whose samples are
            // still retained keeps (or receives) an expiry measured from THIS retirement -- the hardware
            // path they were heard through is being torn down, but the acoustic tail may still reach the
            // microphone briefly and must age out on elapsed time, not linger matchable forever.
            val nowMs = monotonicNowMs()
            for (retainedId in ownerTags.toHashSet()) {
                if (retainedId == UNTAGGED) continue
                tailExpiryMs.putIfAbsent(retainedId, nowMs + DELAY_TAIL_SAMPLES * 1_000L / SAMPLE_RATE)
                retiredProducerIds += retainedId
            }

            // THE SURVIVING TAIL KEEPS ITS CONTINUITY METADATA. Clearing `expectedNextPositionMs` while
            // samples survive would forget where the tail ENDS, so the FIRST contribution after the
            // retirement could not be compared against it -- and a contribution that does not meet the
            // tail would be accepted as adjacent, hiding a discontinuity the window cannot cover. The
            // tail's end is exactly what the next contribution must be checked against.
            //
            // Only when NO samples survive does the metadata describe nothing, and then it is cleared.
            // Nothing was ever submitted: there is no tail to keep and no stale audio to retire.
            if (!filled && writePos == 0) {
                expectedNextPositionMs = null
                return
            }
            val keep = minOf(DELAY_TAIL_SAMPLES, buffer.size, if (filled) buffer.size else writePos)
            val tail = FloatArray(keep)
            val tailOwners = LongArray(keep)
            // Copy the newest `keep` samples in order, wrapping as needed, then write them back so the
            // buffer contains only the still-plausible tail and the write cursor sits after it.
            for (i in 0 until keep) {
                val from = ((writePos - keep + i) % buffer.size + buffer.size) % buffer.size
                tail[i] = buffer[from]
                tailOwners[i] = ownerTags[from]
            }
            buffer.fill(0f)
            java.util.Arrays.fill(ownerTags, UNTAGGED)
            tail.copyInto(buffer, 0)
            tailOwners.copyInto(ownerTags, 0)
            writePos = if (keep == buffer.size) 0 else keep
            filled = false
        }
    }

    /** No axis recorded yet, or no timestamped contribution has arrived. */
    private var positionAxis: PositionAxis? = null

    /** Total samples ever written, across retirements. Names a sample's absolute position. */
    private var totalWritten = 0L

    /**
     * One contribution's mapping onto the track's frame index, retained so a render observation can
     * later select the block it actually names.
     *
     * The record RETIRES WITH ITS SAMPLES: when the ring wraps past [endAbsoluteSample], the record
     * is dropped, because a frame range whose audio is no longer retained cannot anchor a window --
     * selecting it would place samples that no longer exist.
     */
    private class RetainedBlock(
        val startAbsoluteSample: Long,
        val endAbsoluteSample: Long,
        val startTrackFrame: Long,
        val endTrackFrame: Long,
        /** Which track incarnation this audio was written under. */
        val trackGeneration: Long,
        /** Which admission incarnation contributed these samples; retirement withdraws its own only. */
        val ticket: Long,
    )

    private val blocks = ArrayDeque<RetainedBlock>()

    /**
     * The latest render observation whose frame could be matched to retained audio, as
     * `[frameOnTrack, timeMs, trackRateHz]`, or null when none is in force.
     *
     * An observation is accepted only when its frame is at or before the current write head -- a
     * frame AHEAD of everything written names audio that does not exist here (a track reset or a
     * stale read), and accepting it would anchor retained samples to a reading that does not describe
     * them. Unmatched stays unavailable.
     */
    private data class RenderObservation(
        val frameOnTrack: Long,
        val timeMs: Long,
        val trackRateHz: Int,
        val trackGeneration: Long,
        /** Admission incarnation this observation was recorded under; retirement drops it with its audio. */
        val ticket: Long,
    )

    private var renderObservation: RenderObservation? = null

    /**
     * Records where a contribution sits on the track's frame index, alongside its samples.
     *
     * Called by the render path for each chunk; the episode tap does not call it, because its
     * contributions have no track-frame identity to record.
     */
    private fun recordBlock(
        startTrackFrame: Long,
        endTrackFrame: Long,
        sampleCount: Int,
        trackGeneration: Long,
        ticket: Long,
    ) {
        // totalWritten was already advanced by write(); the block's end names that same position, and
        // its start names the position before this contribution's samples. The SAMPLE range is what
        // makes the rendered window exact: an untimed write interleaved between two timed blocks has
        // no frame identity and must not be attributed to the later block's span.
        if (endTrackFrame > startTrackFrame) {
            blocks.addLast(
                RetainedBlock(
                    totalWritten - sampleCount,
                    totalWritten,
                    startTrackFrame,
                    endTrackFrame,
                    trackGeneration,
                    ticket,
                ),
            )
        }
        // Retire block records whose samples the ring has already dropped. The retained count mirrors
        // snapshotLocked's sizing, so a record survives exactly as long as its audio does.
        val retained = if (filled) buffer.size else writePos
        val oldestAlive = totalWritten - retained
        while (blocks.isNotEmpty() && blocks.first().endAbsoluteSample <= oldestAlive) {
            blocks.removeFirst()
        }
        // An observation matched against records that have since retired is no longer matchable.
        val obs = renderObservation
        // An observation belongs to one incarnation; a block from a LATER generation means the track
        // was rebuilt and every older observation describes audio the current track never played.
        if (obs != null && blocks.lastOrNull()?.trackGeneration != obs.trackGeneration) {
            renderObservation = null
        }
    }

    /**
     * Records a render observation for the rendered-block mapping, or drops the previous one when the
     * new observation cannot be matched.
     *
     * An observation naming a frame at or before the write head can select the retained block that
     * frame belongs to. A frame AHEAD of the write head cannot: nothing written yet occupies it, so
     * the observation is refused and the previous one is cleared -- the mapping reports unavailable
     * rather than anchoring to a reading that does not describe the retained audio.
     */
    fun onRenderObservation(
        frameOnTrack: Long,
        timeMs: Long,
        trackRateHz: Int,
        trackGeneration: Long,
        admission: Admission,
    ) {
        val ticket = admission.ticket
        synchronized(lock) {
            expireRetiredTailsLocked(monotonicNowMs())
            // Same admission rule as [write]: a late observation naming a retired incarnation cannot
            // re-anchor the correlator to audio that is expiring or gone. It is DROPPED, not applied --
            // and it must not clear a SUCCESSOR's live anchor either: withdrawal belongs to the
            // retirement boundary, not to a refused callback.
            if (!isAdmissionLiveLocked(admission)) {
                // Withdrawal is keyed by TICKET: a refused stale observation clears only an anchor of its
                // OWN incarnation; a successor sharing the producer id keeps its live anchor (@reviewer).
                if (renderObservation?.ticket == ticket) renderObservation = null
                return
            }
            // The observation is matched only against blocks of ITS OWN track incarnation: after a
            // rebuild both indexes restart at zero, so an old-track frame number can agree with a
            // new-track write head while the clocks behind them describe different audio.
            val sameGeneration = blocks.lastOrNull()?.trackGeneration == trackGeneration
            val writeHeadFrame = blocks.lastOrNull()?.endTrackFrame ?: 0L
            renderObservation =
                if (sameGeneration && frameOnTrack in 1..writeHeadFrame && trackRateHz > 0) {
                    RenderObservation(frameOnTrack, timeMs, trackRateHz, trackGeneration, ticket)
                } else {
                    null
                }
        }
    }

    /**
     * The reference window that has actually RENDERED, as selected by the observation's frame.
     *
     * The window ends at the newest retained block whose END FRAME is at or before the observed
     * frame: that audio has sounded, while anything after it is still queued and is EXCLUDED -- queued
     * PCM must not stand for rendered progress, because the filter would otherwise correlate the
     * microphone against audio the listener has not heard yet.
     *
     * The window's END instant is the observation's time, translated from the observed frame back to
     * the block's end frame at the track's rate -- the frame/time relation preserved as a translation,
     * not clamped to the block's own position. The START is derived from the window's duration, which
     * is exact because the window is contiguous retained audio.
     *
     * Null when nothing can be said: no observation in force, no retained block at or before the
     * observed frame, or the matching records have retired with their samples. Absent stays absent.
     */
    fun renderedReference(): PlaybackReference? {
        synchronized(lock) {
            expireRetiredTailsLocked(monotonicNowMs())
            val obs = renderObservation ?: return null
            // Same rule here: only blocks of the CURRENT incarnation are selectable, so an old
            // observation (already cleared by the rule above, but defensive for a same-call race)
            // cannot select its own generation's block once a newer one exists.
            val rendered = blocks.lastOrNull {
                it.trackGeneration == obs.trackGeneration && it.endTrackFrame <= obs.frameOnTrack
            } ?: return null
            val obsFrame = obs.frameOnTrack
            val obsTimeMs = obs.timeMs
            val trackRateHz = obs.trackRateHz
            val retained = if (filled) buffer.size else writePos
            val oldestAlive = totalWritten - retained
            if (rendered.endAbsoluteSample <= oldestAlive) return null
            // The window spans the CONTIGUOUS timed blocks ending at the selected one: walk back
            // while each block's samples begin exactly where the previous one ended. An untimed
            // write breaks the chain -- its samples have no placement and including them would mix
            // unplaced audio into a window that claims a start instant -- so the window begins at
            // the first block of the chain instead.
            var startAbs = rendered.startAbsoluteSample
            for (i in blocks.size - 2 downTo 0) {
                val prior = blocks.elementAt(i)
                if (prior.endAbsoluteSample == startAbs) startAbs = prior.startAbsoluteSample else break
            }
            val windowStart = maxOf(startAbs, oldestAlive)
            val includedSamples = (rendered.endAbsoluteSample - windowStart).toInt()
            if (includedSamples <= 0) return null
            // The block's end frame sounded at the observation's instant minus the frames between it
            // and the observed frame. Frames-to-time uses the TRACK's rate: both quantities are on the
            // track's index, so that is the rate the difference is expressed in.
            val endMs = obsTimeMs - (obsFrame - rendered.endTrackFrame) * 1_000L / trackRateHz
            val startMs = endMs - includedSamples * 1_000L / SAMPLE_RATE
            return PlaybackReference(
                tailSamples(includedSamples),
                SAMPLE_RATE,
                startMs,
            )
        }
    }

    /** The newest [count] retained samples, in order. Caller holds [lock]. */
    private fun tailSamples(count: Int): FloatArray {
        val result = FloatArray(count)
        for (i in 0 until count) {
            val from = ((writePos - count + i) % buffer.size + buffer.size) % buffer.size
            result[i] = buffer[from]
        }
        return result
    }

    /**
     * @param positionMs the position of the chunk's FIRST sample, or null when the contributing
     * renderer supplies no timestamp (earcons and TTS do not). An untimed chunk contributes SAMPLES
     * and NOT a position: it must not inherit another producer's axis, because doing so would make a
     * window look like it has a valid mapping to a clock nothing measured for this audio.
     * @param axis which clock [positionMs] is on. Ignored when [positionMs] is null.
     */
    fun write(
        pcm: FloatArray,
        positionMs: Long? = null,
        axis: PositionAxis? = null,
        trackStartFrame: Long? = null,
        trackEndFrame: Long? = null,
        trackGeneration: Long? = null,
        sourceSampleRateHz: Int = SAMPLE_RATE,
        sourceChannels: Int = 1,
        /**
         * Identity of the stream producing these samples -- typically the playback incarnation whose
         * decoder emits them. Resampling continuation is keyed to it: two producers at the same rate
         * (an episode and a cloud answer both decoded at 44.1 kHz, or a restart of the same rate) must
         * NOT inherit each other's fractional position, and no external retire is required between
         * them. Omitted by sources already in the reference format, which need no continuation.
         */
        admission: Admission,
    ) {
        // THE RECORDER IS THE FORMAT BOUNDARY. Sources deliver the decoder's own PCM (the episode tap
        // runs at 44.1 kHz stereo for ordinary episodes); the ring, the window duration and every
        // position computation assume 16 kHz mono. Normalizing here -- once, for every source -- is
        // what makes "pcm.size * 1_000L / SAMPLE_RATE" the chunk's true duration below. A tap that
        // stores raw interleaved frames instead holds ~2.8x too little playback time, L/R interleaved,
        // and no correlation in the correlator can undo that.
        require(sourceSampleRateHz > 0) { "sourceSampleRateHz must be positive" }
        require(sourceChannels > 0) { "sourceChannels must be positive" }
        val axisClaimed = axis != null
        synchronized(lock) {
            expireRetiredTailsLocked(monotonicNowMs())
            // ADMISSION IS CLOSED WITH RETIREMENT. A callback admitted before the stop but released
            // after it must not store audio this identity no longer owns, nor open a continuation the
            // successor may already hold under the same key. Refusing the WHOLE write -- samples and
            // position alike -- is the atomic form; nothing about a retired id re-enters the recorder.
            if (!isAdmissionLiveLocked(admission)) return
            val ticket = admission.ticket
            // ONE OWNERSHIP BOUNDARY. Normalization mutates continuation state, so it runs under the
            // same lock that orders the ring write: concurrent writers cannot mix carry/phase, and a
            // concurrent retire() cannot clear continuations mid-chunk.
            // Continuation is keyed by the INCARNATION too: a renewed stream on the same logical id must
            // not inherit the dead one's resampling carry (@reviewer point 2).
            val continuation = continuations.getOrPut(ProducerClockKey(ticket, sourceSampleRateHz)) {
                Continuation()
            }
            val samples = normalizeStream(pcm, sourceSampleRateHz, sourceChannels, continuation)
            totalWritten += samples.size
            for (sample in samples) {
                buffer[writePos] = sample
                ownerTags[writePos] = ticket
                writePos = (writePos + 1) % buffer.size
                if (writePos == 0) filled = true
            }
            if (trackStartFrame != null && trackEndFrame != null) {
                recordBlock(trackStartFrame, trackEndFrame, samples.size, trackGeneration ?: 0L, ticket)
            }
            if (positionMs != null) {
                // A TICK THAT DOES NOT MEET THE PREVIOUS ONE IS A HOLE. Checked HERE, under the same
                // lock as the position update, so the gap and the position cannot be applied separately.
                val expected = expectedNextPositionMs
                if (expected != null && kotlin.math.abs(positionMs - expected) > CONTIGUITY_TOLERANCE_MS) {
                    // Remember WHERE the hole is: it matters only while samples on BOTH sides of it are
                    // still retained. The hole runs from the previous tick's end (where audio is
                    // missing) up to this tick's start (where audio resumes).
                    holeThroughPositionMs = maxOf(holeThroughPositionMs ?: 0L, positionMs)
                }
                // The position of the newest sample: the chunk's start plus its duration at 16 kHz.
                lastPositionMs = positionMs + (samples.size * 1_000L) / SAMPLE_RATE
                expectedNextPositionMs = lastPositionMs
                // WHICH CLOCK that number belongs to. A window holding two contributions from
                // different axes has no single position, and subtracting across them yields a
                // number that is neither a media position nor a time -- the difference between two
                // unrelated origins, reported as a delay.
                if (positionAxis == PositionAxis.Unanchored) {
                    // A window cannot come back to being anchored once an untimed contribution has
                    // joined it. The retained samples now include audio whose sounding time nothing
                    // measured, so the span's start no longer describes when the window began.
                    positionAxis = PositionAxis.Mixed
                } else if (positionAxis == null) {
                    // An unlabelled position is producer placement, not a render observation, so it
                    // leaves the window unanchored rather than claiming the Monotonic default.
                    positionAxis = if (axisClaimed) axis else PositionAxis.Unanchored
                } else if (positionAxis != axis) {
                    positionAxis = PositionAxis.Mixed
                }
            } else {
                // An UNTIMED contribution contributes samples and NO position. It does not inherit
                // the axis of whatever came before, and -- the part that is easy to miss -- it
                // INVALIDATES any anchor already established: the anchor describes the start of the
                // retained window, and this audio has just joined that window with no known time.
                // Keeping the old anchor would place untimed samples at a moment derived from
                // another producer's clock.
                positionAxis = PositionAxis.Unanchored
            }
        }
    }

    /**
     * The player-timeline position of the newest recorded sample, or null when nothing was
     * written with a timestamp. Lets the correlation align a mic segment to a position rather than
     * to buffer offset, which is what holds when sources share the buffer.
     */
    fun lastRecordedPositionMs(): Long? = synchronized(lock) { lastPositionMs }

    /**
     * The reference window with the player position of its oldest retained sample.
     *
     * The oldest sample's position is derived from the newest ([lastPositionMs]) minus the window's
     * duration, which is exact for a contiguous window; sources that interleave are placed by their
     * own submission positions, so the span is the range the buffer actually covers. Null when no
     * timestamped source has contributed.
     */
    fun reference(): PlaybackReference {
        // One acquisition, not two. `snapshot()` and the position are the two halves of ONE answer --
        // the samples and the timeline position they occupy -- so reading them under separate locks
        // can pair `start` derived from a newer position with samples from an older state, giving a
        // span that never existed. `write()` updates both, and `retire()` changes the sample count
        // without touching the position.
        synchronized(lock) {
            expireRetiredTailsLocked(monotonicNowMs())
            val samples = snapshotLocked()
            val end = lastPositionMs
            // A window spanning two clocks reports NO position. The start would otherwise be computed
            // by subtracting the window's duration from a value on one axis and then compared against
            // an instant on the other, producing a plausible delay that is really the distance between
            // two unrelated origins. Absent is the honest answer, and the correlator already falls
            // back to offset matching when a position is absent.
            // A position is offered ONLY when the window's samples carry a monotonically-clocked
            // mapping, because that is the only axis the microphone instant can be compared against.
            // Two exclusions, and the second is the one a mixed-axis guard alone misses:
            //
            //   Mixed     the window spans two origins, so subtraction crosses them
            //   Media     a SINGLE-domain window on the wrong domain -- a media position is seconds
            //             into an episode and a microphone instant is a monotonic time, so pairing
            //             them reports the distance between two unrelated origins as a delay
            //
            // Both report no position. Absent is the honest answer and the correlator falls back to
            // offset matching, whereas a media position would align confidently against a moment that
            // has nothing to do with when the audio was captured.
            val anchored = positionAxis == PositionAxis.Monotonic && windowIsContiguous(samples.size)
            val start = if (anchored) {
                end?.let { it - (samples.size * 1_000L) / SAMPLE_RATE }
            } else {
                null
            }
            return PlaybackReference(samples, SAMPLE_RATE, start)
        }
    }

    /**
     * The retained window with NO position offered, for the fallback path when the rendered mapping
     * cannot anchor.
     *
     * This exists because the fallback must not be [reference]: after observations retire,
     * `reference()` can still report a monotonic anchor over the whole retained span -- including
     * queued PCM -- which would let audio nobody has heard stand for rendered progress through the
     * back door. The window here carries the same SAMPLES for offset-based correlation and asserts
     * nothing about when they sounded.
     */
    fun window(): PlaybackReference = synchronized(lock) {
        expireRetiredTailsLocked(monotonicNowMs())
        PlaybackReference(snapshotLocked(), SAMPLE_RATE, null)
    }

    fun snapshot(): FloatArray = synchronized(lock) {
        expireRetiredTailsLocked(monotonicNowMs())
        snapshotLocked()
    }

    /** The window's samples. Callers must hold [lock] so the read is one coherent step. */
    private fun snapshotLocked(): FloatArray {
        if (!filled && writePos == 0) return FloatArray(0)
        val size = if (filled) buffer.size else writePos
        val result = FloatArray(size)
        if (filled) {
            // Wrap: copy from writePos to end, then start to writePos
            val tail = buffer.size - writePos
            buffer.copyInto(result, 0, writePos, buffer.size)
            buffer.copyInto(result, tail, 0, writePos)
        } else {
            buffer.copyInto(result, 0, 0, writePos)
        }
        return result
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val BUFFER_DURATION_SECONDS = 2

        /** Ring slots no live producer owns. */
        internal const val UNTAGGED: Long = Long.MIN_VALUE

        /**
         * How far a tick's start may sit from the previous tick's end before the samples are treated as
         * non-adjacent. Well below any real chunk gap and far above clock jitter.
         */
        private const val CONTIGUITY_TOLERANCE_MS = 20L

        /**
         * How much of the reference survives retirement. Matches the correlator's search window
         * upper bound (500 ms): everything older than this cannot still be heard at the mic.
         */
        const val DELAY_TAIL_SAMPLES = SAMPLE_RATE / 2

        /**
         * Downmixes interleaved channels to mono and resamples [sourceSampleRateHz] to [SAMPLE_RATE]
         * with linear interpolation. Phase-preserving enough for correlation: the correlator matches
         * against a microphone that heard the playback through air, so sub-sample interpolation error
         * is far below the acoustic variance it already absorbs. Duration, not fidelity, is the
         * invariant: N seconds of source must become N * [SAMPLE_RATE] stored samples.
         */
        internal fun downmixToMono(pcm: FloatArray, sourceChannels: Int): FloatArray {
            if (sourceChannels == 1) return pcm
            val frames = pcm.size / sourceChannels
            return FloatArray(frames) { frame ->
                var sum = 0f
                for (ch in 0 until sourceChannels) sum += pcm[frame * sourceChannels + ch]
                sum / sourceChannels
            }
        }

        /**
         * Converts a tap chunk to the float samples the reference stores. Mirrors the existing
         * consumer's conversion so both readers of the tap agree on the encoding.
         */
        internal fun chunkToFloats(chunk: FingerprintPcmTap.PcmChunk): FloatArray {
            val buffer = ByteBuffer.wrap(chunk.data).order(ByteOrder.nativeOrder())
            return if (chunk.encoding == C.ENCODING_PCM_FLOAT) {
                val floatBuffer = buffer.asFloatBuffer()
                val out = FloatArray(floatBuffer.remaining())
                floatBuffer.get(out)
                out
            } else {
                val shortBuffer = buffer.asShortBuffer()
                val out = FloatArray(shortBuffer.remaining())
                for (i in out.indices) out[i] = shortBuffer.get(i) / 32768f
                out
            }
        }
    }
}

/**
 * Which clock a contribution's position is measured on. Two sources in different domains cannot be
 * paired, so the recorder records which one it is holding rather than assuming they agree.
 */
enum class PositionAxis {
    /** Seconds into the media being played (the episode renderer's tap). */
    Media,

    /** A monotonic time on `CLOCK_MONOTONIC` (the cloud answer's render clock). */
    Monotonic,

    /** Both have contributed: the window has no single position. */
    Mixed,

    /**
     * An untimed contribution has joined the window, so the span's start is no longer known even if
     * every timed contribution agreed. Distinguishing this from [Mixed] keeps the reason accurate:
     * one is two clocks disagreeing, the other is audio whose time nothing measured.
     */
    Unanchored,
}
