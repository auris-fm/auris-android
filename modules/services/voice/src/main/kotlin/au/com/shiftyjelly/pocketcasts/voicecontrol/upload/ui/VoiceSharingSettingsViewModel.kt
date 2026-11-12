package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
data class VoiceSharingSettingsUiState(
    val settingAvailable: Boolean,
    val enrolled: Boolean,
    val pending: Boolean,
    val status: SettingsOperationStatus,
    val failedOperation: FailedOperation = FailedOperation.NONE,
    val pendingOperation: PendingOperation = PendingOperation.NONE,
    val projectionObserved: Boolean,
    val durableChoiceKnown: Boolean = false,
    val deletionRequested: Boolean = false,
    val deleteAvailable: Boolean = false,
    val deleteConfirmationOpen: Boolean = false,
    val deletionFailed: Boolean = false,
    val disclosureVersion: String,
    val disclosureTitle: String,
    val disclosureSupporting: String,
    val disclosureDetail: String,
)
enum class SettingsOperationStatus { NONE, FAILED, RELEASE_FAILED }
enum class FailedOperation { NONE, ENROLL, WITHDRAW }
enum class PendingOperation { NONE, ENROLL, WITHDRAW }
class VoiceSharingSettingsViewModel(
    private val projection: StateFlow<VoiceSharingAccountProjection?>,
    private val refreshProjection: (suspend () -> Boolean)? = null,
    private val enrollmentController: EnrollmentController,
    private val ownerSession: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val cancelInFlightUpload: () -> Unit = {},
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val savedChoice: (suspend () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?)? = null,
    /**
     * VISIBILITY provider (spec's bot, offline-start finding): does the account have ANY durable
     * record? Kept separate from [savedChoice] because "sharing is off" is a known state — the row
     * must show offline — while the switch VALUE stays on the stricter consent read.
     */
    private val knownChoice: (suspend () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?)? = null,
) {
    private var lastFailedOperation = FailedOperation.NONE
    private var pendingOperation = PendingOperation.NONE
    private var presentedSession: Long = ownerSession()
    private var actionGeneration = 0L
    private var deletionRequested = false
    private var deletionFailed = false
    private var deletionGeneration = 0L
    private var deleteConfirmationOpen = false
    private var confirmationSession = 0L
    private var presentationStatus = SettingsOperationStatus.NONE
    /**
     * ONE LOCAL TRUTH (owner's Pixel consent bug): the switch renders the DURABLE choice — the same
     * SavedSharingChoiceStore record capture/upload admission reads — never the in-memory projection
     * alone. Fail-closed null until the first IO read lands. Every server answer updates the store,
     * so re-reading it here keeps the switch on exactly what admission would act on.
     */
    private var lastRenderedChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? = null
    /** Any durable record for the account (visibility only); null until an IO read lands. */
    private var lastKnownChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? = null
    private val mutableState = MutableStateFlow(render())
    val state: StateFlow<VoiceSharingSettingsUiState> = mutableState.asStateFlow()
    init {
        scope.launch {
            projection.collect {
                retireForeignSessionPresentation()
                mutableState.value = render()
                refreshEnrolled()
            }
        }
        refreshProjection?.let { request ->
            scope.launch { request() }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(enrollmentController.state) { _ -> }
                .collect {
                    retireForeignSessionPresentation()
                    mutableState.value = render()
                    refreshEnrolled()
                }
        }
    }
    private fun retireForeignSessionPresentation() {
        val session = ownerSession()
        if (session == presentedSession) return
        presentedSession = session
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        deletionRequested = false
        deletionFailed = false
        deleteConfirmationOpen = false
        confirmationSession = 0L
    }
    fun enroll() {
        retireForeignSessionPresentation()
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.ENROLL
        presentationStatus = SettingsOperationStatus.NONE
        val generation = ++actionGeneration
        val tappedSession = ownerSession()
        mutableState.value = render()
        scope.launch {
            if (ownerSession() != tappedSession) {
                retireTapPresentation(generation)
                return@launch
            }
            consume(enrollmentController.enroll(EnrollmentDisclosure.VERSION), FailedOperation.ENROLL, generation)
        }
    }
    fun requestDelete() {
        retireForeignSessionPresentation()
        deleteConfirmationOpen = true
        confirmationSession = ownerSession()
        mutableState.value = render()
    }
    fun dismissDelete() {
        deleteConfirmationOpen = false
        confirmationSession = 0L
        mutableState.value = render()
    }
    fun confirmDelete() {
        if (!deleteConfirmationOpen) return
        if (ownerSession() != confirmationSession) {
            dismissDelete()
            return
        }
        deleteConfirmationOpen = false
        confirmationSession = 0L
        startDeletion()
    }
    private fun startDeletion() {
        retireForeignSessionPresentation()
        deletionFailed = false
        deletionRequested = true
        val generation = ++deletionGeneration
        val tappedSession = ownerSession()
        mutableState.value = render()
        scope.launch {
            if (ownerSession() != tappedSession) return@launch
            val outcome = enrollmentController.deleteSharedData()
            if (generation != deletionGeneration || ownerSession() != tappedSession) return@launch
            when (outcome) {
                is EnrollmentOutcome.DataDeleted -> {
                    deletionRequested = false
                    deletionFailed = false
                }
                is EnrollmentOutcome.Failed, is EnrollmentOutcome.Refused -> {
                    deletionRequested = false
                    deletionFailed = true
                }
                else -> {}
            }
            mutableState.value = render()
        }
    }
    fun withdraw() {
        retireForeignSessionPresentation()
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.WITHDRAW
        presentationStatus = SettingsOperationStatus.NONE
        val generation = ++actionGeneration
        val tappedSession = ownerSession()
        scope.launch {
            acceptChoiceRead(readChoiceFenced())
            if (generation != actionGeneration) return@launch
            mutableState.value = renderFrom(isEnrolled())
            // Cancel anything already on the wire; the controller writes the durable WITHDRAWN
            // record at tap time, before its network call.
            withContext(ioDispatcher) { cancelInFlightUpload() }
            acceptChoiceRead(readChoiceFenced())
            if (generation != actionGeneration) return@launch
            if (ownerSession() != tappedSession) {
                retireTapPresentation(generation, isEnrolled())
                return@launch
            }
            mutableState.value = renderFrom(isEnrolled())
            val outcome = withContext(ioDispatcher) { enrollmentController.withdraw() }
            if (ownerSession() != tappedSession) {
                acceptChoiceRead(readChoiceFenced())
                retireTapPresentation(generation, isEnrolled())
                return@launch
            }
            applyWithdrawalOutcome(outcome, generation)
        }
    }
    private fun refreshEnrolled() {
        scope.launch { acceptChoiceRead(readChoiceFenced()) }
    }

    private suspend fun readChoiceFenced(): FencedFacts {
        val choice = withContext(ioDispatcher) { savedChoice?.invoke() }
        val known = withContext(ioDispatcher) { knownChoice?.invoke() }
        return FencedFacts(choice, known)
    }

    private fun acceptChoiceRead(read: FencedFacts) {
        lastRenderedChoice = read.choice
        lastKnownChoice = read.known
        mutableState.value = render()
    }

    /** The fenced render inputs: the durable choice and whether ANY durable record exists (row visibility). */
    private data class FencedFacts(
        val choice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?,
        val known: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? = null,
    )
    private suspend fun applyWithdrawalOutcome(outcome: EnrollmentOutcome, generation: Long) {
        when (outcome) {
            is EnrollmentOutcome.Applied -> {
                acceptChoiceRead(readChoiceFenced())
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = renderFrom(isEnrolled())
            }
            is EnrollmentOutcome.Failed, is EnrollmentOutcome.Refused -> {
                acceptChoiceRead(readChoiceFenced())
                if (generation != actionGeneration) return
                lastFailedOperation = FailedOperation.WITHDRAW
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = renderFrom(isEnrolled())
            }
            is EnrollmentOutcome.DataDeleted,
            EnrollmentOutcome.Superseded,
            -> {
                if (generation != actionGeneration) return
            }
        }
    }
    private suspend fun consume(outcome: EnrollmentOutcome, operation: FailedOperation, generation: Long) {
        when (outcome) {
            is EnrollmentOutcome.Applied -> {
                acceptChoiceRead(readChoiceFenced())
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }
            is EnrollmentOutcome.Failed -> {
                if (generation != actionGeneration) return
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }
            is EnrollmentOutcome.DataDeleted -> {
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }
            is EnrollmentOutcome.Refused -> {
                if (generation != actionGeneration) return
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }
            EnrollmentOutcome.Superseded -> {
            }
        }
    }
    private fun retireTapPresentation(generation: Long) {
        if (generation != actionGeneration) return
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        mutableState.value = render()
    }
    private fun retireTapPresentation(generation: Long, enrolled: Boolean) {
        if (generation != actionGeneration) return
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        mutableState.value = renderFrom(enrolled)
    }
    private fun render(status: SettingsOperationStatus = presentationStatus): VoiceSharingSettingsUiState = renderFrom(isEnrolled(), status)
    private fun renderFrom(
        enrolled: Boolean,
        status: SettingsOperationStatus = presentationStatus,
    ): VoiceSharingSettingsUiState {
        val observed = projection.value != null
        Timber.i(
            "voice-sharing surface: settingAvailable=%b projectionObserved=%b (session=%d) enrolled=%b",
            true,
            observed,
            ownerSession(),
            enrolled,
        )
        return VoiceSharingSettingsUiState(
            settingAvailable = true,
            enrolled = enrolled,
            pending = pendingOperation != PendingOperation.NONE,
            projectionObserved = projection.value != null,
            durableChoiceKnown = lastKnownChoice != null,
            deletionRequested = deletionRequested,
            deletionFailed = deletionFailed,
            deleteAvailable = isDeleteAvailable(),
            deleteConfirmationOpen = deleteConfirmationOpen,
            status = status,
            failedOperation = lastFailedOperation,
            pendingOperation = pendingOperation,
            disclosureVersion = EnrollmentDisclosure.VERSION,
            disclosureTitle = EnrollmentDisclosure.TITLE,
            disclosureSupporting = EnrollmentDisclosure.SUPPORTING,
            disclosureDetail = EnrollmentDisclosure.DETAIL,
        )
    }
    private fun currentProjection(): VoiceSharingAccountProjection? = projection.value
    private fun isEnrolled(): Boolean {
        // The DURABLE choice is the switch's truth (spec one-local-truth): the same record admission
        // reads. The projection only reports whether a server answer was ever observed. Fail closed:
        // no durable record, no consent.
        return lastRenderedChoice?.state == au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.ENROLLED
    }
    private fun isDeleteAvailable(): Boolean {
        val p = currentProjection() ?: return false
        return p.voiceSharingEnrolled || p.enrollmentEpoch != null
    }
}
