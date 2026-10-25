package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EarconPlayerTest {
    private lateinit var player: EarconPlayer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        player = EarconPlayer(context)
    }

    @Test
    fun `play does not throw for any EarconId`() {
        EarconId.entries.forEach { id ->
            try {
                player.play(id)
            } catch (e: Exception) {
                fail("Unexpected exception: $e")
            }
        }
    }

    @Test
    fun `play returns false for a raw resource that is genuinely absent`() {
        // THE ORIGINAL FORM OF THIS TEST WAS ASSERTING A FALSE PREMISE. It called
        // `play(EarconId.SUCCESS)` and expected false "when assets are not present" -- but
        // `res/raw/earcon_success.wav` DOES exist, so the real answer is true. It only passed while
        // Android resources were not being loaded, i.e. it was pinned to a test-environment artifact
        // rather than to behaviour. Now that the module loads real resources (needed by
        // RealServiceLifecycleControlTest), the correct assertions are: a PRESENT earcon plays, and a
        // genuinely unresolvable resource id degrades to false instead of crashing.
        assertTrue(
            "a present earcon resource must actually play",
            player.play(EarconId.SUCCESS),
        )

        // The missing-resource path still has to be safe. Play through a player whose context cannot
        // resolve any earcon resource, which is exactly the "assets not present" condition.
        val unresolvable = EarconPlayer(MissingResourceContext(context))
        assertFalse(
            "an unresolvable earcon resource must return false, not throw",
            unresolvable.play(EarconId.SUCCESS),
        )
    }

    @Test
    fun `release disposes resources`() {
        player.release()
        // Subsequent play should be no-op, not crash
        try {
            player.play(EarconId.SUCCESS)
        } catch (e: Exception) {
            fail("Unexpected exception: $e")
        }
    }
}

/**
 * A context whose resources resolve no earcon, which is the real "assets are missing" condition.
 *
 * Used instead of asserting the absence of an asset that in fact exists.
 */
private class MissingResourceContext(private val wrapped: Context) : android.content.ContextWrapper(wrapped) {
    // Delegate everything to the real context, but make the earcon resource lookup fail, which is
    // precisely the "assets are not present" condition the player must survive.
    override fun getResources(): android.content.res.Resources = MissingResources(wrapped.resources)
}

/** Real Resources, but every identifier resolves to 0 (i.e. not found). */
@Suppress("DEPRECATION")
private open class MissingResources(real: android.content.res.Resources) : android.content.res.Resources(real.assets, real.displayMetrics, real.configuration) {
    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0
}
