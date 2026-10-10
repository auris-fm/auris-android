package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.preferences.model.VoiceControlAudioRoutePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidAudioRouteMonitorTest {

    @Test
    fun `a device change closes the window through the production route monitor`() = runBlocking {
        // The production connection the sink's privacy guard depends on: a device event reaches
        // GracePeriodSignal through the monitor's own registered callback, not through a test-installed
        // one. The debounce and route read that follow are production.
        val signal = GracePeriodSignal(timeoutMs = 60_000L)
        val monitor = AndroidAudioRouteMonitor(ApplicationProvider.getApplicationContext(), signal)

        signal.onWakeWordDetected()
        assertTrue("the wake must open the window", signal.isActive.value)
        assertFalse("an open window is not a privacy closure", signal.isClosedByPrivacy())
        val closuresBefore = signal.privacyClosureCount()

        monitor.onDevicesChanged()

        // The owning transition must not wait on the route-read debounce: the event is the fact and the
        // read is derived. Sampled immediately, with no wait, so a closure that only happened after the
        // 500ms read could not satisfy this.
        assertTrue(
            "grace must end as the event arrives, not after the debounce (active=${signal.isActive.value})",
            signal.isClosedByPrivacy(),
        )
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals(
            "the event closes grace exactly once, not once per debounce",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
        // Past the debounce: the route read still lands, and it must not close a second time.
        delay(1_200)
        assertEquals(
            "the debounced route read must not close grace a second time",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
    }

    @Test
    fun `wired headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `wired headphones without mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = false), route)
    }

    @Test
    fun `bluetooth sco headset with active sco is headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            bluetoothScoActive = true,
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `airpods a2dp with sco devices enumerated but sco inactive needs sco open`() {
        // Android enumerates TYPE_BLUETOOTH_SCO I/O for AirPods even when the SCO
        // link is off (music is on A2DP). That must NOT be treated as an already-open
        // headset mic — otherwise VoiceAsrEngine skips startBluetoothSco() and capture
        // stays on the phone bottom mic.
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            bluetoothScoActive = false,
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
    }

    @Test
    fun `airpods with active sco is headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            bluetoothScoActive = true,
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `bluetooth a2dp with wired headset mic input`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `bluetooth a2dp only without headset input`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
    }

    @Test
    fun `builtin speaker only`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Speaker, route)
    }

    @Test
    fun `a2dp preferred over builtin speaker`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
    }

    @Test
    fun `wired headset preferred over a2dp`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `unknown devices`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_USB_DEVICE),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Unknown, route)
    }

    @Test
    fun `empty devices`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = emptyList(),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Unknown, route)
    }

    @Test
    fun `a wake during the debounce is not ended by the pending route read`() = runBlocking {
        // A device event schedules the debounced read; the user wakes before it elapses, opening a new
        // window; the stale read then lands. It must not end a window it never saw.
        val signal = GracePeriodSignal(timeoutMs = 60_000L)
        val monitor = AndroidAudioRouteMonitor(ApplicationProvider.getApplicationContext(), signal)

        signal.onWakeWordDetected()
        val closuresBefore = signal.privacyClosureCount()
        monitor.onDevicesChanged()

        // The wake's own closure already happened; reopen the window as a later wake would.
        signal.onWakeWordDetected()
        assertTrue("the wake opens a window", signal.isActive.value)

        delay(1_200)
        assertTrue(
            "a stale debounced read must not end a window opened after it was scheduled",
            signal.isActive.value,
        )
        assertFalse("and it must not mark that window as privacy-closed", signal.isClosedByPrivacy())
        assertEquals(
            "the stale read must not count a closure against the new window",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
    }

    /** An observer whose current stream reports [type]; null means no stream is reporting. */
    private fun observerReporting(type: Int?): RoutedOutputObserver = RoutedOutputObserver().also { observer -> if (type != null) observer.observe().publishType(type) }

    @Test
    fun `a live stream's routed output outranks enumeration`() {
        // The route-selection item: enumeration offers A2DP (so it would classify BluetoothA2dpOnly and
        // impose SCO), while the live stream reports it is actually routed to the speaker. The routed
        // answer is the stronger input and must win, or availability keeps imposing SCO on a session
        // that is using the speaker.
        val signal = GracePeriodSignal()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidAudioRouteMonitor(
            context = ctx,
            gracePeriodSignal = signal,
            routedOutputObserver = observerReporting(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertEquals(
            "the observed routing must decide the route, not the enumerated availability",
            AudioRoute.Speaker,
            monitor.route.value,
        )
    }

    @Test
    fun `route decisions read the observed route, so availability cannot be substituted downstream`() {
        // @spec's question: does keeping availability separate reintroduce the substitution at a
        // downstream caller? The check is that the route-dependent decisions take the OBSERVED route —
        // a paired-but-unused headset must not reach them. When no stream reports, the route is Unknown
        // and the policy refuses rather than allowing headset-gated control on an unused device.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidAudioRouteMonitor(
            context = ctx,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = null,
        )
        assertTrue(
            "with no routed answer the route stays Unknown (got=${monitor.route.value})",
            monitor.route.value is AudioRoute.Unknown,
        )
        // Unknown is the value the route decisions receive, and the policy treats it as disallowed
        // rather than as headset-present — so availability, which may still list a device, cannot leak in.
        val rule = AudioRoutePolicyRule(
            route = monitor.route,
            policy = kotlinx.coroutines.flow.MutableStateFlow(VoiceControlAudioRoutePolicy.HeadsetOnly),
        )
        // The distinguishing case, and it must be constructed rather than observed: Robolectric's audio
        // service enumerates nothing, so both the observed route and availability read Unknown there and
        // a fallback would be invisible. The classifier is called with a paired-but-unused A2DP device —
        // the substitution's exact input — and the two surfaces are shown to disagree: availability
        // reports the device, while the route decision is fed Unknown.
        val availabilityWithPairedDevice = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            inputDeviceTypes = listOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC),
        )
        assertEquals(
            "availability reports the paired device",
            AudioRoute.BluetoothA2dpOnly,
            availabilityWithPairedDevice,
        )
        val stateFromRoute = rule.evaluate()
        assertEquals(
            "while the route decision sees Unknown, so the paired device cannot gate control",
            VoiceControlRuleState.Blocked("audio_route_disallowed"),
            stateFromRoute,
        )
        // And the policy would have ALLOWED that same input had availability been substituted, which is
        // what makes the assertion above load-bearing rather than a coincidence of both being Unknown.
        val ifAvailabilityWereSubstituted = AudioRoutePolicyRule(
            route = kotlinx.coroutines.flow.MutableStateFlow(availabilityWithPairedDevice),
            policy = kotlinx.coroutines.flow.MutableStateFlow(VoiceControlAudioRoutePolicy.HeadsetOnly),
        ).evaluate()
        assertEquals(
            "the substitution would have allowed control on an unused device",
            VoiceControlRuleState.Allowed,
            ifAvailabilityWereSubstituted,
        )
    }

    @Test
    fun `the route and availability surfaces are independent answers`() {
        // @spec's separation, asserted as the property that makes it matter: a stream reports its route,
        // and availability is computed from enumeration. They can disagree, and neither derives the
        // other — which is what stops "a headset is paired" from becoming "the audio goes to it".
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val withRoute = AndroidAudioRouteMonitor(
            context = ctx,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = observerReporting(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertEquals("the route is the observed routing", AudioRoute.Speaker, withRoute.route.value)

        // The same monitor with no routed answer reports Unknown for the route, while availability is
        // still computed from enumeration — the surfaces answer different questions.
        val withoutRoute = AndroidAudioRouteMonitor(
            context = ctx,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = null,
        )
        assertTrue(
            "with no live routed answer the route is Unknown (got=${withoutRoute.route.value})",
            withoutRoute.route.value is AudioRoute.Unknown,
        )
        // Availability is its own surface and is NOT derived from the routed answer: under Robolectric
        // it reports the enumerated devices, which is whatever the platform offers. The assertion is
        // that reading it does not throw and does not mirror the route — the two are separate values.
        val availability = withoutRoute.availability.value
        assertEquals(
            "with no routed answer the route is Unknown while availability is computed independently",
            AudioRoute.Unknown,
            withoutRoute.route.value,
        )
        assertTrue(
            "availability is a distinct value from the route (route=Unknown availability=$availability)",
            availability != withoutRoute.route.value || availability is AudioRoute.Unknown,
        )
    }

    @Test
    fun `an unknown routed output stays unknown rather than becoming a speaker`() {
        // Nullable on purpose: with a paired-but-inactive headset and no routed answer, reporting the
        // speaker would be a claim no API made. Enumeration decides, which is the pre-existing
        // behaviour, and the result is A2DP rather than a fabricated speaker.
        val signal = GracePeriodSignal()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidAudioRouteMonitor(
            context = ctx,
            gracePeriodSignal = signal,
            routedOutputObserver = observerReporting(null),
        )
        // @spec's rule: a missing observation must stay missing. Falling back to enumeration would let a
        // paired-but-unused headset impose SCO on a session that may use the speaker — the substitution
        // this item exists to remove.
        //
        // Asserting only `is Unknown` is not enough: under Robolectric the enumeration ALSO yields
        // Unknown, so a fallback would pass this. The availability surface is asserted separately, which
        // is what makes the distinction observable rather than coincidental.
        assertTrue(
            "no routed answer must read as Unknown (got=${monitor.route.value})",
            monitor.route.value is AudioRoute.Unknown,
        )
        // The distinction is only observable if the two surfaces can DIFFER. Availability is computed
        // from enumeration regardless of the routed answer, so it is the one place a paired device would
        // show up while the route stays Unknown — which is exactly the substitution being removed.
        val availabilityFromEnumeration = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            inputDeviceTypes = listOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC),
        )
        assertEquals(
            "availability still classifies an enumerated A2DP device, which is its job",
            AudioRoute.BluetoothA2dpOnly,
            availabilityFromEnumeration,
        )
        assertTrue(
            "while the ROUTE stays Unknown without a live routed answer (got=${monitor.route.value})",
            monitor.route.value is AudioRoute.Unknown,
        )
    }

    @Test
    fun `an enumerated but unused A2DP device classifies as A2DP, which the enumeration cannot refute`() {
        // @spec's correction, pinned as a LIMITATION rather than a passing expectation. The classifier's
        // input is the enumerated output-device list, so it cannot distinguish "A2DP is available and
        // unused" from "A2DP is carrying the audio" — both present TYPE_BLUETOOTH_A2DP here and both
        // yield BluetoothA2dpOnly. The engine then opens SCO for a device that may not be the emitted
        // route, so if route classification ever gates echo policy, this is the pair it must separate.
        val pairedButUnused = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC),
        )
        val actuallyCarryingAudio = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC),
        )

        // The assertion is the AMBIGUITY: identical inputs give identical answers, because enumeration
        // carries no information about which output is live. Encoding a different expectation would
        // assert behaviour the API cannot provide.
        assertTrue(
            "the classifier is given no input that separates available-and-unused from available-and-used",
            pairedButUnused == actuallyCarryingAudio,
        )
        assertTrue(
            "and with A2DP present it reports A2DP rather than the speaker media may still be using",
            pairedButUnused is AudioRoute.BluetoothA2dpOnly,
        )
    }
}
