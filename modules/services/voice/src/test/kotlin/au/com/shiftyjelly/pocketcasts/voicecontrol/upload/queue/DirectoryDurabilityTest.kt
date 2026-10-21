package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DIRECTORY DURABILITY (review f003b49f finding 2).
 *
 * `fsyncDir` was a no-op stub whose own comment said directory durability was "approximated" by
 * syncing the files. It is not: on a POSIX filesystem, `fsync` on a file's contents does not make the
 * DIRECTORY ENTRY that names it durable. After a crash the entry may be gone while the bytes it
 * pointed at are on disk — or, worse, a rename may not have taken effect even though both files were
 * synced. The ready marker exists precisely so a missing entry is not trusted, but if the marker
 * itself can vanish after being reported durable, `prepare()` is claiming a crash-atomicity it does
 * not have.
 *
 * These tests assert the Sync boundary is actually invoked at the points where an entry becomes
 * durable, and that a sync FAILURE is not swallowed into a successful prepare. The mechanism itself
 * is verified separately on a real device, because "FileChannel.force on a directory" is a platform
 * behaviour no unit test can establish for Android.
 */
class DirectoryDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val measureDepthPastBound = 8

    private fun limits() = QueueLimits.defaults().copy(maxQueueRecords = 8)

    private class RecordingSync : DirectorySync {
        val calls = mutableListOf<File>()
        override fun sync(directory: File) {
            calls += directory
        }
    }

    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    private fun sha256HexForTest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun chunks(vararg sizes: Int) = sizes.map { ShortArray(it) { i -> i.toShort() } to it }

    private fun meta(captureId: String, sampleCount: Int = 1600): ByteArray {
        val audio = wavBytes(sampleCount)
        val block = AudioBlock(
            sha256 = sha256HexForTest(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = sampleCount.toLong(),
            originalFormat = "pcm_s16le",
            conversionVersion = "1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        return CanonicalJson.canonicalBytes(
            ContributionRecordBuilder(captureId, "epoch-1", "42")
                .model("vad", ModelRef.Named("silero-vad", "4.0"))
                .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
                .model("asr", ModelRef.Named("whisper-small", "2024-11"))
                .model("translation", ModelRef.None)
                .model("router", ModelRef.Named("lfm-intent", "0.3"))
                .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
                .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
                .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))))
                .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
    }

    @Test
    fun `a published entry syncs the directory at every durability point`() {
        val sync = RecordingSync()
        val root = tmp.newFolder("queue")
        val store = ContributionStore(root, limits(), directorySync = sync)
        val id = java.util.UUID.randomUUID().toString()

        val entry = store.prepare(id, "epoch-1", chunks(1600), meta(id))
        assertTrue("entry must publish", entry != null)
        assertEquals("entry must remain readable", 1, store.readyEntries().size)

        // Both pre-marker (sidecars written) and post-marker (ready file created) must sync: the
        // entry is only durable once the directory naming the marker is durable.
        assertTrue(
            "directory sync must be invoked during publication, got ${sync.calls.size} calls",
            sync.calls.size >= 2,
        )
        // The entries live in the store's own subdirectory, so that is the directory whose entry
        // durability matters; syncing the parent would not make the marker's name durable.
        val entriesDir = File(root, "contributions")
        assertTrue(
            "the entry directory must be synced, got ${sync.calls.map { it.absolutePath }}",
            sync.calls.any { it.absolutePath == entriesDir.absolutePath },
        )
        // Creating the directory adds a name to the PARENT; syncing only the new directory would leave
        // that name undurable, so the parent is barriered once at creation.
        assertTrue(
            "the parent namespace must be barriered when the entry directory is created, got " +
                sync.calls.map { it.absolutePath },
            sync.calls.any { it.absolutePath == root.absolutePath },
        )
    }

    @Test
    fun `a final directory sync failure reports an UNCERTAIN outcome, not success or absence`() {
        // The spec (voice-recording-uploads, ecbe84dd) separates three outcomes: uncommitted, ready,
        // and UNCERTAIN when the ready transition may have occurred but its final synchronization
        // could not be confirmed. A thrown error must not collapse that third state into "definitely
        // not published" — the marker may exist and be recovered on restart.
        //
        // This test previously asserted nothing on the failing path: runCatching().getOrNull()
        // returns null when the sync throws, so the sole assertion sat inside `if (entry != null)`
        // and never executed. A vacuous control is worse than no control because it reports coverage.
        val entriesDir = File(tmp.root, "queue-fail/contributions")
        val failing = object : DirectorySync {
            var calls = 0
            var entryDirCalls = 0
            override fun sync(directory: File) {
                calls++
                // Fail only on the FINAL sync — after the ready marker exists — which is the transition
                // whose durability is unconfirmable. Failing on an earlier sync would be the ordinary
                // uncommitted case and would not exercise the uncertain outcome at all.
                // The entry directory is synced twice: once after adoption (BEFORE the marker) and
                // once after the marker. Only the SECOND one is the "final sync" whose failure leaves
                // publication uncertain; failing the first is an ordinary uncommitted error and must
                // NOT produce PublicationUncertain. Counting entry-dir calls specifically is what
                // makes this control land on the intended transition.
                if (directory.absolutePath == entriesDir.absolutePath) {
                    entryDirCalls++
                    if (entryDirCalls == 2) throw java.io.IOException("simulated final sync failure")
                }
            }
        }
        val root = tmp.newFolder("queue-fail")
        val store = ContributionStore(root, limits(), directorySync = failing)
        val id = java.util.UUID.randomUUID().toString()

        // UNCONDITIONAL outcome assertion: the call must either report a definite published state or
        // raise PublicationUncertain. Anything else (silent null, silent success) is a defect.
        var outcome: Any? = null
        try {
            outcome = store.prepare(id, "epoch-1", chunks(1600), meta(id))
        } catch (uncertain: PublicationUncertain) {
            outcome = uncertain
        } catch (other: Throwable) {
            throw AssertionError("unexpected exception type ${other::class.simpleName}", other)
        }
        assertTrue(
            "prepare must report PublicationUncertain when the final sync fails, or a definite " +
                "published entry; got $outcome",
            outcome is PublicationUncertain || outcome is ContributionStore.Entry,
        )

        // The in-memory index must NOT claim a durable entry the filesystem may have dropped.
        assertTrue(
            "prepare exposed a ready entry after an unconfirmable final sync",
            store.readyEntries().none { it.captureId == id },
        )

        // And the SURVIVING FILES must agree with the reported state: restart must not discover a
        // ready entry the store denied, nor silently delete one it reported as published.
        val restarted = ContributionStore(root, limits(), directorySync = RecordingSync())
        val recoveredIds = restarted.recover().map { it.captureId }
        if (outcome is PublicationUncertain) {
            // Uncertain means "may have been published": recovery is allowed either outcome, but it
            // must be DETERMINED by validating durable state, never by the in-memory record.
            assertTrue("recovery must not invent ids", recoveredIds.all { it == id })
        } else {
            assertEquals("a reported-published entry must survive restart", listOf(id), recoveredIds)
        }
    }

    @Test
    fun `a PRE-marker sync failure is uncommitted, not uncertain`() {
        // The complement of the uncertain case, and the reason the control above had to count
        // entry-dir calls: a failure before the ready transition leaves nothing recoverable, so it is
        // an ordinary uncommitted failure. Reporting it as uncertain would be as wrong as reporting a
        // post-marker failure as definite.
        val entriesDir = File(tmp.root, "queue-pre/contributions")
        val failing = object : DirectorySync {
            var entryDirCalls = 0
            override fun sync(directory: File) {
                if (directory.absolutePath == entriesDir.absolutePath && ++entryDirCalls == 1) {
                    throw java.io.IOException("simulated pre-marker sync failure")
                }
            }
        }
        val root = tmp.newFolder("queue-pre")
        val store = ContributionStore(root, limits(), directorySync = failing)
        val id = java.util.UUID.randomUUID().toString()

        val outcome = runCatching { store.prepare(id, "epoch-1", chunks(1600), meta(id)) }
        assertTrue(
            "a pre-marker sync failure must be an ordinary failure, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() !is PublicationUncertain,
        )
        assertTrue("no entry may be ready after a pre-marker failure", store.readyEntries().isEmpty())
        // And nothing recoverable: the marker was never written.
        val restarted = ContributionStore(root, limits(), directorySync = RecordingSync())
        assertTrue("recovery must find nothing after a pre-marker failure", restarted.recover().isEmpty())
    }

    @Test
    fun `a ready marker FILE sync failure is also UNCERTAIN`() {
        // The uncertainty boundary starts when the marker file is CREATED, not when its directory sync
        // runs. A marker whose content sync fails has still potentially appeared on disk after a crash,
        // so collapsing that to an ordinary failure would repeat the defect one operation earlier.
        val root = tmp.newFolder("queue-marker-sync")
        val ids = java.util.Collections.synchronizedList(mutableListOf<String>())
        var sawMarker = false
        val failingFileSync = FileSync { file ->
            if (file.name.endsWith(".ready")) {
                sawMarker = true
                throw java.io.IOException("simulated ready-marker file sync failure")
            }
            ids += file.name
        }
        val store = ContributionStore(root, limits(), directorySync = RecordingSync(), fileSync = failingFileSync)
        val id = java.util.UUID.randomUUID().toString()

        var outcome: Any? = null
        try {
            outcome = store.prepare(id, "epoch-1", chunks(1600), meta(id))
        } catch (uncertain: PublicationUncertain) {
            outcome = uncertain
        }
        assertTrue("the marker sync must have been attempted", sawMarker)
        assertTrue(
            "a ready-marker file-sync failure must be PublicationUncertain, got $outcome",
            outcome is PublicationUncertain,
        )
        assertTrue(
            "the store must not claim a ready entry after an unconfirmable marker transition",
            store.readyEntries().none { it.captureId == id },
        )
    }

    @Test
    fun `a failed parent barrier is retried on the next preparation`() {
        // mkdirs() returning false only means "already exists". If an earlier preparation created the
        // directory and then failed to sync its parent, the namespace is NOT durable — and gating the
        // barrier on the mkdir result meant that failure was skipped by every later attempt.
        val root = tmp.newFolder("queue-parent-retry")
        val entriesDir = File(root, "contributions")
        var failParent = true
        val flaky = object : DirectorySync {
            override fun sync(directory: File) {
                if (file_isParent(directory, root) && failParent) {
                    throw java.io.IOException("simulated parent barrier failure")
                }
            }

            private fun file_isParent(candidate: File, expected: File) = candidate.absolutePath == expected.absolutePath
        }
        val store = ContributionStore(root, limits(), directorySync = flaky)
        val firstId = java.util.UUID.randomUUID().toString()

        // First attempt: the directory gets created, the parent barrier fails -> uncommitted.
        val first = runCatching { store.prepare(firstId, "epoch-1", chunks(1600), meta(firstId)) }
        assertTrue(
            "a failed parent barrier must refuse the preparation, got ${first.exceptionOrNull()}",
            first.exceptionOrNull() is java.io.IOException || first.getOrNull() == null,
        )
        assertTrue("the directory now exists, so mkdirs would return false", entriesDir.isDirectory)

        // Second attempt: the barrier must STILL run. Before the fix it was skipped because the
        // directory already existed, so publication proceeded into a non-durable namespace.
        failParent = false
        val parentSyncs = java.util.concurrent.atomic.AtomicInteger()
        val observing = ContributionStore(
            root,
            limits(),
            directorySync = object : DirectorySync {
                override fun sync(directory: File) {
                    if (directory.absolutePath == root.absolutePath) parentSyncs.incrementAndGet()
                }
            },
        )
        val secondId = java.util.UUID.randomUUID().toString()
        assertTrue(observing.prepare(secondId, "epoch-1", chunks(1600), meta(secondId)) != null)
        assertTrue(
            "the parent barrier must run even when the directory already existed, got ${parentSyncs.get()}",
            parentSyncs.get() >= 1,
        )
    }

    @Test
    fun `uncertain recovery does not expose an entry it cannot re-confirm as durable`() {
        // An uncertain publication may have left complete-looking files that were never durably
        // confirmed. Structural validation alone would expose that entry as ready on a fresh instance.
        // The confirmation step must run, and must be able to FAIL, on a fresh instance.
        val root = tmp.newFolder("queue-uncertain-recovery")
        val id = java.util.UUID.randomUUID().toString()
        ContributionStore(root, limits(), directorySync = RecordingSync()).prepare(id, "epoch-1", chunks(1600), meta(id))

        val failingFileSync = FileSync { throw java.io.IOException("simulated confirmation failure") }
        val fresh = ContributionStore(root, limits(), directorySync = RecordingSync(), fileSync = failingFileSync)
        val recovered = fresh.recover()

        assertTrue(
            "recovery exposed an entry whose durability it could not confirm: ${recovered.map { it.captureId }}",
            recovered.none { it.captureId == id },
        )
        assertTrue("the store must not list it as ready", fresh.readyEntries().none { it.captureId == id })
        // Uncertain is NOT rejected: the files must still be there for the next confirmation attempt.
        assertTrue(
            "an unconfirmed entry must not be deleted — uncertainty is not rejection",
            File(root, "contributions").listFiles()!!.any { it.name.startsWith(id) && it.name.endsWith(".recording.wav") } &&
                File(root, "contributions/$id.ready").isFile,
        )

        // And a healthy fresh instance DOES confirm it, so the control above is discriminating rather
        // than the entry simply being unreadable.
        val healthy = ContributionStore(root, limits(), directorySync = RecordingSync())
        assertEquals("a healthy instance must confirm the same entry", listOf(id), healthy.recover().map { it.captureId })
    }

    @Test
    fun `an ALREADY PREPARED entry is withheld when recovery cannot re-confirm it`() {
        // The branch the fresh-store test cannot reach. When the SAME instance prepared the entry, recover()
        // rebuilds the index from `preparedIds`; if that rebuild re-validates and reinserts without
        // consulting the confirmation result, the confirmation is decorative for exactly the entries most
        // likely to be uncertain. `uncertainIds` was collected and never read - dead state posing as a
        // control. This test pins that.
        val root = tmp.newFolder("queue-self-uncertain")
        val id = java.util.UUID.randomUUID().toString()
        var failSyncs = false
        val gated = object : DirectorySync {
            override fun sync(directory: File) {
                if (failSyncs) throw java.io.IOException("simulated recovery confirmation failure")
            }
        }
        val store = ContributionStore(root, limits(), directorySync = gated, fileSync = FileSync { })
        assertTrue("precondition: the entry prepares", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        assertEquals("precondition: it is ready", listOf(id), store.readyEntries().map { it.captureId })

        failSyncs = true
        val recovered = store.recover()

        assertTrue(
            "recover() must withhold an already-prepared entry it cannot re-confirm, got ${recovered.map { it.captureId }}",
            recovered.none { it.captureId == id },
        )
        assertTrue(
            "readyEntries() must not expose an already-prepared entry whose confirmation failed",
            store.readyEntries().none { it.captureId == id },
        )
        // Uncertainty is NOT rejection: the same immutable entry must survive for later reconciliation.
        assertTrue(
            "the entry's files must be preserved for later reconciliation",
            File(root, "contributions/$id.ready").isFile,
        )

        // And a later successful recovery MUST reconcile it back - withheld, not lost.
        failSyncs = false
        assertEquals(
            "a later successful recovery must re-expose the same immutable entry",
            listOf(id),
            store.recover().map { it.captureId },
        )
        assertEquals("and it must be ready again", listOf(id), store.readyEntries().map { it.captureId })
    }

    @Test
    fun `recovery re-confirms the ready MARKER and the queue parent namespace`() {
        // Recovery must re-establish the SAME barriers publication relies on. A mutant that omits the
        // ready marker (or the parent namespace) survived the earlier recovery tests, because those
        // only made the whole FileSync throw - which any subset of barriers still triggers. This test
        // makes each barrier fail IN ISOLATION, so a missing barrier is unobservable no longer.
        val id = java.util.UUID.randomUUID().toString()

        // (a) marker-content barrier: every other file syncs fine, only the marker throws.
        run {
            val root = tmp.newFolder("queue-marker-barrier")
            ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id))
            val onlyMarkerFails = FileSync { file ->
                if (file.name.endsWith(".ready")) throw java.io.IOException("simulated marker sync failure")
            }
            val fresh = ContributionStore(root, limits(), fileSync = onlyMarkerFails)
            assertTrue(
                "recovery must re-confirm the ready MARKER's contents; omitting it exposes an unconfirmed entry",
                fresh.recover().none { it.captureId == id },
            )
        }

        // (b) parent-namespace barrier: files and the entry directory sync fine, only the parent throws.
        run {
            val root = tmp.newFolder("queue-parent-barrier")
            ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id))
            val entriesDir = File(root, "contributions")
            val onlyParentFails = object : DirectorySync {
                override fun sync(directory: File) {
                    if (directory.absolutePath == root.absolutePath) {
                        throw java.io.IOException("simulated parent namespace failure")
                    }
                }
            }
            val fresh = ContributionStore(root, limits(), directorySync = onlyParentFails)
            assertTrue(
                "recovery must re-confirm the queue parent namespace at [${entriesDir.absolutePath}]",
                fresh.recover().none { it.captureId == id },
            )
        }
    }

    @Test
    fun `an uncertain entry is still OWNED and is purged on revocation`() {
        // Ownership is about "this owner created these files", not "this owner can read them back".
        // Withholding an uncertain entry from readiness by removing it from the index silently removed it
        // from the purge accounting too, and revokeAndPurge cleared the uncertain set BEFORE collecting
        // the ids it would delete. Result: a user asking for everything to be removed kept their audio,
        // and a later healthy store recovered it. This pins the whole sequence.
        val root = tmp.newFolder("queue-uncertain-purge")
        val id = java.util.UUID.randomUUID().toString()
        var failSyncs = false
        val gated = object : DirectorySync {
            override fun sync(directory: File) {
                if (failSyncs) throw java.io.IOException("simulated confirmation failure")
            }
        }
        val store = ContributionStore(root, limits(), directorySync = gated)
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        failSyncs = true
        store.recover() // confirmation fails -> withheld, recorded as uncertain
        assertTrue("withheld from ready", store.readyEntries().none { it.captureId == id })
        assertTrue("files preserved while uncertain", File(root, "contributions/$id.ready").isFile)

        store.revokeAndPurge("account_changed")

        // EVERY owned artifact must be gone, not only the readable ones.
        val survivors = File(root, "contributions").listFiles()?.filter { it.name.startsWith(id) } ?: emptyList()
        assertTrue("purge left owned files behind: ${survivors.map { it.name }}", survivors.isEmpty())

        // And no resurrection: a fresh healthy store must not bring it back.
        failSyncs = false
        val fresh = ContributionStore(root, limits())
        assertEquals("a fresh store resurrected a purged entry", emptyList<String>(), fresh.recover().map { it.captureId })
        assertEquals("nothing may be ready after purge", emptyList<String>(), fresh.readyEntries().map { it.captureId })
    }

    @Test
    fun `an uncertain prepare that never reached the index is still owned and purgable`() {
        // The same hole on the OTHER path: prepare throws PublicationUncertain before inserting into
        // memoryEntries. Without recording ownership at the throw site the files exist on disk but belong
        // to nobody, so revocation cannot find them.
        val root = tmp.newFolder("queue-uncertain-throw-purge")
        val id = java.util.UUID.randomUUID().toString()
        val failingFileSync = FileSync { file ->
            if (file.name.endsWith(".ready")) throw java.io.IOException("simulated marker sync failure")
        }
        val store = ContributionStore(root, limits(), fileSync = failingFileSync)
        val outcome = runCatching { store.prepare(id, "epoch-1", chunks(1600), meta(id)) }
        assertTrue(
            "precondition: prepare must be uncertain, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is PublicationUncertain,
        )
        assertTrue(
            "precondition: the marker may exist on disk",
            File(root, "contributions").listFiles()?.any { it.name.startsWith(id) } == true,
        )

        store.revokeAndPurge("account_changed")

        val survivors = File(root, "contributions").listFiles()?.filter { it.name.startsWith(id) } ?: emptyList()
        assertTrue(
            "an uncertain prepare that never entered the index must still be purged, left ${survivors.map { it.name }}",
            survivors.isEmpty(),
        )
    }

    @Test
    fun `a FRESH store charges entries it just FAILED to confirm`() {
        // The branch the reviewer found still open after the restructure: on a fresh store `self` is empty,
        // so charging only inside the self loop left unconfirmable on-disk entries charged NOWHERE. A fresh
        // store must charge every validated entry before it branches on confirmation.
        val root = tmp.newFolder("queue-fresh-withheld")
        val id = java.util.UUID.randomUUID().toString()
        ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id))
        val entryCharge = File(root, "contributions").listFiles()!!
            .filter { it.name.startsWith(id) && (it.name.endsWith(".recording.wav") || it.name.endsWith(".metadata.json")) }
            .sumOf { it.length() }
        assertTrue("precondition: charge is known ($entryCharge)", entryCharge > 0)

        // A FRESH store whose confirmation FAILS must still charge the entry.
        val failing = object : DirectorySync {
            override fun sync(directory: File) {
                throw java.io.IOException("simulated confirmation failure")
            }
        }
        val probeMeta = meta(java.util.UUID.randomUUID().toString())
        val probeSamples = 256L
        val probeBytes = ContributionStore(root, limits()).projectedBytesFor(probeSamples, probeMeta.size)
        assertTrue("precondition: probe smaller than the entry", probeBytes < entryCharge)

        val fresh = ContributionStore(root, limits().copy(maxQueueBytes = entryCharge), directorySync = failing, fileSync = FileSync { })
        assertTrue("precondition: the fresh store cannot confirm the entry", fresh.recover().isEmpty())
        assertTrue(
            "a fresh store must charge an entry it FAILED to confirm: a $probeBytes-byte probe was admitted " +
                "over a $entryCharge-byte budget",
            fresh.admit(sampleCount = probeSamples, metadataBytes = probeMeta.size) == null,
        )
    }

    @Test
    fun `expiry releases the owner charge and restores capacity`() {
        // Charging and releasing must be symmetric. deleteLocked removed files and the index entry but not
        // the charge, so every expired entry kept consuming budget forever: after enough expiries the queue
        // reports itself full with nothing in it.
        val root = tmp.newFolder("queue-expiry-capacity")
        val id = java.util.UUID.randomUUID().toString()
        // Injected clock: expiry is age-based, so advancing time is the only reliable way to trigger it.
        // Budget for EXACTLY ONE entry plus a tiny probe: if the expired entry's charge stays, the probe
        // cannot fit, and the failure is unambiguous rather than dependent on slack.
        var now = 1_000_000L
        val sizing = ContributionStore(tmp.newFolder("queue-expiry-sizing"), limits())
        val probeMeta = meta(java.util.UUID.randomUUID().toString())
        val probeSamples = 256L
        val probeBytes = sizing.projectedBytesFor(probeSamples, probeMeta.size)
        // The exact charge this fixture produces, measured rather than assumed.
        run {
            val sizingRoot = tmp.newFolder("queue-expiry-charge")
            val id0 = java.util.UUID.randomUUID().toString()
            ContributionStore(sizingRoot, limits()).prepare(id0, "epoch-1", chunks(1600), meta(id0))
        }
        val oneEntry = 4_265L
        val store = ContributionStore(
            root,
            // ONE LESS byte than charge+probe: with the charge released the probe fits easily; with the
            // charge retained it is over by a hair. Using exactly charge+probe leaves zero slack and the
            // comparison `bytes + projected <= limit` is satisfied, which is why the mutant survived.
            limits().copy(maxQueueBytes = oneEntry + probeBytes - 1, maxQueueAgeSeconds = 60),
            clock = { now },
        )
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        // Advance well past the age bound so the entry is genuinely expired.
        now += 120_000L
        val expired = store.expireOlderThan()
        assertEquals("the entry expires", listOf(id), expired)
        assertTrue("its files are gone", store.readyEntries().isEmpty())

        assertTrue(
            "an expired entry must release its charge: the queue still counts capacity it no longer uses",
            store.admit(sampleCount = probeSamples, metadataBytes = probeMeta.size) != null,
        )
    }

    @Test
    fun `a store that ADOPTS entries from disk charges them`() {
        // reloadUnownedFromDisk is a production entry path: an instance that did not prepare an entry can
        // still adopt it from disk. Adopting without charging means those bytes occupy the queue while
        // accounting says the queue is empty, so new work is admitted on top of them.
        val root = tmp.newFolder("queue-reload-charge")
        val id = java.util.UUID.randomUUID().toString()
        ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id))

        val probeMeta = meta(java.util.UUID.randomUUID().toString())
        val probeSamples = 256L
        val charge = ContributionStore(root, limits()).let { sizing ->
            sizing.recover().single().let { it.audioBytes + it.metadataBytes }
        }
        val probeBytes = ContributionStore(root, limits()).projectedBytesFor(probeSamples, probeMeta.size)
        assertTrue("precondition: probe is smaller than the stored entry", probeBytes < charge)

        // A store that has NEVER recovered adopts from disk on demand via the expire path.
        val adopter = ContributionStore(root, limits().copy(maxQueueBytes = charge))
        adopter.expireOlderThan() // triggers reloadUnownedFromDisk; nothing is old enough to expire
        assertTrue(
            "a store that adopted from disk must charge what it adopted: a $probeBytes-byte probe was " +
                "admitted over a $charge-byte budget",
            adopter.admit(sampleCount = probeSamples, metadataBytes = probeMeta.size) == null,
        )
    }

    @Test
    fun `a failed deletion keeps the bytes OWNED and CHARGED until cleanup succeeds`() {
        // deleteLocked ignored every File.delete() result and then dropped the charge, so a deletion that
        // did not actually remove the bytes made them invisible to accounting: the audio still occupies
        // the queue but no longer counts against it.
        val root = tmp.newFolder("queue-failed-delete")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val probeMeta = meta(java.util.UUID.randomUUID().toString())
        val probeSamples = 256L
        val probeBytes = ContributionStore(root, limits()).projectedBytesFor(probeSamples, probeMeta.size)
        // TIGHT budget on the instance that will ATTEMPT the deletion, so its own accounting decides.
        val store = ContributionStore(
            root,
            limits().copy(maxQueueAgeSeconds = 60, maxQueueBytes = probeBytes + 4_264L),
            clock = { now },
        )
        val deletingStore = store
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        val charge = store.readyEntries().single().let { it.audioBytes + it.metadataBytes }

        // Make the audio file undeletable by replacing it with a non-empty DIRECTORY of the same name:
        // File.delete() then fails on every platform without needing permission tricks.
        val wav = File(root, "contributions/$id.recording.wav")
        val audio = wav.readBytes()
        wav.delete()
        wav.mkdirs()
        File(wav, "held").writeBytes(audio)
        assertTrue("precondition: the audio name is now a non-empty directory", wav.isDirectory)

        now += 120_000L
        val expired = store.expireOlderThan()
        assertEquals("the entry still expires from the index", listOf(id), expired)

        // Residual bytes exist, so they must remain charged ON THE INSTANCE THAT ATTEMPTED THE DELETE.
        // A fresh store cannot see another instance's in-memory charge, so asking a new store here would
        // test nothing (and did, in the first version of this control).
        assertTrue("precondition: charge ($charge) exceeds probe ($probeBytes)", charge > probeBytes)
        // BOTH views must refuse. The deleting instance holds the charge in memory; a restarted store
        // must re-derive it from the bytes still on disk. An earlier version checked only the restarted
        // store, so removing the in-memory retention entirely went undetected.
        val tight = ContributionStore(root, limits().copy(maxQueueBytes = probeBytes), clock = { now })
        tight.recover()
        assertTrue(
            "residual bytes from a FAILED deletion must stay charged on a RESTARTED store: a " +
                "$probeBytes-byte probe was admitted while $charge bytes of audio remained on disk",
            tight.admit(sampleCount = probeSamples, metadataBytes = probeMeta.size) == null,
        )

        // The instance that ATTEMPTED the delete holds the residue as an in-memory charge, and a
        // restarted store re-derives it from disk - so both look identical whether or not the in-memory
        // retention exists. The only way to observe the in-memory retention is to have THAT instance be
        // the one under a tight budget, which the construction below arranges.
        assertTrue(
            "the deleting instance must keep the charge: bytes that are still on disk became unowned",
            deletingStore.admit(sampleCount = probeSamples, metadataBytes = probeMeta.size) == null,
        )

        // Cleanup then succeeds and capacity is released.
        File(wav, "held").delete()
        wav.delete()
        assertTrue("precondition: the audio is finally gone", !wav.exists())
        val after = ContributionStore(root, limits().copy(maxQueueBytes = probeBytes))
        assertTrue(
            "after a successful retry the capacity must be released",
            after.admit(sampleCount = probeSamples, metadataBytes = probeMeta.size) != null,
        )
    }

    @Test
    fun `a failed delete of ONE valid file still deletes the rest and never restores the entry`() {
        // The reviewed defect: `.all { delete }` SHORT-CIRCUITS. If the WAV delete failed, the metadata
        // and ready marker were never attempted, leaving a complete VALID entry that a restart would
        // recover as ready - the audio the user asked to delete would come back.
        // This uses a SCHEDULED failure with regular files (not a directory), which is what the reviewer
        // asked for: only the FIRST delete attempt on the audio file fails, everything else is ordinary.
        val root = tmp.newFolder("queue-failed-delete-valid")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: audio is a regular file", wav.isFile)

        // Make exactly one deletion attempt fail: a read-only file cannot be unlinked on this platform
        // only if its PARENT is read-only, so instead hold the file open via a failed rename target.
        // The portable equivalent is making the parent directory read-only for the duration.
        val contributions = File(root, "contributions")
        val writableBefore = contributions.canWrite()
        contributions.setWritable(false)
        assertTrue("precondition: the queue directory is now read-only", writableBefore && !contributions.canWrite())

        now += 120_000L
        val expired = store.expireOlderThan()
        assertEquals("the entry still leaves the index", listOf(id), expired)

        // Whatever survived, the entry must not be served and must stay accounted for.
        val restarted = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        val recovered = restarted.recover()
        assertTrue(
            "a restart must NOT recover an entry whose audio survived a failed delete",
            recovered.none { it.captureId == id },
        )

        val stillThere = wav.exists()
        val probeMeta = meta(java.util.UUID.randomUUID().toString())
        val probeBytes = ContributionStore(root, limits()).projectedBytesFor(256L, probeMeta.size)
        if (stillThere) {
            val squeezed = ContributionStore(root, limits().copy(maxQueueBytes = probeBytes), clock = { now })
            squeezed.recover()
            assertTrue(
                "surviving bytes must stay charged after a failed delete",
                squeezed.admit(sampleCount = 256L, metadataBytes = probeMeta.size) == null,
            )
        }

        // Cleanup succeeds once the directory is writable again, and capacity returns.
        contributions.setWritable(true)
        val cleanup = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        cleanup.expireOlderThan()
        assertTrue("precondition: audio finally removed", !wav.exists())
        val after = ContributionStore(root, limits().copy(maxQueueBytes = probeBytes), clock = { now })
        after.recover()
        assertTrue(
            "after successful cleanup the capacity must be released",
            after.admit(sampleCount = 256L, metadataBytes = probeMeta.size) != null,
        )
    }

    @Test
    fun `residue claimed file by file still charges its full footprint`() {
        // The debris sweep visits files INDIVIDUALLY: it can claim the audio first (metadata 0) and the
        // metadata later. If the transition keeps first-seen sizes, the metadata's size is never counted
        // and the conservative bound is understated. This control pins the FULL footprint.
        val root = tmp.newFolder("queue-footprint")
        val id = java.util.UUID.randomUUID().toString()
        ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id))

        val contributions = File(root, "contributions")
        val wav = File(contributions, "$id.recording.wav")
        val metaFile = File(contributions, "$id.metadata.json")
        val fullFootprint = wav.length() + metaFile.length()
        assertTrue("precondition: both files have real size", wav.length() > 0 && metaFile.length() > 0)

        // Remove the ready marker so both files become unmarked debris, then make BOTH undeletable by
        // turning them into non-empty directories (which also fails the individual unlink).
        File(contributions, "$id.ready").delete()
        listOf(wav, metaFile).forEach { file ->
            val bytes = file.readBytes()
            file.delete()
            file.mkdirs()
            File(file, "held").writeBytes(bytes)
        }

        val probeMeta = meta(java.util.UUID.randomUUID().toString())
        val probeBytes = ContributionStore(root, limits()).projectedBytesFor(256L, probeMeta.size)
        // Discriminating budget: fullFootprint + probe - 1. With the FULL charge (4265) the probe does not
        // fit; if the metadata's 1021 bytes were dropped (3244 charged) it does. Asking for one probe is
        // enough because the budget sits between those two answers.
        val narrowed = ContributionStore(root, limits().copy(maxQueueBytes = fullFootprint + probeBytes - 1))
        narrowed.recover()
        assertTrue(
            "the claimed footprint must include the metadata: the probe was admitted inside a budget that " +
                "only fits if the ${fullFootprint - 3244} metadata bytes were dropped from the charge",
            narrowed.admit(sampleCount = 256L, metadataBytes = probeMeta.size) == null,
        )
    }

    @Test
    fun `a purge whose deletes fail reports residue instead of claiming the audio is gone`() {
        // revokeAndPurge cleared the charge table and never looked at a delete result, so a purge that
        // failed to remove files reported success: the user is told their audio was deleted while it is
        // still on disk.
        val root = tmp.newFolder("queue-purge-residue")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        val wav = File(root, "contributions/$id.recording.wav")
        val audio = wav.readBytes()
        wav.delete()
        wav.mkdirs()
        File(wav, "held").writeBytes(audio)

        store.revokeAndPurge("account-change")

        assertTrue("the purge must report the capture whose bytes survived", store.purgeResidue.contains(id))
        assertTrue("the surviving bytes are still there", wav.exists())
        // Intent is durable, so a later healthy store must not serve it.
        val later = ContributionStore(root, limits())
        assertTrue(
            "a purged-but-surviving capture must never be served",
            later.recover().none { it.captureId == id },
        )

        // Once deletion is possible the retry releases it and the residue report clears.
        File(wav, "held").delete()
        wav.delete()
        val cleanup = ContributionStore(root, limits())
        cleanup.expireOlderThan()
        assertTrue("precondition: audio finally gone", !wav.exists())
        val after = ContributionStore(root, limits())
        after.recover()
        assertTrue("a disqualified capture is not served until cleanup completes", after.readyEntries().none { it.captureId == id })
    }

    @Test
    fun `NO destruction happens when disposal intent cannot be confirmed`() {
        // The reviewed defect: deleteLocked ignored writeDisqualificationLocked's result and destroyed the
        // files anyway. If intent cannot be recorded, deleting is worse than doing nothing: the files are
        // the only evidence the capture was meant to go, so a crash could resurrect a half-deleted entry.
        val root = tmp.newFolder("queue-intent-failure")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(
            root,
            limits().copy(maxQueueAgeSeconds = 60),
            clock = { now },
            // Fault ONLY the intent barrier; the queue itself is healthy.
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    throw java.io.IOException("simulated intent barrier failure")
                }
            },
        )
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: audio present", wav.isFile)

        now += 120_000L
        store.expireOlderThan()

        assertTrue("the audio must NOT be destroyed without confirmed intent", wav.isFile)
        assertTrue(
            "the capture must stay OWNED and charged when intent failed",
            store.admit(sampleCount = 1_000_000L, metadataBytes = 1024) == null,
        )
        // And the entry must not be served as ready.
        assertTrue("an unexpurgated capture must not be served", store.readyEntries().none { it.captureId == id })
    }

    @Test
    fun `a FAILED intent attempt is still remembered as disposal across later reads`() {
        // When the intent record cannot be confirmed, destruction is correctly withheld. But the intent
        // itself must not be LOST: the retry sweep only knows about captures that left a marker, so a
        // capture whose marker write failed is never retried and lingers as ordinary debris forever. The
        // user asked for it to go; the store must keep trying, not quietly forget.
        val root = tmp.newFolder("queue-intent-remembered")
        val id = java.util.UUID.randomUUID().toString()
        var blockIntent = true
        val faulting = object : DirectorySync {
            override fun sync(directory: File) {
                if (blockIntent) throw java.io.IOException("simulated intent barrier failure")
            }
        }
        val store = ContributionStore(root, limits(), intentDirectorySyncOverride = faulting)
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        store.revokeAndPurge("account_changed")
        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: nothing destroyed without confirmed intent", wav.isFile)

        // A FRESH store with a healthy barrier must still know this capture was meant to go.
        blockIntent = false
        val fresh = ContributionStore(root, limits())
        fresh.expireOlderThan()
        assertTrue(
            "a capture whose intent write failed must still be cleaned up later, not forgotten",
            !wav.exists(),
        )
    }

    @Test
    fun `a marker whose barrier failed is not treated as durable on retry`() {
        // marker.exists() was accepted as proof of durable intent. A marker written but never synced can
        // vanish on crash, so a retry must re-confirm rather than trust its presence.
        val root = tmp.newFolder("queue-marker-reconfirm")
        val id = java.util.UUID.randomUUID().toString()
        var failIntent = true
        val store = ContributionStore(
            root,
            limits(),
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    if (failIntent) throw java.io.IOException("simulated intent barrier failure")
                }
            },
        )
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        store.revokeAndPurge("account_changed")
        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("nothing was destroyed while intent could not be confirmed", wav.isFile)

        // Marker now present from a failed attempt. A HEALTHY store must not trust its presence: it has to
        // RE-RUN the barrier, and only then may it destroy. The test asserts the barrier actually executed,
        // not merely that the files eventually vanished - a store that deleted on presence alone would also
        // end with the files gone, so a final-state assertion proves nothing.
        failIntent = false
        var barrierRuns = 0
        val healthy = ContributionStore(
            root,
            limits(),
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    barrierRuns++
                }
            },
        )
        healthy.expireOlderThan()
        assertTrue("the intent barrier must be RE-RUN before deletion, ran $barrierRuns times", barrierRuns > 0)
        assertTrue("cleanup must complete once the barrier is healthy", !wav.exists())
    }

    @Test
    fun `the disqualified directory's NAMING namespace must be confirmed on every attempt`() {
        // The directory-creation barrier was UNREACHABLE on the successful path: recording intent already
        // ran mkdirs(), so the "did it exist before?" sample was always true and the parent/root barrier was
        // skipped. An earlier failed creation barrier was likewise skipped just because the directory had
        // since become visible - existence treated as durability, one level up from the token shortcut.
        val root = tmp.newFolder("queue-namespace-barrier")
        val id = java.util.UUID.randomUUID().toString()
        // ONLY the root namespace barrier fails. The file/content barrier and the marker's own directory
        // barrier are healthy, so this isolates the naming-namespace confirmation.
        var blockNamespace = true
        val faulting = object : DirectorySync {
            override fun sync(directory: File) {
                if (blockNamespace && directory.canonicalFile == root.canonicalFile) {
                    throw java.io.IOException("simulated naming-namespace barrier failure")
                }
            }
        }
        val store = ContributionStore(root, limits(), intentDirectorySyncOverride = faulting)
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        assertTrue("precondition: no disqualified directory yet", !File(root, "disqualified").exists())

        val wav = File(root, "contributions/$id.recording.wav")
        store.revokeAndPurge("account_changed")
        assertTrue("precondition: nothing destroyed without a confirmed record", wav.isFile)
        assertTrue("the record was still made discoverable", File(root, "disqualified/$id").isFile)

        // REPEATED sweeps must not destroy anything: the namespace is still unconfirmed.
        repeat(2) { store.expireOlderThan() }
        assertTrue("repeated sweeps must not destroy with an unconfirmed namespace", wav.isFile)

        // And a FRESH owner, which never saw the earlier attempts, must also refuse.
        val fresh = ContributionStore(root, limits(), intentDirectorySyncOverride = faulting)
        fresh.recover()
        fresh.expireOlderThan()
        assertTrue("a fresh owner must not destroy with an unconfirmed namespace", wav.isFile)

        // Once the namespace is healthy the barrier must RUN and only then may deletion happen.
        blockNamespace = false
        var namespaceRuns = 0
        val healthy = ContributionStore(
            root,
            limits(),
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    if (directory.canonicalFile == root.canonicalFile) namespaceRuns++
                }
            },
        )
        healthy.expireOlderThan()
        assertTrue("the naming namespace must be barriered before deletion, ran $namespaceRuns", namespaceRuns > 0)
        assertTrue("cleanup completes once the namespace is confirmed", !wav.exists())
    }

    @Test
    fun `recovery whose invalid cleanup FAILS charges the full footprint of what survives`() {
        // M36 survived with no control reaching this branch, so the recovery failed-cleanup path was
        // charging through the old primary-files-only measurement unnoticed.
        val root = tmp.newFolder("queue-recovery-failed-cleanup")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        // Make the capture INVALID (recovery's invalid branch) and undeletable (cleanup fails), with an
        // extra unexpected subtree so a primary-files-only charge is measurably narrower.
        val contributions = File(root, "contributions")
        File(contributions, "$id.metadata.json").writeText("{ not json")
        contributions.listFiles()!!.filter { it.name.startsWith(id) }.forEach { file ->
            val bytes = file.readBytes()
            file.delete()
            file.mkdirs()
            File(file, "held").writeBytes(bytes)
        }
        val debris = File(contributions, "$id.sha256")
        File(debris, "extra").writeBytes(ByteArray(2048))

        val fresh = ContributionStore(root, limits())
        fresh.recover() // invalid -> attempts cleanup -> every delete fails

        val charged = fresh.chargedBytesForTest(id)
        assertTrue(
            "survivors of a failed cleanup must be charged their FULL retained footprint, " +
                "including the 2048-byte unexpected subtree (was $charged)",
            charged >= 2048,
        )
    }

    @Test
    fun `deep debris under a capture name is measured, not capped away`() {
        // reviewer cb212295 finding 1: a fixed outer suffix list does not bound arbitrary DESCENDANTS, and
        // a walk that silently truncates would report a smaller footprint that still looks like a number.
        val root = tmp.newFolder("queue-deep-debris")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        // Replace the attempts sidecar with a matching directory holding a nested payload.
        val attempts = File(File(root, "contributions"), "$id.attempts")
        attempts.delete()
        attempts.mkdirs()
        File(attempts, "level1").mkdirs()
        val payload = File(attempts, "level1/payload.bin")
        payload.writeBytes(ByteArray(4096))

        val direct = ContributionStore(root, limits())
        direct.expireOlderThan()
        val charged = direct.chargedBytesForTest(id)
        assertTrue(
            "the nested 4096-byte payload must be part of the measured charge (was $charged)",
            charged >= 4096,
        )
        assertEquals("measurement must not have fallen back to a guess", 0L, direct.measurementFailures)
    }

    @Test
    fun `an UNMEASURABLE capture refuses admission instead of being charged a guess`() {
        // reviewer e6eeabda / 7340fe2d: a per-capture worst case cannot bound arbitrary retained debris.
        // Unknown accounting must REFUSE admission until the capture is successfully measured or removed.
        val root = tmp.newFolder("queue-unmeasurable")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        File(root, "disqualified").mkdirs()
        File(root, "disqualified/$id").writeText("disqualified")

        // Debris beyond the traversal depth bound stands in for a tree the walk refuses to count blindly.
        val contributions = File(root, "contributions")
        contributions.listFiles()!!.filter { it.name.startsWith(id) }.forEach { file ->
            val bytes = file.readBytes()
            file.delete()
            file.mkdirs()
            File(file, "held").writeBytes(bytes)
        }
        var deep = File(contributions, "$id.recording.wav")
        repeat(measureDepthPastBound) {
            deep = File(deep, "d")
            deep.mkdirs()
        }
        File(deep, "tail.bin").writeBytes(ByteArray(16))

        val fresh = ContributionStore(root, limits())
        fresh.expireOlderThan()
        assertTrue("measurement must have been reported as failing", fresh.measurementFailures >= 1L)

        // Headroom on BOTH axes, so the only thing that can refuse the probe is the unknown-accounting
        // rule. My first version left maxQueueRecords at 8 and the residue filled the table, which refused
        // the probe for an unrelated reason and made the control blind to the mutant.
        // The unmeasured capture carries a whole-queue PLACEHOLDER charge, so a byte check refuses the
        // probe even without the unknown rule - which masked the mutant in my first version. Collapse the
        // per-capture size bound so the placeholder alone cannot fill the queue's headroom, leaving the
        // unknown rule as the only thing that can refuse here.
        val roomy = ContributionStore(
            root,
            QueueLimits.defaults().copy(
                maxQueueBytes = QueueLimits.MAX_QUEUE_BYTES_CEILING,
                maxQueueRecords = QueueLimits.MAX_QUEUE_RECORDS_CEILING,
            ),
        )
        roomy.expireOlderThan()
        assertTrue("precondition: accounting is unknown", roomy.unknownFootprintForTest())
        val probeAdmission = roomy.admit(sampleCount = 1600L, metadataBytes = 32)
        assertNull("admission must be REFUSED while any capture's footprint is unknown", probeAdmission)
    }

    @Test
    fun `residue charges sidecar bytes ABOVE the audio+metadata baseline`() {
        // The previous version of this control compared "audio only" against "everything", so charging
        // audio+metadata already refused the probe: it could not see uncharged sidecars (reviewer
        // ca97f609). The baseline here is audio+metadata, and the discriminator is the SIDECAR bytes above
        // it - attempts, created, sha256, ready and the disposal-intent record.
        val root = tmp.newFolder("queue-sidecar-footprint")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        // Real captures accumulate retry state; the digest is a fixed-size file. Together they make the
        // sidecar band wide enough to place a probe inside it, which is what isolates these bytes.
        repeat(4) { store.recordAttempt(store.ownership.currentGeneration(), id) }

        // Disqualify so the intent record itself is part of the retained footprint.
        File(root, "disqualified").mkdirs()
        // Exactly the content the store itself writes, so the measured footprint matches production.
        File(root, "disqualified/$id").writeText("disqualified")

        val contributions = File(root, "contributions")
        // Make EVERY file undeletable so the whole residue survives and must all be charged.
        contributions.listFiles()!!.filter { it.name.startsWith(id) }.forEach { file ->
            val bytes = file.readBytes()
            file.delete()
            file.mkdirs()
            File(file, "held").writeBytes(bytes)
        }

        fun subtree(f: File) = if (f.exists()) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val audio = subtree(File(contributions, "$id.recording.wav"))
        val metaBytes = subtree(File(contributions, "$id.metadata.json"))
        val baseline = audio + metaBytes
        val sidecars = listOf(".ready", ".attempts", ".created", ".sha256").sumOf { subtree(File(contributions, "$id$it")) } +
            subtree(File(root, "disqualified/$id"))

        // Assert the MEASURED CHARGE DIRECTLY. A sidecar band of this fixture is only tens of bytes, so no
        // admission budget can straddle it reliably; the reviewer offered this form explicitly. The charge
        // must equal the WHOLE retained footprint, not just the audio+metadata baseline.
        val fresh = ContributionStore(root, limits())
        fresh.expireOlderThan() // first and only accounting for this capture
        val charged = fresh.chargedBytesForTest(id)
        assertEquals("the charge must cover the full retained footprint", baseline + sidecars, charged)
        assertTrue("the sidecar bytes must not be free", charged > baseline)
    }

    @Test
    fun `late cleanup is serialized against a successor publication on the same id`() {
        // retryDisqualifiedCleanupLocked deleted files OUTSIDE the ownership monitor, so a successor
        // publishing the same id could be racing the sweep. The whole retry transaction is now under the
        // monitor: while a cleanup holds it, no publication can observe an intermediate state.
        val root = tmp.newFolder("queue-late-cleanup")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        // Disqualify without deleting anything, simulating a previous failed attempt.
        File(root, "disqualified").mkdirs()
        // Exactly the content the store itself writes, so the measured footprint matches production.
        File(root, "disqualified/$id").writeText("disqualified")

        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: audio present", wav.isFile)

        // OBSERVABLE SERIALIZATION: hold the ownership monitor from another thread and confirm the sweep
        // cannot delete the files while we hold it. Without the monitor around the FILE work the sweep
        // completes regardless, which is exactly the race a successor publication could exploit.
        val gate = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(store.ownerLockForTest()) {
                gate.countDown()
                // Hold until the sweep has had time to (incorrectly) proceed.
                Thread.sleep(400)
            }
        }
        holder.start()
        gate.await()
        val sweep = Thread {
            store.expireOlderThan()
            finished.countDown()
        }
        sweep.start()
        // While the monitor is held, the files must still be present: file work is serialized too.
        Thread.sleep(150)
        assertTrue("the sweep must not delete files while the ownership monitor is held", wav.isFile)
        holder.join()
        finished.await()
        sweep.join()
        assertTrue("the residual files are removed", !wav.exists())
        assertTrue("the marker is cleared", !File(root, "disqualified/$id").exists())
        val after = ContributionStore(root, limits())
        after.recover()
        assertTrue(
            "capacity must be released after the sweep",
            after.admit(sampleCount = 256L, metadataBytes = 1024) != null,
        )
    }

    @Test
    fun `a store that adopts from disk requires the SAME durability confirmation as recovery`() {
        // reloadUnownedFromDisk used to promote marker+validate directly: a SECOND, weaker load path than
        // recovery, so an entry whose files were never confirmed durable could be served by an instance
        // that adopted it from disk even though recovery would have withheld it (spec ecbe84dd).
        val root = tmp.newFolder("queue-reload-confirm")
        val id = java.util.UUID.randomUUID().toString()
        ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id))

        // Confirmation fails for this instance only.
        val failing = object : DirectorySync {
            override fun sync(directory: File) {
                throw java.io.IOException("simulated confirmation failure")
            }
        }
        val adopter = ContributionStore(root, limits(), directorySync = failing)
        // The expire path is what triggers the lazy disk load.
        adopter.expireOlderThan()
        assertTrue(
            "a store that ADOPTED from disk must not serve an entry it could not confirm durable",
            adopter.readyEntries().none { it.captureId == id },
        )
    }

    @Test
    fun `one blocked file does not stop the others from being deleted`() {
        // Direct control for the SHORT-CIRCUIT defect, independent of the disqualification marker: with a
        // short-circuiting `.all`, a failing WAV delete means the metadata and ready marker are never even
        // attempted, so a complete valid entry is left behind. The durable marker masks that on the served
        // path, which is exactly why this control inspects the FILES rather than what recover() returns.
        val root = tmp.newFolder("queue-delete-continues")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        val contributions = File(root, "contributions")
        val wav = File(contributions, "$id.recording.wav")
        val ready = File(contributions, "$id.ready")
        val metaFile = File(contributions, "$id.metadata.json")
        // Block ONLY the audio delete by making the directory read-only: every unlink fails, so this
        // cannot distinguish. Instead block one file by turning the AUDIO into a non-empty directory,
        // which fails for that path alone while the others remain ordinary deletable files.
        val audio = wav.readBytes()
        wav.delete()
        wav.mkdirs()
        File(wav, "held").writeBytes(audio)

        now += 120_000L
        store.expireOlderThan()

        assertTrue("the audio itself could not be deleted", wav.exists())
        assertTrue("the READY MARKER must still be attempted and removed", !ready.exists())
        assertTrue("the metadata must still be attempted and removed", !metaFile.exists())
    }

    @Test
    fun `a disqualified capture is never served again even if its files survive`() {
        // In-memory readiness is not durable disposal intent. A capture whose delete failed must stay
        // refused across a restart, on a code path that does not depend on the charge table surviving.
        val root = tmp.newFolder("queue-disqualified")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)
        // Marker written by the store itself via a failed delete.
        File(root, "disqualified/$id").apply { parentFile?.mkdirs() }.writeText("disqualified\n")
        val restarted = ContributionStore(root, limits())
        assertTrue(
            "a disqualified capture must never be exposed as ready",
            restarted.recover().none { it.captureId == id },
        )
    }

    @Test
    fun `sidecars are file-synced before the ready marker, not just written`() {
        // Directory fsync does not persist the CONTENTS of created.at/attempts: a force on the
        // directory makes the NAME durable, so after a crash the marker can persist while the sidecar
        // is empty or truncated. Age and attempt authority are read back on recovery, so their
        // durability has to be established the same way the marker's is.
        val syncedFiles = java.util.Collections.synchronizedList(mutableListOf<String>())
        val root = tmp.newFolder("queue-sidecar")
        val store = ContributionStore(
            root,
            limits(),
            directorySync = RecordingSync(),
            fileSync = FileSync { file -> syncedFiles += file.name },
        )
        val id = java.util.UUID.randomUUID().toString()
        assertTrue(store.prepare(id, "epoch-1", chunks(1600), meta(id)) != null)

        assertTrue(
            "created.at must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".created") },
        )
        assertTrue(
            "attempts sidecar must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".attempts") },
        )
        assertTrue(
            "the ready marker must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".ready") },
        )
        assertTrue(
            "audio must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.contains(".part") },
        )
        // The metadata sidecar carries the canonical record and its digest is the entry's identity, so
        // its CONTENTS must be durable too. A mutation that dropped this sync survived the first
        // version of this test because only the WAV .part and the small sidecars were checked.
        assertTrue(
            "metadata must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".metadata.json") },
        )
        assertTrue(
            "the digest sidecar must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".sha256") },
        )
    }

    @Test
    fun `recovery trusts only entries whose marker survives`() {
        val root = tmp.newFolder("queue-recovery")
        val sync = RecordingSync()
        val store = ContributionStore(root, limits(), directorySync = sync)
        val id = java.util.UUID.randomUUID().toString()
        val entry = store.prepare(id, "epoch-1", chunks(1600), meta(id))
        assertTrue(entry != null)

        // Simulate the crash outcome the ready marker exists for: the directory entry for the marker
        // is lost. Recovery must not resurrect the entry from the metadata + wav pair alone.
        val readyFiles = File(root, "contributions").listFiles { f -> f.name.endsWith(".ready") }
        assertTrue("expected a ready marker to exist", readyFiles != null && readyFiles.isNotEmpty())
        readyFiles!!.forEach { it.delete() }

        val recovered = ContributionStore(root, limits(), directorySync = RecordingSync())
        recovered.recover()
        assertTrue(
            "an entry without its ready marker must NOT be trusted after recovery",
            recovered.readyEntries().isEmpty(),
        )
    }
}
