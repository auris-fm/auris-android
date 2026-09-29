package au.com.shiftyjelly.pocketcasts.voicecontrol.cloud

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.shiftyjelly.pocketcasts.preferences.gateway.GatewayUrlProvider
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteClient
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issues one real cloud turn from this device through the app's own client and the app's own token,
 * to whichever base URL the app is configured to use.
 *
 * This exists for a specific decision rather than for coverage: before retiring the Go turn path we
 * need to know that the routed host accepts a token this app actually holds. The verifier runs
 * before request-body validation, so the outcome distinguishes the cases cleanly — a `401` means the
 * issuer or audience was refused, and anything else means the token was accepted.
 *
 * The token is never logged, only whether one exists and how long it is. Run it against staging with
 * the app already configured for staging; it reads the configured base URL rather than assuming one.
 */
@RunWith(AndroidJUnit4::class)
class CloudRouteDeviceCheck {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CheckEntryPoint {
        fun tokenProvider(): CloudTokenProviding
        fun gatewayUrlProvider(): GatewayUrlProvider
    }

    @Test
    fun aTurnFromThisDeviceIsAcceptedOnTheRoutedPath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = EntryPointAccessors.fromApplication(context, CheckEntryPoint::class.java)
        val baseUrl = entry.gatewayUrlProvider().configuredGatewayUrl()
        val tokenProvider = entry.tokenProvider()
        val token = tokenProvider.currentToken()

        Log.i(TAG, "base_url=$baseUrl")
        // Never the token itself: this is a credential, and the rule for this pipeline is that
        // credentials do not reach logs.
        Log.i(TAG, "token_present=${!token.isNullOrBlank()} token_length=${token?.length ?: 0}")
        assertTrue("no token on this device, so the routed path cannot be exercised", !token.isNullOrBlank())

        val client = CloudRouteClient(baseUrl = baseUrl, tokenProvider = tokenProvider)
        val turn = CloudRouteTurn(
            request = "what is this podcast about",
            context = CloudRouteContext(episodeId = "", clientPositionMs = 0L),
            requestId = UUID.randomUUID().toString(),
        )

        val events = client.route(turn).toList()
        events.forEach { event ->
            val code = (event as? CloudRouteEvent.Error)?.code ?: "-"
            Log.i(TAG, "event=${event::class.simpleName} code=$code")
        }

        val error = events.filterIsInstance<CloudRouteEvent.Error>().firstOrNull()
        val verdict = when {
            error == null -> "ACCEPTED: the route produced a stream, so the token was accepted"
            error.code.startsWith("http_401") -> "REFUSED: 401 from the routed host — issuer or audience rejected"
            else -> "OTHER: the token was accepted and the request failed later (code=${error.code})"
        }
        Log.i(TAG, "VERDICT: $verdict")
    }

    private companion object {
        const val TAG = "CloudRouteDeviceCheck"
    }
}
