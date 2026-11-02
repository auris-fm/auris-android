package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.AllowanceRetirementWatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceLifecycle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production composition owner for the collection boundary (reviewer 01877984 blocker 1: no
 * caller graph existed). Injected singletons with no consumer are NOT composition — this class
 * is instantiated from the application's onCreate exactly like [au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyStartup]
 * and [VoiceSharingAccountStartup], and it starts every loss subscription for real:
 *
 *   observer ← sink.onRetentionEnded wiring            (provider, VoiceObservationModule)
 *   watcher.start() → accounts listener, enrollment hook, policy flow, exact lease deadline
 */
@Singleton
class CollectionStartup @Inject constructor(
    private val watcher: CollectionLossWatcher,
    private val accountStartup: VoiceSharingAccountStartup,
    private val authority: CollectionAuthority,
    private val observer: SegmentObserver,
    private val allowanceLifecycle: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceLifecycle,
    private val allowanceRetirement: AllowanceRetirementWatcher,
    private val outcomeFinalizer: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureOutcomeFinalizer,
) {
    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        // Enrollment evidence: the state fence's post-commit hook feeds the authority AND the
        // loss watcher; both are set before any capture can start because initializeOnAppStart
        // runs at app startup, ahead of any voice session.
        watcher.start() // installs chained evidence + loss hooks (single composition point)
        // ALLOWANCE LIFECYCLE (reviewer c5d94af4 #4): a retirement purges the outstanding capture
        // through the SAME fence-gated revocation the other loss causes use, then the slot is
        // already back at the unavailable default. The watcher owns the subscriptions/deadline;
        // in-process disposal goes through AllowanceRetirementWatcher.dispose (process death
        // needs no hook - the prepared token is lost by design).
        allowanceLifecycle.onRetired = { observer.revoked("allowance-retired") }
        allowanceRetirement.start()
        // BATCH B (reviewer 399cb2e6 / spec cf1b9809): bounded outcome finalization hooks onto
        // the sink, and the ADMISSION GUARD is fail-closed: with no selected wait limits the
        // finalizer never arms and contribution admission REFUSES — no unbounded positive path.
        observer.outcomeWaitGuard = { outcomeFinalizer.limitsSelected }
        outcomeFinalizer.start()
    }
}
