package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.CaptureAllowanceProvisioner

/**
 * The provisioning decision for ONE refused utterance.
 *
 * This lives outside [VoiceAsrEngine] because the ordering inside it is the whole correctness
 * argument, and pinning it should not require constructing an engine (audio, ASR, wake word and
 * feedback collaborators) whose fakes would dominate such a control without adding anything.
 */
internal object VoiceProvisionGate {

    /**
     * Decides whether this utterance may reach the server, and does so exactly once per decision.
     *
     * RE-ARM FROM THE PROVEN STATE FIRST (reviewer 6a6eec2a gap 3). Re-arming only after the gate
     * had already been consumed meant the FIRST exhausted or expired utterance skipped the round
     * trip and refused the capture, and only the utterance AFTER it could refresh -- one whole user
     * attempt lost at exactly the batch boundary this feature exists to cross. The provable state
     * is a precondition of the decision, not a reaction to its outcome.
     *
     * An ordinary refusal leaves the gate closed (cloud 0b659d72): re-asking once per attempt is a
     * spin loop against the server, whereas a spent or expired owner is precisely a state whose
     * correct handling is "ask the server".
     *
     * Returns null when this attempt may not provision.
     */
    suspend fun decide(
        gate: java.util.concurrent.atomic.AtomicBoolean,
        provisioner: CaptureAllowanceProvisioner?,
    ): CaptureAllowanceProvisioner.Result? {
        if (provisioner != null && (provisioner.composedOwnerExhausted() || provisioner.composedOwnerExpired())) {
            gate.set(true)
        }
        // READINESS RESOLVED RE-ARMS TOO (reviewer d4cff2e5 #2/#3, spec 1a340261). A signed-out
        // session, or one whose account was not yet authoritative, produces no composed owner at
        // all -- so there is no spent/expired state to re-arm from, the gate stays closed, and
        // signing in DURING this session could never resume provisioning. Consuming the transition
        // keeps it event-driven: one re-arm per resolution, not one per utterance.
        if (provisioner != null && provisioner.consumeReadinessTransition()) {
            gate.set(true)
        }
        if (!gate.compareAndSet(true, false)) return null
        return provisioner?.provision()
    }
}
