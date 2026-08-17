package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration for the Auris cloud server the client talks to
 * (cloud assistant + fingerprint reference data).
 *
 * The base URL is stored in SharedPreferences under the documented key
 * `auris_cloud_base_url`. An empty base URL disables cloud alignment — the
 * client degrades to the transcript-sync mapping and `client_position_ms`.
 * (A debug-settings entry to set this key is a follow-up.)
 */
interface CloudConfig {
    fun baseUrl(): String
}

@Singleton
class SharedPreferencesCloudConfig @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudConfig {
    override fun baseUrl(): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_BASE_URL, null)
        .orEmpty()

    companion object {
        const val PREFS_NAME = "auris_cloud"
        const val KEY_BASE_URL = "base_url"
    }
}
