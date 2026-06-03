# Account & Onboarding

## Sign In (Email)

**Variant 1: Direct sign-in**
> **User:** Sign in with email.
> **Bot:** What's your email address?
> **User:** merlin@example.com
> **Bot:** And your password?
> **User:** hunter2
> **Bot:** Signed in as merlin@example.com.
Result: User is authenticated and taken to the home screen.

**Variant 2: Specifying credentials upfront**
> **User:** Log in to my account, merlin@example.com, password hunter2.
> **Bot:** Signed in as merlin@example.com. Welcome back!
Result: User is authenticated.

**Variant 3: Casual phrasing**
> **User:** I need to log in.
> **Bot:** Sure, what's your email?
Result: Sign-in flow begins.

**Variant 4: After sign-out**
> **User:** Log back in.
> **Bot:** Logging in with merlin@example.com — enter your password to continue.
Result: Pre-fills last-used email and prompts for password.

### Edge Cases & Error Conditions

- **Wrong password:**
  > **User:** Log in, merlin@example.com, wrongpass.
  > **Bot:** That password doesn't match. Want to try again or reset your password?
- **Account not found:**
  > **User:** Sign in as nobody@example.com.
  > **Bot:** No account found for that email. Want to create one instead?
- **Network error:**
  > **Bot:** Couldn't reach the server. Check your connection and try again.
- **Already signed in:**
  > **User:** Sign in.
  > **Bot:** You're already signed in as merlin@example.com. Want to switch accounts?

## Sign In (Google)

**Variant 1: Direct request**
> **User:** Sign in with Google.
> **Bot:** Opening Google sign-in.
Result: System Google sign-in sheet appears.

**Variant 2: Casual phrasing**
> **User:** Use my Google account to log in.
> **Bot:** Opening Google sign-in.
Result: System Google sign-in sheet appears.

**Variant 3: Quick login**
> **User:** Google login.
> **Bot:** Opening Google sign-in.
Result: System Google sign-in sheet appears.

**Variant 4: After sign-out**
> **User:** Sign back in with Google.
> **Bot:** Opening Google sign-in.
Result: System Google sign-in sheet appears.

### Edge Cases & Error Conditions

- **Google sign-in failure:**
  > **Bot:** Google sign-in was cancelled or failed. Want to try again or use email instead?
- **Already signed in:**
  > **User:** Sign in with Google.
  > **Bot:** You're already signed in. Want to switch to a Google account?
- **Network error:**
  > **Bot:** Couldn't connect to Google. Check your internet and try again.

## Create Account

**Variant 1: Step by step**
> **User:** Create an account.
> **Bot:** What email would you like to use?
> **User:** merlin@example.com
> **Bot:** Choose a password (at least 8 characters).
> **User:** securepassword123
> **Bot:** Account created for merlin@example.com. You're signed in!
Result: New account created and user is authenticated.

**Variant 2: With all details**
> **User:** Create account, merlin@example.com, password securepassword123.
> **Bot:** Account created for merlin@example.com. You're signed in!
Result: New account created and user is authenticated.

**Variant 3: Sign up phrasing**
> **User:** I want to sign up.
> **Bot:** Let's create your account. What email would you like to use?
Result: Account creation flow begins.

**Variant 4: With newsletter opt-in**
> **User:** Create an account and sign me up for the newsletter.
> **Bot:** What email would you like to use?
> **User:** merlin@example.com
> **Bot:** Choose a password.
> **User:** securepassword123
> **Bot:** Account created for merlin@example.com with newsletter enabled.
Result: New account created with newsletter opted in.

### Edge Cases & Error Conditions

- **Email already in use:**
  > **Bot:** That email already has an account. Want to sign in instead?
- **Weak password:**
  > **User:** Create account, merlin@example.com, password 123.
  > **Bot:** That password is too short. Use at least 8 characters.
- **Invalid email format:**
  > **User:** Create account, notanemail, password securepassword123.
  > **Bot:** That doesn't look like a valid email. Try again?
- **Network error:**
  > **Bot:** Couldn't create your account right now. Check your connection and try again.

## Change Email

**Variant 1: Direct request**
> **User:** Change my email to newaddress@example.com.
> **Bot:** I'll need your password to confirm. What is it?
> **User:** securepassword123
> **Bot:** Email updated to newaddress@example.com.
Result: Email address is changed.

**Variant 2: Step by step**
> **User:** Update my email address.
> **Bot:** What's the new email?
> **User:** newaddress@example.com
> **Bot:** Enter your password to confirm.
> **User:** securepassword123
> **Bot:** Email updated to newaddress@example.com.
Result: Email address is changed.

**Variant 3: Account settings context**
> **User:** I need to change my email in account settings.
> **Bot:** What's the new email address?
Result: Email change flow begins.

**Variant 4: Casual**
> **User:** My email changed, update it to newaddress@example.com.
> **Bot:** Enter your password to confirm the change.
Result: Password confirmation prompted.

### Edge Cases & Error Conditions

- **Email already in use:**
  > **Bot:** That email is already linked to another account. Try a different one.
- **Wrong password:**
  > **Bot:** That password doesn't match. Want to try again?
- **Invalid email format:**
  > **Bot:** That doesn't look like a valid email address.
- **Not signed in:**
  > **User:** Change my email.
  > **Bot:** You'll need to sign in first.

## Change Password

**Variant 1: Direct with both passwords**
> **User:** Change my password from oldpass123 to newpass456.
> **Bot:** Password updated.
Result: Password is changed.

**Variant 2: Step by step**
> **User:** I want to change my password.
> **Bot:** Enter your current password.
> **User:** oldpass123
> **Bot:** Now enter the new password.
> **User:** newpass456
> **Bot:** Password updated.
Result: Password is changed.

**Variant 3: Security concern**
> **User:** Update my password for security.
> **Bot:** Enter your current password to continue.
Result: Password change flow begins.

**Variant 4: Short phrasing**
> **User:** New password please.
> **Bot:** Enter your current password first.
Result: Password change flow begins.

### Edge Cases & Error Conditions

- **Wrong current password:**
  > **Bot:** That doesn't match your current password. Try again?
- **New password too weak:**
  > **Bot:** That password is too short. Use at least 8 characters.
- **New password same as old:**
  > **Bot:** The new password must be different from your current one.
- **Not signed in:**
  > **User:** Change my password.
  > **Bot:** You'll need to sign in first.

## Reset Password

**Variant 1: Forgot password**
> **User:** I forgot my password.
> **Bot:** What email is your account linked to?
> **User:** merlin@example.com
> **Bot:** Password reset email sent to merlin@example.com. Check your inbox.
Result: Reset email is sent.

**Variant 2: Direct request**
> **User:** Reset my password for merlin@example.com.
> **Bot:** Password reset email sent to merlin@example.com. Check your inbox.
Result: Reset email is sent.

**Variant 3: Can't log in**
> **User:** I can't log in, I don't remember my password.
> **Bot:** I can send a reset link. What's your email?
> **User:** merlin@example.com
> **Bot:** Reset email sent. Check your inbox.
Result: Reset email is sent.

**Variant 4: Password help**
> **User:** Help me with my password.
> **Bot:** I can send a password reset link. What's your email?
Result: Reset flow begins.

### Edge Cases & Error Conditions

- **Account not found:**
  > **User:** Reset password for nobody@example.com.
  > **Bot:** No account found for that email. Want to create one instead?
- **Reset email not received (user asks again):**
  > **User:** I didn't get the reset email.
  > **Bot:** I can resend it to merlin@example.com. Want me to try again?
- **Already signed in:**
  > **User:** Reset my password.
  > **Bot:** You're already signed in. Did you want to change your password instead?

## Redeem Promo Code

**Variant 1: Direct with code**
> **User:** Redeem promo code PODCAST2024.
> **Bot:** Promo code PODCAST2024 applied! You now have Plus until March 2025.
Result: Promo code is redeemed and benefit is applied.

**Variant 2: Step by step**
> **User:** I have a promo code.
> **Bot:** What's the code?
> **User:** PODCAST2024
> **Bot:** Promo code applied! You now have Plus until March 2025.
Result: Promo code is redeemed.

**Variant 3: Gift code phrasing**
> **User:** Enter my gift code PODCAST2024.
> **Bot:** Gift code applied! Plus activated until March 2025.
Result: Code is redeemed.

**Variant 4: Voucher**
> **User:** I've got a voucher to redeem.
> **Bot:** What's the voucher code?
Result: Code entry flow begins.

### Edge Cases & Error Conditions

- **Invalid promo code:**
  > **User:** Redeem code FAKECODE.
  > **Bot:** That code isn't valid. Double-check and try again.
- **Expired promo code:**
  > **User:** Redeem code OLDCODE.
  > **Bot:** That code has expired. Contact support if you think this is an error.
- **Already redeemed:**
  > **User:** Redeem code PODCAST2024.
  > **Bot:** That code has already been used.
- **Network error:**
  > **Bot:** Couldn't verify the code right now. Try again in a moment.

## Sign Out

**Variant 1: Direct**
> **User:** Sign out.
> **Bot:** Are you sure? Your downloads will stay on this device.
> **User:** Yes.
> **Bot:** Signed out.
Result: User is signed out.

**Variant 2: Log off phrasing**
> **User:** Log me out.
> **Bot:** Are you sure? Your downloads will stay on this device.
> **User:** Yes, log out.
> **Bot:** Signed out. See you next time!
Result: User is signed out.

**Variant 3: Switch account context**
> **User:** Sign out, I want to use a different account.
> **Bot:** Are you sure you want to sign out?
> **User:** Yes.
> **Bot:** Signed out. You can sign in with another account now.
Result: User is signed out and taken to sign-in screen.

**Variant 4: Cancel the sign-out**
> **User:** Sign out.
> **Bot:** Are you sure? Your downloads will stay on this device.
> **User:** Actually, never mind.
> **Bot:** Okay, staying signed in.
Result: No action taken.

### Edge Cases & Error Conditions

- **Not signed in:**
  > **User:** Sign out.
  > **Bot:** You're not currently signed in.
- **Unsynced data:**
  > **Bot:** You have unsynced listening progress. Sign out anyway?
  > **User:** Yes.
  > **Bot:** Signed out. Note that unsynced progress won't be saved.
- **Downloaded content warning:**
  > **Bot:** You have 42 downloaded episodes. They'll stay on your device but won't sync until you sign back in. Sign out?
