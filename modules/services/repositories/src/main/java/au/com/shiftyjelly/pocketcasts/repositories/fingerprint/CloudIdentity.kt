package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight cloud identity (cloud-identity.md). The client generates a
 * stable random user ID on first launch, persists it locally, and sends the
 * full `user_<uuid>` string verbatim in `Authorization: Bearer <user_id>`.
 * The server treats the raw bearer value as `users.id` (trust-on-first-use).
 */
@Singleton
class CloudIdentity @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the stable user ID, creating and persisting it on first call. */
    fun userId(): String {
        prefs.getString(KEY_USER_ID, null)?.let { return it }
        val id = generateUserId()
        prefs.edit().putString(KEY_USER_ID, id).apply()
        return id
    }

    /** Test seam. */
    @VisibleForTesting
    fun clear() {
        prefs.edit().remove(KEY_USER_ID).apply()
    }

    companion object {
        private const val PREFS_NAME = "auris_cloud"
        private const val KEY_USER_ID = "user_id"

        /** User IDs are client-generated UUIDv4 with a `user_` prefix (cloud-identity.md). */
        @VisibleForTesting
        fun generateUserId(): String = "user_" + UUID.randomUUID()
    }
}
