package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
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
    private val withdrawalIntent: LocalWithdrawalIntentStore,
    private val ownerSession: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val cancelInFlightUpload: () -> Unit = {},
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val markerRead: (suspend () -> Boolean)? = null,
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
    /** Fail-closed seed: the real marker arrives from the collectors' IO read (no main-thread disk read at construction). */
    private var lastRenderedMarker: Boolean = false
    private var markerReadGeneration = 0L
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
            val openedRead = readMarkerFenced()
            acceptMarkerRead(openedRead)
            if (generation != actionGeneration) return@launch
            mutableState.value = renderFrom(isEnrolled())
            withContext(ioDispatcher) {
                withdrawalIntent.recordWithdrawalRequested()
                cancelInFlightUpload()
            }
            acceptMarkerRead(readMarkerFenced())
            if (generation != actionGeneration) return@launch
            if (ownerSession() != tappedSession) {
                retireTapPresentation(generation, isEnrolled())
                return@launch
            }
            mutableState.value = renderFrom(isEnrolled())
            val outcome = withContext(ioDispatcher) { enrollmentController.withdraw() }
            if (ownerSession() != tappedSession) {
                acceptMarkerRead(readMarkerFenced())
                retireTapPresentation(generation, isEnrolled())
                return@launch
            }
            applyWithdrawalOutcome(outcome, generation)
        }
    }
    private fun refreshEnrolled() {
        scope.launch { acceptMarkerRead(readMarkerFenced()) }
    }
    private suspend fun readMarkerFenced(): Pair<Long, Boolean> {
        val generation = ++markerReadGeneration
        val marker = markerRead?.invoke()
            ?: withContext(ioDispatcher) { withdrawalIntent.collectionPermittedByLocalIntent() }
        return generation to marker
    }
    private fun acceptMarkerRead(read: Pair<Long, Boolean>) {
        val (generation, marker) = read
        if (generation != markerReadGeneration) return
        lastRenderedMarker = marker
        mutableState.value = render()
    }
    private suspend fun applyWithdrawalOutcome(outcome: EnrollmentOutcome, generation: Long) {
        when (outcome) {
            is EnrollmentOutcome.Applied -> {
                withContext(ioDispatcher) {
                    enrollmentController.applyForOutcome(outcome) { withdrawalIntent.onServerAgrees() }
                }
                acceptMarkerRead(readMarkerFenced())
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = renderFrom(isEnrolled())
            }
            is EnrollmentOutcome.Failed, is EnrollmentOutcome.Refused -> {
                withContext(ioDispatcher) {
                    enrollmentController.applyForOutcome(outcome) { withdrawalIntent.onWithdrawalFailed() }
                }
                acceptMarkerRead(readMarkerFenced())
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
                if (operation == FailedOperation.WITHDRAW) {
                    enrollmentController.applyForOutcome(outcome) {
                        withdrawalIntent.onServerAgrees()
                    }
                }
                if (operation == FailedOperation.ENROLL) {
                    val released = withContext(ioDispatcher) {
                        enrollmentController.applyForOutcome(outcome) {
                            withdrawalIntent.clearOnUserEnrollment()
                        }
                    }
                    if (released == false && generation == actionGeneration) {
                        pendingOperation = PendingOperation.NONE
                        presentationStatus = SettingsOperationStatus.RELEASE_FAILED
                        mutableState.value = render(SettingsOperationStatus.RELEASE_FAILED)
                        return
                    }
                    if (generation == actionGeneration) {
                        acceptMarkerRead(readMarkerFenced())
                    }
                }
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }
            is EnrollmentOutcome.Failed -> {
                if (generation != actionGeneration) return
                if (operation == FailedOperation.WITHDRAW) {
                    withContext(ioDispatcher) {
                        enrollmentController.applyForOutcome(outcome) {
                            withdrawalIntent.onWithdrawalFailed()
                        }
                    }
                }
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
                if (operation == FailedOperation.WITHDRAW) {
                    withContext(ioDispatcher) {
                        enrollmentController.applyForOutcome(outcome) {
                            withdrawalIntent.onWithdrawalFailed()
                        }
                    }
                }
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
        val p = currentProjection() ?: return false
        return p.voiceSharingEnrolled &&
            p.withdrawalState == WithdrawalState.ACTIVE &&
            lastRenderedMarker
    }
    private fun isDeleteAvailable(): Boolean {
        val p = currentProjection() ?: return false
        return p.voiceSharingEnrolled || p.enrollmentEpoch != null
    }
}
