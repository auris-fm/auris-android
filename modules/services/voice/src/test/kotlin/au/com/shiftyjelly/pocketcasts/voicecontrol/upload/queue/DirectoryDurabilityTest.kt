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
        println("DIAG syncCalls=${failing.calls} outcome=$outcome")
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
