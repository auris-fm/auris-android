package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import android.content.Context
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * Real composition entry for the voice-sharing policy (reviewer 40ff55d1 blocker 1): this Hilt
 * module OWNS the provider's lifecycle wiring — the application-scope coroutine context, the
 * real monotonic clock, the real delay-based sleep, and the whole-refresh deadline are bound
 * here, at the same seam the rest of the app's singletons are composed. Constructing the
 * provider in a test file is not composition; binding it here and testing THIS entry is.
 *
 * The binding does not enable collection: it only instantiates the fail-closed provider, whose
 * lifecycle init observes the real Firebase adapter sequence. Enabling collection additionally
 * requires the account projection and enrollment (later plan tasks) composed at the
 * VoiceSharingController gate.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceSharingPolicyModule {

    @Provides
    @Singleton
    fun provideVoiceSharingPolicyProvider(
        @VoiceSharingRemoteConfig remoteConfig: FirebaseRemoteConfig,
        featureProvider: FirebaseRemoteFeatureProvider,
        ownership: CredentialOwnershipOwner,
        @ApplicationScope scope: CoroutineScope,
    ): VoiceSharingPolicyProvider = VoiceSharingPolicyProvider(
        remoteConfig = remoteConfig,
        featureProvider = featureProvider,
        scope = scope,
        nowMs = { SystemClock.elapsedRealtime() },
        sleepMs = { delay(it) },
        refreshTimeoutMs = VoiceSharingPolicyProvider.REFRESH_TIMEOUT_MS,
        // THE exact owner primitive — same object identity as the collection transaction's and
        // every owner transition's outermost lock. No defaults anywhere on this path.
        sharedCollectionLock = ownership.collectionSharedLock,
    )

    /**
     * The Voice-Sharing-only Remote Config, read from the NAMED SECONDARY FirebaseApp
     * `voiceSharing` (project `auris-fm`, spec dispatch 51500e55).
     *
     * WHY A SECONDARY APP: the default Firebase app is initialized from the variant's
     * google-services.json and is consumed by unrelated features; repointing it would move every
     * one of them onto the Voice Sharing project. This binding therefore builds its own
     * [FirebaseOptions] from the shipped client configuration
     * (`res/raw/voice_sharing_firebase_config.json`) and registers it under the app name
     * `voiceSharing`, so the default consumers keep their project untouched.
     *
     * FAIL-CLOSED: a missing or malformed configuration throws here rather than producing a
     * config that silently answers with the default project's values — an enrollment/policy
     * decision must never rest on the wrong project. Initialization is idempotent: the named app
     * is created once and reused.
     */
    @Provides
    @Singleton
    @VoiceSharingRemoteConfig
    fun provideVoiceSharingRemoteConfig(
        @ApplicationContext context: Context,
    ): FirebaseRemoteConfig {
        val raw = try {
            context.resources.openRawResource(au.com.shiftyjelly.pocketcasts.voicecontrol.R.raw.voice_sharing_firebase_config)
                .use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            Timber.w(e, "[VoiceSharingPolicy] secondary Firebase config resource unreadable")
            throw IllegalStateException("voice sharing secondary firebase config missing", e)
        }
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw IllegalStateException("voice sharing secondary firebase config malformed", e)
        }
        val projectInfo = json.getJSONObject("project_info")
        val client = json.getJSONArray("client").getJSONObject(0)
        val clientInfo = client.getJSONObject("client_info")
        val optionsBuilder = FirebaseOptions.Builder()
            .setApplicationId(clientInfo.getString("mobilesdk_app_id"))
            .setApiKey(client.getJSONArray("api_key").getJSONObject(0).getString("current_key"))
            .setProjectId(projectInfo.getString("project_id"))
        // THE SENDER ID LIVES AT project_info.project_number (reviewer-read source, dad748dd):
        // gcm_defaultSenderId is a per-client mirror, not the canonical value.
        projectInfo.optString("project_number", "").takeIf { it.isNotEmpty() }
            ?.let { optionsBuilder.setGcmSenderId(it) }
        val options = optionsBuilder.build()
        // getApps returns a LIST indexed by Int — select the named app by NAME, not by indexing
        // with it (compile-gate failure receipt 4a2709dc).
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(context, options, APP_NAME)
        return FirebaseRemoteConfig.getInstance(app)
    }

    private const val APP_NAME = "voiceSharing"
}
