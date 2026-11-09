package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

/**
 * The SERVER-VOCABULARY GATE for the `wake_only` routing reason (spec 5374333c option 2):
 * Cloud staging does not accept `wake_only` yet (head on HOLD), so emission defaults OFF and
 * flips ON only when staging confirms the vocabulary. OFF means the wake-only drop records an
 * unknown routing stage exactly as before delta 58940cbe — no wire change. This is an EMISSION
||||||| /tmp/mfb
 * The server-vocabulary gate for the `wake_only` routing reason:
 * Cloud staging does not accept `wake_only` yet (head on hold), so emission defaults off and
 * flips on only when staging confirms the vocabulary. Off means the wake-only drop records an
 * unknown routing stage exactly as before delta 58940cbe — no wire change. This is an EMISSION
 * gate only: the observation, its ordering with Recognized, and every control stay in place.
 */
object WakeOnlyRoutingEmission {
    @Volatile
    var enabled: Boolean = false
}
