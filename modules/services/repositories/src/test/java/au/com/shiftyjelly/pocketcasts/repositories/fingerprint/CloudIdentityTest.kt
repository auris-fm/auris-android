package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class CloudIdentityTest {

    @Test
    fun `generates a user prefixed uuid`() {
        val id = CloudIdentity.generateUserId()
        assertTrue(id.startsWith("user_"))
        // UUID v4 format after the prefix.
        val uuidPart = id.removePrefix("user_")
        assertTrue(uuidPart.length == 36)
    }

    @Test
    fun `userId is stable across instances`() {
        val first = CloudIdentity(RuntimeEnvironment.getApplication())
        val second = CloudIdentity(RuntimeEnvironment.getApplication())

        val id1 = first.userId()
        val id2 = second.userId()

        assertEquals(id1, id2)
        assertTrue(id1.startsWith("user_"))
    }

    @Test
    fun `userId persists in SharedPreferences across new instances`() {
        val context = RuntimeEnvironment.getApplication()
        val first = CloudIdentity(context)
        first.clear()

        val created = first.userId()
        val reloaded = CloudIdentity(context).userId()

        assertEquals(created, reloaded)
        assertTrue(created.startsWith("user_"))
    }
}
