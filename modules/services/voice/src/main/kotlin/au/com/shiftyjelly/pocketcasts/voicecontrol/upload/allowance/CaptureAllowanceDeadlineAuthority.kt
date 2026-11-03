package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * Enforces the ALLOWANCE's own deadline at every admission, not only at install time
 * (reviewer c226fb6f, cloud a3293810).
 *
 * The two deadlines are independent, and the shorter one is the one that bites:
 * - **policy freshness** — the lease the retirement watcher schedules, up to 5 minutes;
 * - **the allowance's `valid_for_ms`** — computed by the server at ISSUE as
 *   `min(remaining policy freshness, until-midnight-UTC)`, anchored on the client's request
 *   start, and it can be far shorter than the policy lease. Near midnight UTC it can collapse to
 *   milliseconds while policy still looks healthy.
 *
 * So an installed authority whose deadline has passed must refuse, even though the policy lease
 * that installed it is live and no timer has fired. The check runs on the ADMISSION path
 * ([takePrepaidToken]) — it never relies on a scheduled callback being prompt, which is exactly
 * what made the install-time-only check insufficient.
 */
class CaptureAllowanceDeadlineAuthority(
    private val delegate: CaptureAllowanceAuthority,
    private val grant: CaptureAllowanceProvisioner.Grant,
    private val elapsedMs: () -> Long,
) : CaptureAllowanceAuthority {

    override fun takePrepaidToken(): CaptureAllowanceToken? {
        // DEADLINE FIRST: an expired allowance must not hand out a token even if the underlying
        // ledger would. The grant's freshness is anchored at the ORIGINAL request start, so
        // transit time cannot extend the server's interval.
        if (!grant.isFresh(elapsedMs())) return null
        return delegate.takePrepaidToken()
    }

    /** Retirement reaches the wrapped authority unchanged. */
    override fun retireForLifecycle() = delegate.retireForLifecycle()
}
