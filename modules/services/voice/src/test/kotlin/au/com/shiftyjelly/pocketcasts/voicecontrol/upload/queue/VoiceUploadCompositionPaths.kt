package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.mockito.kotlin.mock

/**
 * The joined engine→durable-record path, emitted ONCE per JVM and shared by every control that needs
 * the REAL record.
 *
 * Two controls need these bytes: the composition end-to-end control (which then drains and settles the
 * record) and the Worker parity control (which submits the bytes to the Worker's own validator). Parity
 * is only meaningful if BOTH measure the same bytes — a hand-built lookalike would prove the schema
 * agrees with a fixture I wrote, not with what the engine emitted.
 *
 * This lives OUTSIDE the test class on purpose. Registering the producer in a `@Before` bound it to one
 * class's JUnit instance lifecycle: a parity control running first in the same JVM found no producer
 * (the failure it reported was `noProducer=true`). Production here is keyed on nothing but the
 * Robolectric application context, so it is callable from either class at any point in the run.
 *
 * Fails loudly rather than substituting a fixture: if the joined path emits nothing, [emitOnce] throws
 * and the calling control fails, because an unproduced record is never a passing parity claim.
 */
internal object VoiceUploadCompositionPaths {
    internal class EmittedJoin(
        val store: ContributionStore,
        val captureId: UUID,
        val startup: VoiceSharingAccountStartup,
        val owner: Any,
        val policy: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider,
        val scope: CoroutineScope,
        val bytes: String,
        val sha256: String,
    )

    @Volatile
    private var emitted: EmittedJoin? = null

    /**
     * Where the emitted record is handed to other test classes. Robolectric runs each test class in its
     * own sandbox classloader, so a shared in-memory object does NOT cross them; a file does, and the
     * file is evidence a reviewer can read. Overridable for CI isolation.
     */
    fun handoffFile(): File = System.getProperty("auris.joinedRecord.handoff")
        ?.let(::File)
        ?: File(System.getProperty("java.io.tmpdir"), "auris-joined-record/joined-emitted-record.json")

    @Synchronized
    fun emitOnce(): EmittedJoin {
        emitted?.let { return it }
        val result = produce()
        emitted = result
        EmittedJoinedRecordHolder.bytes = result.bytes
        EmittedJoinedRecordHolder.sha256 = result.sha256
        return result
    }

    private fun produce(): EmittedJoin {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = java.nio.file.Files.createTempDirectory("joined-emit").toFile()
        val store = ContributionStore(
            root,
            QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
            clock = System::currentTimeMillis,
            jitter = { 0L },
        )
        val startup = VoiceUploadCompositionFixtures.startup(context as android.app.Application)
        val owner = startup.ownerForHandoff()
        // THE live owner identity. Without this, GUARD 2 in the trigger fails closed (no matching
        // live owner) and the capture is recorded record-less — the joined path must publish as the
        // REAL owner, so the fixture publishes through the same owner lock production uses.
        (owner as VoiceUploadCompositionFixtures.AppContextOwner).publish("a@x", 7L, "Bearer-fixture")
        startup.credentials.publish("Bearer-fixture")
        val token = startup.state.sessionStarted(7L)
        val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
        assertTrue(
            startup.state.applyOutcome(
                token,
                ticket,
                VoiceUploadCompositionFixtures.authenticatedProjection("epoch-1"),
                ownerSession = 7L,
            ),
        )
        val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule
            .provideVoiceSharingPolicyProvider(
                remoteConfig = mock(),
                featureProvider = mock(),
                ownership = owner,
                scope = CoroutineScope(Job().apply { cancel() }),
            )
        policy.applySnapshotForTest(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                enabled = true,
                activatedAtMonotonicMs = 1L,
                activationGeneration = 1L,
            ),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val captureId = UUID.randomUUID()
        val ctx = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext(
            captureId,
            captureSession = 1L,
            routeClass = "Bluetooth",
            issuedAuthority = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority(
                accountName = "a@x",
                sessionGeneration = 7L,
                credentialIncarnation = 1L,
                enrollmentEpoch = "epoch-1",
                acceptedDisclosureVersion = "1",
                policyEnabledAtIssue = true,
            ),
            asrModel = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
        )
        assertTrue(
            sink.started(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation.Started(
                    ctx,
                    ShortArray(800) { it.toShort() },
                    validSamples = 800,
                    allowanceMetadataCapBytes = 1 shl 20,
                ),
            ),
        )
        sink.completed(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation.Completed(
                ctx,
                frames = listOf(ShortArray(800) { (it + 1).toShort() }),
                sampleRateHz = 16000,
                speechOnsetSample = 0,
                speechEndSample = 1600,
                wakeState = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation.WakeState.Detected,
            ),
        )
        val producer = VoiceUploadCompositionFixtures.producer(context, store, startup)
        val publishTrigger = VoiceUploadModule.provideRetentionPublishTrigger(
            sink = sink,
            producer = producer,
            accountStartup = startup,
            scope = scope,
        )
        publishTrigger.attach()
        assertTrue(
            sink.joinOutcome(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome.Recognized(
                    captureId,
                    transcript = "skip this ad",
                    detectedLanguage = "en",
                ),
            ),
        )
        assertTrue(
            sink.joinOutcome(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome.Executed(
                    captureId,
                    dispatchedWithoutThrowing = true,
                ),
            ),
        )
        // Name the ACTUAL cause rather than a generic "no record": the publish body can bail for a
        // dozen reasons, and a generic message sends the next reader hunting in the wrong place.
        val ready = store.readyEntries().any { it.captureId == captureId.toString() }
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        check(ready) {
            "the joined path produced no durable record. Diagnose: identity(account='a@x', " +
                "session=7, epoch=epoch-1) must be live in the SAME owner instance the trigger reads; " +
                "asrModel must be Named; the Executed outcome must carry retained PCM; and the record " +
                "builder must accept every stage. Instrumentation: app_version='${pkg.versionName}' " +
                "build='${pkg.longVersionCode}' (a blank app_version is refused by both the builder and " +
                "the Cloud intake)."
        }
        val served = checkNotNull(store.durableCapture(captureId.toString())) {
            "the joined path left no servable durable capture"
        }
        // RELEASE THE DESCRIPTOR IMMEDIATELY. This object is JVM-wide, so a descriptor left open here
        // outlives every test class and holds a source slot for the whole run - it starved unrelated
        // classes with "left descriptors open" (reviewer 2c7dc375). Only the RECORD is needed below.
        served.owned?.settle(1_000L)
        val bytes = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
            .canonicalBytes(served.record).toString(Charsets.UTF_8)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        // Durable handoff: Robolectric isolates test classes in separate classloaders, so an in-memory
        // holder cannot carry bytes between them (observed: `noProducer=true` even though the other
        // class had run). A file can, and a file is also inspectable evidence.
        val handoff = handoffFile()
        handoff.parentFile?.mkdirs()
        handoff.writeText(bytes)
        java.io.File(handoff.parentFile, "joined-emitted-record.sha256.txt").writeText(sha + "\n")
        return EmittedJoin(store, captureId, startup, owner, policy, scope, bytes, sha)
    }
}
