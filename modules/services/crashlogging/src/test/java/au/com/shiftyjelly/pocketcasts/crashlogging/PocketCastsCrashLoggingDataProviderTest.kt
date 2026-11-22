package au.com.shiftyjelly.pocketcasts.crashlogging

import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.crashlogging.PocketCastsCrashLoggingDataProvider.Companion.GLOBAL_TAG_APP_PLATFORM
import au.com.shiftyjelly.pocketcasts.crashlogging.fakes.FakeBuildDataProvider
import au.com.shiftyjelly.pocketcasts.crashlogging.fakes.FakeCrashReportPermissionCheck
import au.com.shiftyjelly.pocketcasts.crashlogging.fakes.FakeEncryptedLogging
import au.com.shiftyjelly.pocketcasts.crashlogging.fakes.FakeObserveUser
import com.automattic.android.tracks.crashlogging.CrashLoggingUser
import com.automattic.android.tracks.crashlogging.ErrorSampling
import com.automattic.android.tracks.crashlogging.EventLevel
import java.io.File
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class PocketCastsCrashLoggingDataProviderTest {

    private lateinit var sut: PocketCastsCrashLoggingDataProvider
    private val fakeBuildDataProvider = FakeBuildDataProvider()
    private val fakeObserveUser = FakeObserveUser()

    @Before
    fun setUp() {
        sut = PocketCastsCrashLoggingDataProvider(
            fakeObserveUser,
            FakeCrashReportPermissionCheck(),
            fakeBuildDataProvider,
            FakeEncryptedLogging(),
            File.createTempFile("test", "test"),
            localeProvider = { null },
            connectionStatusProvider = { true },
        )
    }

    @Test
    fun `should provide specific error sampling for mobile platform`() {
        fakeBuildDataProvider.buildPlatform = "mobile"

        assertEquals(
            ErrorSampling.Enabled(0.3),
            sut.errorSampling,
        )
    }

    @Test
    fun `should disable error sampling for other platforms`() {
        val notMobilePlatforms = listOf("automotive", "watch")

        notMobilePlatforms.forEach { build ->
            fakeBuildDataProvider.buildPlatform = build

            assertEquals(ErrorSampling.Disabled, sut.errorSampling)
        }
    }

    @Test
    fun `should attach platform tag`() {
        fakeBuildDataProvider.buildPlatform = "mobile"

        runBlocking {
            assertEquals(
                sut.applicationContextProvider.last()[GLOBAL_TAG_APP_PLATFORM],
                "mobile",
            )
        }
    }

    @Test
    fun `should provide user if available`() = runTest {
        fakeObserveUser.emitUser(User("mail"))

        sut.user.test {
            assertEquals(CrashLoggingUser(userID = null, email = "mail", username = null), expectMostRecentItem())
        }
    }

    @Test
    fun `should provide null if user is unavailable`() = runTest {
        sut.user.test {
            assertEquals(null, expectMostRecentItem())
        }
    }

    @Test
    fun `should not attach a log uuid when logs cannot be queued`() {
        val fake = FakeEncryptedLogging()
        val provider = PocketCastsCrashLoggingDataProvider(
            fakeObserveUser,
            FakeCrashReportPermissionCheck(),
            fakeBuildDataProvider,
            KeyGuardedEncryptedLogging(fake, ""),
            File.createTempFile("test", "test"),
            localeProvider = { null },
            connectionStatusProvider = { true },
        )

        val extras = provider.provideExtrasForEvent(emptyMap(), EventLevel.FATAL)

        assertTrue(extras.isEmpty())
        assertTrue(fake.toUpload.isEmpty())
    }

    @Test
    fun `should attach a log uuid when logs can be queued`() {
        val fake = FakeEncryptedLogging()
        val key = android.util.Base64.encodeToString(ByteArray(32), android.util.Base64.NO_WRAP)
        val provider = PocketCastsCrashLoggingDataProvider(
            fakeObserveUser,
            FakeCrashReportPermissionCheck(),
            fakeBuildDataProvider,
            KeyGuardedEncryptedLogging(fake, key),
            File.createTempFile("test", "test"),
            localeProvider = { null },
            connectionStatusProvider = { true },
        )

        val extras = provider.provideExtrasForEvent(emptyMap(), EventLevel.FATAL)

        assertEquals(setOf(PocketCastsCrashLoggingDataProvider.EXTRA_UUID), extras.keys)
        assertEquals(1, fake.toUpload.size)
    }
}
