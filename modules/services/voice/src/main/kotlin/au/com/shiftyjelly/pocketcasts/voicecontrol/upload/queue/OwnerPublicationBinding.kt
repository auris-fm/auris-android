package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * THE OWNER-PUBLICATION BINDING (reviewer fd3faba7): the ONE production rule for publishing a
 * record under a frozen owner — validate the frozen tuple against ONE coherent live identity
 * read and commit inside the SAME owner-locked section, or refuse without any reservation.
 * Both the module wiring and the barrier control call THIS function, so a regression in the
 * locking rule cannot hide behind a handwritten test copy.
 *
 * [OwnerPublicationSection] adapts the credential owner: it must run [OwnerPublicationSection.underOwnerTransaction]'s
 * lambda EXACTLY ONCE inside one owner transaction, passing the coherent live identity read
 * from the transaction's snapshot (null when no live identity exists — e.g. logged out).
 */
interface OwnerPublicationSection {
    fun underOwnerTransaction(section: (live: RetentionPublishTrigger.LiveIdentity?) -> Boolean?): Boolean?
}

/**
 * Builds the atomic publication binding. [interleave] is a CONTROL-ONLY hook invoked between
 * validation and commit INSIDE the section (production passes nothing): the barrier control
 * uses it to attempt an owner transition at exactly the interleaving point the atomicity
 * guarantee forbids.
 */
fun ownerPublicationBinding(
    section: OwnerPublicationSection,
    interleave: (() -> Unit)? = null,
): (frozen: RetentionPublishTrigger.FrozenOwner, commit: () -> Boolean) -> Boolean = { frozen, commit ->
    section.underOwnerTransaction { live ->
        val valid = live != null &&
            live.account == frozen.account &&
            live.session == frozen.session &&
            live.enrollmentEpoch == frozen.enrollmentEpoch
        if (!valid) {
            false
        } else {
            interleave?.invoke()
            commit()
        }
    } == true
}
