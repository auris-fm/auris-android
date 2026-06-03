# Account Management

Account management by voice serves accessibility and hands-free needs. Multi-step flows use natural slot-filling — the bot asks for missing information one piece at a time. Passwords spoken aloud are supported, but the bot always notes the option to type on screen instead. Explicit-confirmation actions protect destructive or costly operations.

---

## Sign In (Email)

Confirmation: implicit

> **User:** Sign in.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** And your password? You can also type it on screen.
> **User:** hunter2
> **Bot:** Signed in as merlin@example.com.

Result: User is authenticated and signed in.

> **User:** Log in, merlin@example.com, password hunter2.
> **Bot:** Signed in as merlin@example.com.

Result: All details provided in one utterance — the bot skips slot-filling and signs in directly.

> **User:** Sign in to my account.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** And your password? You can also type it on screen.
> **User:** I'll type it.
> **Bot:** Tap the password field on screen to continue.

Result: Bot waits. The user types their password on the device.

> **User:** Sign in with email.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** And your password? You can also type it on screen.
> **User:** Let me type the password.
> **Bot:** Tap the password field on screen to continue.

Result: Same as above — user prefers to type the password.

### Edge Cases

**Wrong password.**

> **User:** wrongpass
> **Bot:** That password didn't work. Try again, or say "reset password".

**Account not found.**

> **User:** nobody@example.com
> **Bot:** No account for that email. Say "create account" to set one up.

**Already signed in.**

> **User:** Sign in.
> **Bot:** You're already signed in as merlin@example.com. Switch accounts instead?

**Network error.**

> **User:** hunter2
> **Bot:** Couldn't reach the server. Check your connection and try again.

---

## Sign In (Google)

Confirmation: implicit

> **User:** Sign in with Google.
> **Bot:** Opening Google sign-in. Tap your account on screen.

Result: Google sign-in bottom sheet opens. The user must tap their account on the device.

> **User:** Log in with my Google account.
> **Bot:** Opening Google sign-in. Tap your account on screen.

Result: Same action, natural paraphrase.

> **User:** Use Google to sign in.
> **Bot:** Opening Google sign-in. Tap your account on screen.

Result: Same action.

### Edge Cases

**Google sign-in failure.**

> **User:** *(after tapping)*
> **Bot:** Google sign-in didn't work. Try again or use your email instead.

**No Google accounts on device.**

> **User:** Sign in with Google.
> **Bot:** No Google accounts found. Add one in Settings, or sign in with email.

---

## Create Account

Confirmation: implicit

Multi-step slot-filling: the bot collects email, password, and newsletter preference. If the user provides everything upfront, it confirms in one turn.

> **User:** Create an account.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** Pick a password. You can also type it on screen.
> **User:** securepass123
> **Bot:** Want to hear about new features and picks via email?
> **User:** Sure.
> **Bot:** Account created. Signed in as merlin@example.com.

Result: Account created and user is signed in.

> **User:** Create account, merlin@example.com, password securepass.
> **Bot:** Want to hear about new features and picks via email?
> **User:** No thanks.
> **Bot:** Account created. Signed in as merlin@example.com.

Result: All details provided upfront, bot only asks for the newsletter preference.

> **User:** Sign up.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** Pick a password. You can also type it on screen.
> **User:** I'll type it.
> **Bot:** Tap the password field on screen to continue.

Result: Bot waits for the user to type their password on the device.

> **User:** Make me an account.
> **Bot:** What's your email?
> **User:** newuser@example.com
> **Bot:** Pick a password. You can also type it on screen.
> **User:** strongPassword99
> **Bot:** Want to hear about new features and picks via email?
> **User:** Yes.
> **Bot:** Account created. Signed in as newuser@example.com.

Result: Full slot-filling flow completed.

### Edge Cases

**Email already in use.**

> **User:** merlin@example.com
> **Bot:** That email already has an account. Sign in instead, or use a different email.

**Weak password.**

> **User:** abc
> **Bot:** Password needs at least 8 characters. Try again, or type it on screen.

**Invalid email format.**

> **User:** notanemail
> **Bot:** That doesn't look like an email. Try again?

**User cancels mid-flow.**

> **User:** Never mind.
> **Bot:** Cancelled. No account was created.

---

## Change Email

Confirmation: implicit

> **User:** Change my email to new@example.com.
> **Bot:** Enter your password to confirm.
> **User:** hunter2
> **Bot:** Email updated.

Result: Email address changed. A confirmation email is sent to the new address.

> **User:** Update my email.
> **Bot:** What's the new email?
> **User:** new@example.com
> **Bot:** Enter your password to confirm. You can type it on screen.
> **User:** hunter2
> **Bot:** Email updated.

Result: Same outcome via two-step slot-filling.

> **User:** Switch my email address to merlin@newdomain.com.
> **Bot:** Enter your password to confirm.
> **User:** mypassword
> **Bot:** Email updated.

Result: Same outcome.

### Edge Cases

**Wrong password.**

> **User:** wrongpass
> **Bot:** That password didn't work. Try again?

**New email already in use.**

> **User:** Change my email to taken@example.com.
> **Bot:** That email is already in use. Try a different one?

**Not signed in.**

> **User:** Change my email.
> **Bot:** Sign in first to change your email.

---

## Change Password

Confirmation: implicit

> **User:** Change my password.
> **Bot:** Enter your current password. You can type it on screen.
> **User:** oldpass
> **Bot:** Enter the new password. You can type it on screen.
> **User:** newpass123
> **Bot:** Password updated.

Result: Password changed successfully.

> **User:** I want to update my password.
> **Bot:** Enter your current password. You can type it on screen.
> **User:** oldpass
> **Bot:** Enter the new password. You can type it on screen.
> **User:** strongNewPass99
> **Bot:** Password updated.

Result: Same outcome, natural paraphrase.

> **User:** Change my password from oldpass to newpass123.
> **Bot:** Password updated.

Result: Both passwords provided in one utterance — bot applies the change directly.

> **User:** Set a new password.
> **Bot:** Enter your current password. You can type it on screen.
> **User:** I'll type both.
> **Bot:** Tap the fields on screen to continue.

Result: Bot waits. The user types current and new passwords on the device.

### Edge Cases

**Wrong current password.**

> **User:** wrongpass
> **Bot:** That password didn't work. Try again?

**New password too weak.**

> **User:** abc
> **Bot:** Password needs at least 8 characters. Try again, or type it on screen.

**Not signed in.**

> **User:** Change my password.
> **Bot:** Sign in first to change your password.

---

## Reset Password

Confirmation: implicit

> **User:** I forgot my password.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** Reset email sent. Check your inbox.

Result: Password reset email is sent.

> **User:** Reset my password.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** Reset email sent. Check your inbox.

Result: Same outcome.

> **User:** I can't log in, reset password for merlin@example.com.
> **Bot:** Reset email sent. Check your inbox.

Result: Email provided upfront, bot skips the question.

> **User:** Password reset.
> **Bot:** What's your email?
> **User:** merlin@example.com
> **Bot:** Reset email sent. Check your inbox.

Result: Same outcome.

### Edge Cases

**Account not found.**

> **User:** nobody@example.com
> **Bot:** If that email has an account, a reset link is on its way.

Result: The bot does not reveal whether the email exists, for security.

**Reset email not received.**

> **User:** I didn't get the email.
> **Bot:** Check your spam folder. Want me to resend it?
> **User:** Yes.
> **Bot:** Resent. Check your inbox again.

**Network error.**

> **User:** merlin@example.com
> **Bot:** Couldn't send the reset email. Check your connection and try again.

---

## Redeem Promo Code

Confirmation: implicit

> **User:** Redeem code PODCAST2024.
> **Bot:** Code applied! Plus until December 31, 2026.

Result: Promo code redeemed and Plus access activated or extended.

> **User:** I have a promo code: PODCAST2024.
> **Bot:** Code applied! Plus until December 31, 2026.

Result: Same outcome, natural paraphrase.

> **User:** Enter promo code SUMMER50.
> **Bot:** Code applied! 50% off Plus yearly.

Result: Code redeemed with discount applied.

> **User:** Redeem code.
> **Bot:** What's the code?
> **User:** PODCAST2024
> **Bot:** Code applied! Plus until December 31, 2026.

Result: Slot-filling when the code is not provided upfront.

### Edge Cases

**Invalid or expired code.**

> **User:** Redeem code FAKECODE.
> **Bot:** That code didn't work. Check it and try again.

**Already redeemed.**

> **User:** Redeem code PODCAST2024.
> **Bot:** That code has already been used.

**Not signed in.**

> **User:** Redeem code PODCAST2024.
> **Bot:** Sign in first to redeem a code.

---

## Sign Out

Confirmation: explicit

> **User:** Sign out.
> **Bot:** Sign out? Your downloads will stay on this device.
> **User:** Yes.
> **Bot:** Signed out.

Result: User is signed out. Downloaded episodes remain on device.

> **User:** Log out.
> **Bot:** Sign out? Your downloads will stay on this device.
> **User:** Yep.
> **Bot:** Signed out.

Result: Same outcome, natural paraphrase.

> **User:** Sign out.
> **Bot:** Sign out? Your downloads will stay on this device.
> **User:** No, wait.
> **Bot:** Staying signed in.

Result: User cancels — no action taken.

> **User:** I want to sign out of my account.
> **Bot:** Sign out? Your downloads will stay on this device.
> **User:** Yes.
> **Bot:** Signed out.

Result: Same outcome, longer phrasing.

### Edge Cases

**Not signed in.**

> **User:** Sign out.
> **Bot:** You're not signed in.

---

## View Plans

Confirmation: implicit

> **User:** What plans are available?
> **Bot:** Plus monthly is $3.99, Plus yearly is $39.99. Both include ad-free listening, folders, and bookmarks.

Result: Plan information read aloud.

> **User:** Show me pricing.
> **Bot:** Plus monthly is $3.99, Plus yearly is $39.99. Both include ad-free listening, folders, and bookmarks.

Result: Same information, natural paraphrase.

> **User:** How much does Plus cost?
> **Bot:** Plus monthly is $3.99, Plus yearly is $39.99. Both include ad-free listening, folders, and bookmarks.

Result: Same information, question form.

> **User:** What do I get with Plus?
> **Bot:** Plus includes ad-free listening, folders, bookmarks, desktop apps, and cloud storage. Monthly is $3.99, yearly is $39.99.

Result: Feature-first answer with pricing.

---

## Change Plan

Confirmation: explicit

> **User:** Switch to yearly.
> **Bot:** Switch to Plus yearly at $39.99 per year? Your monthly plan will be cancelled.
> **User:** Yes.
> **Bot:** Plan changed.

Result: Subscription switched from monthly to yearly billing.

> **User:** Change to monthly plan.
> **Bot:** Switch to Plus monthly at $3.99 per month? Your yearly plan will be cancelled.
> **User:** Yes.
> **Bot:** Plan changed.

Result: Subscription switched from yearly to monthly billing.

> **User:** Switch to yearly.
> **Bot:** Switch to Plus yearly at $39.99 per year? Your monthly plan will be cancelled.
> **User:** On second thought, no.
> **Bot:** Staying on Plus monthly.

Result: User cancels — no change made.

> **User:** Upgrade to yearly.
> **Bot:** Switch to Plus yearly at $39.99 per year? Your monthly plan will be cancelled.
> **User:** Yes.
> **Bot:** Plan changed.

Result: Same as first variant.

### Edge Cases

**Not subscribed.**

> **User:** Switch to yearly.
> **Bot:** You're not on a paid plan. Say "view plans" to see options.

**Already on the requested plan.**

> **User:** Switch to monthly.
> **Bot:** You're already on Plus monthly.

**Network error.**

> **User:** Yes.
> **Bot:** Couldn't change your plan. Check your connection and try again.

---

## Claim Offer

Confirmation: implicit

> **User:** Claim this offer.
> **Bot:** Offer applied! Three months of Plus free.

Result: Promotional offer redeemed.

> **User:** Redeem the holiday offer.
> **Bot:** Offer applied! 40% off Plus yearly.

Result: Specific offer redeemed.

> **User:** Use my free trial.
> **Bot:** Offer applied! 30 days of Plus free.

Result: Trial offer activated.

### Edge Cases

**No offer available.**

> **User:** Claim this offer.
> **Bot:** No offer available right now.

**Offer expired.**

> **User:** Claim this offer.
> **Bot:** That offer has expired. Check for current promotions in your account.

**Not signed in.**

> **User:** Claim this offer.
> **Bot:** Sign in first to claim an offer.

---

## Cancel Subscription

Confirmation: explicit

> **User:** Cancel my subscription.
> **Bot:** Cancel Plus? You'll lose access to ad-free listening, folders, and bookmarks at the end of your billing period on July 15.
> **User:** Yes, cancel.
> **Bot:** Subscription cancelled. Active until July 15.

Result: Subscription is cancelled but remains active until the end of the billing period.

> **User:** I want to unsubscribe.
> **Bot:** Cancel Plus? You'll lose access to ad-free listening, folders, and bookmarks at the end of your billing period on July 15.
> **User:** Go ahead.
> **Bot:** Subscription cancelled. Active until July 15.

Result: Same outcome, natural paraphrase.

> **User:** Cancel my subscription.
> **Bot:** Cancel Plus? You'll lose access to ad-free listening, folders, and bookmarks at the end of your billing period on July 15.
> **User:** Actually, keep it.
> **Bot:** Glad you're staying!

Result: User decides to keep the subscription — no change made.

> **User:** Stop my Plus subscription.
> **Bot:** Cancel Plus? You'll lose access to ad-free listening, folders, and bookmarks at the end of your billing period on July 15.
> **User:** Yes, cancel.
> **Bot:** Subscription cancelled. Active until July 15.

Result: Same outcome.

### Edge Cases

**Not subscribed.**

> **User:** Cancel my subscription.
> **Bot:** You're on the free plan — nothing to cancel.

**Network error.**

> **User:** Yes, cancel.
> **Bot:** Couldn't process that. Check your connection and try again.

---

## Keep Subscription

Confirmation: implicit

> **User:** Keep my subscription.
> **Bot:** Glad you're staying!

Result: No action — reassurance message only.

> **User:** Never mind, keep it.
> **Bot:** Glad you're staying!

Result: Same — user changes their mind during a cancel flow.

> **User:** I changed my mind, don't cancel.
> **Bot:** Glad you're staying!

Result: Same — user backs out explicitly.

---

## Sign-In Status

Confirmation: implicit

> **User:** Am I signed in?
> **Bot:** Yes, signed in as merlin@example.com.

Result: Status confirmed.

> **User:** Who am I logged in as?
> **Bot:** Signed in as merlin@example.com.

Result: Same information, natural paraphrase.

> **User:** Am I signed in?
> **Bot:** You're not signed in.

Result: User is not authenticated.

> **User:** What account is this?
> **Bot:** Signed in as merlin@example.com on Plus yearly.

Result: Status with subscription tier.

---

## Subscription Status

Confirmation: implicit

> **User:** What plan am I on?
> **Bot:** You're on Plus monthly. Next billing: July 15.

Result: Plan and billing date confirmed.

> **User:** Do I have Plus?
> **Bot:** Yes, Plus monthly. Next billing: July 15.

Result: Confirmation with billing date.

> **User:** What plan am I on?
> **Bot:** You're on the free plan.

Result: User is on the free tier.

> **User:** When does my subscription renew?
> **Bot:** Plus yearly renews on December 31.

Result: Renewal date confirmed.
