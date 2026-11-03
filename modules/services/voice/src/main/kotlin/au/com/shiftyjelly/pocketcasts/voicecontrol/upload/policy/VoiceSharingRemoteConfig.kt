package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import javax.inject.Qualifier

/**
 * Marks the Voice-Sharing-ONLY Firebase Remote Config binding: a named secondary FirebaseApp
 * (project `auris-fm`) whose configuration lives in `res/raw/voice_sharing_firebase_config.json`.
 *
 * WHY A QUALIFIER: the unqualified [com.google.firebase.remoteconfig.FirebaseRemoteConfig]
 * binding serves the DEFAULT Firebase app that unrelated features consume. Replacing it would
 * move every other consumer onto the Voice Sharing project (spec correction 172783bb). Only
 * composition sites annotated with this qualifier read the `auris-fm` policy; the default app
 * and all its consumers are untouched.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceSharingRemoteConfig
