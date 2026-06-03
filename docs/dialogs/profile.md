# Profile

Voice dialog reference for all actions in the Profile category.

---

## Profile Main

### View stats

> **User:** Show me my listening stats.
> **Bot:** Opening your stats.

Result: Navigates to the stats screen showing listening history, total time, and other metrics.

> **User:** How much have I listened?
> **Bot:** Here are your stats.

Result: Opens the stats view.

> **User:** My stats.
> **Bot:** Opening stats.

Result: Navigates to the stats screen.

> **User:** Show my listening history stats.
> **Bot:** Opening your stats.

Result: Opens the stats screen.

Edge cases:
- **Not logged in**:
  > **User:** Show me my stats.
  > **Bot:** You need to be logged in to see your stats. Want to sign in?
- **No stats available**:
  > **User:** How much have I listened?
  > **Bot:** You haven't listened to any episodes yet. Your stats will appear once you start listening.

---

### View downloads

> **User:** Show my downloads.
> **Bot:** Opening downloads.

Result: Navigates to the downloads screen listing all downloaded episodes.

> **User:** Go to my downloaded episodes.
> **Bot:** Here are your downloads.

Result: Opens the downloads list.

> **User:** What do I have downloaded?
> **Bot:** Opening downloads.

Result: Navigates to the downloads screen.

> **User:** Show downloaded episodes.
> **Bot:** Opening downloads.

Result: Opens the downloads view.

Edge cases:
- **Not logged in**:
  > **User:** Show my downloads.
  > **Bot:** You need to be logged in to see your downloads. Want to sign in?
- **No downloads**:
  > **User:** What do I have downloaded?
  > **Bot:** You don't have any downloaded episodes. Download an episode to listen offline.

---

### View uploaded files (Cloud)

> **User:** Show my cloud files.
> **Bot:** Opening cloud files.

Result: Navigates to the cloud files screen.

> **User:** Go to my uploaded files.
> **Bot:** Opening cloud files.

Result: Opens the cloud files list.

> **User:** Show my uploaded files.
> **Bot:** Here are your cloud files.

Result: Navigates to the cloud files view.

> **User:** Open cloud files.
> **Bot:** Opening cloud files.

Result: Opens the cloud files screen.

Edge cases:
- **Not logged in**:
  > **User:** Show my cloud files.
  > **Bot:** You need to be logged in to access cloud files. Want to sign in?
- **Not a Plus subscriber**:
  > **User:** Show my uploaded files.
  > **Bot:** Cloud files are a Plus feature. Want to upgrade?
- **No cloud files**:
  > **User:** Show my cloud files.
  > **Bot:** You haven't uploaded any files yet. Upload a file to access it anywhere.

---

### View starred episodes

> **User:** Show my starred episodes.
> **Bot:** Opening starred episodes.

Result: Navigates to the starred episodes list.

> **User:** Go to my favorites.
> **Bot:** Opening starred episodes.

Result: Opens the starred episodes view.

> **User:** What have I starred?
> **Bot:** Here are your starred episodes.

Result: Navigates to the starred episodes screen.

> **User:** Show starred.
> **Bot:** Opening starred.

Result: Opens the starred episodes list.

Edge cases:
- **Not logged in**:
  > **User:** Show my starred episodes.
  > **Bot:** You need to be logged in to see starred episodes. Want to sign in?
- **No starred episodes**:
  > **User:** What have I starred?
  > **Bot:** You haven't starred any episodes yet. Star an episode to save it for later.

---

### View bookmarks

> **User:** Show my bookmarks.
> **Bot:** Opening bookmarks.

Result: Navigates to the bookmarks screen listing all bookmarks across episodes.

> **User:** Go to my bookmarks.
> **Bot:** Here are your bookmarks.

Result: Opens the bookmarks list.

> **User:** List my bookmarks.
> **Bot:** Opening bookmarks.

Result: Navigates to the bookmarks view.

Edge cases:
- **Not logged in**:
  > **User:** Show my bookmarks.
  > **Bot:** You need to be logged in to see your bookmarks. Want to sign in?
- **No bookmarks**:
  > **User:** Show my bookmarks.
  > **Bot:** You don't have any bookmarks yet. Bookmark a moment to save it for later.

---

### View listening history

> **User:** Show my listening history.
> **Bot:** Opening listening history.

Result: Navigates to the listening history screen.

> **User:** What have I been listening to?
> **Bot:** Here's your listening history.

Result: Opens the history list.

> **User:** Go to my history.
> **Bot:** Opening history.

Result: Navigates to the listening history view.

> **User:** Show recently played.
> **Bot:** Opening listening history.

Result: Opens the history screen.

Edge cases:
- **Not logged in**:
  > **User:** Show my listening history.
  > **Bot:** You need to be logged in to see your history. Want to sign in?
- **Empty listening history**:
  > **User:** What have I been listening to?
  > **Bot:** Your listening history is empty. Start listening to build your history.

---

### Open help

> **User:** Open help.
> **Bot:** Opening help.

Result: Navigates to the help and support screen.

> **User:** I need help.
> **Bot:** Opening help.

Result: Opens the help center.

> **User:** Show support.
> **Bot:** Opening help.

Result: Navigates to the help screen.

> **User:** Where can I get support?
> **Bot:** Opening help.

Result: Opens the help center.

---

### Open settings

> **User:** Open settings.
> **Bot:** Opening settings.

Result: Navigates to the main settings screen.

> **User:** Go to settings.
> **Bot:** Opening settings.

Result: Opens the settings screen.

> **User:** Show me the settings.
> **Bot:** Opening settings.

Result: Navigates to settings.

> **User:** I want to change a setting.
> **Bot:** Opening settings.

Result: Opens the settings screen.

---

### Open account details

> **User:** Show my account.
> **Bot:** Opening your account.

Result: Navigates to the account details screen.

> **User:** Go to my account details.
> **Bot:** Opening account details.

Result: Opens the account view.

> **User:** Show account info.
> **Bot:** Opening your account.

Result: Navigates to the account details screen.

Edge cases:
- **Not logged in**:
  > **User:** Show my account.
  > **Bot:** You need to sign in first. Want to log in?

---

### Pull to refresh

> **User:** Refresh my profile.
> **Bot:** Refreshed.

Result: Reloads the profile data from the server.

> **User:** Refresh.
> **Bot:** Refreshed.

Result: Pulls the latest profile data.

> **User:** Check for updates.
> **Bot:** Refreshed.

Result: Refreshes the profile screen.

---

### Dismiss upgrade banner

> **User:** Dismiss the upgrade banner.
> **Bot:** Dismissed.

Result: Closes the Plus upgrade banner on the profile screen.

> **User:** Hide the upgrade banner.
> **Bot:** Hidden.

Result: Dismisses the upgrade banner.

> **User:** Close that Plus ad.
> **Bot:** Dismissed.

Result: Removes the upgrade promotional banner.

---

## Cloud Files

### Sort files

> **User:** Sort my cloud files by name.
> **Bot:** Sorted by name.

Result: Reorders cloud files alphabetically.

> **User:** Sort cloud files by newest.
> **Bot:** Sorted by newest first.

Result: Reorders files with the most recently uploaded at the top.

> **User:** Sort my uploaded files by oldest.
> **Bot:** Sorted by oldest first.

Result: Reorders files with the oldest at the top.

> **User:** Sort cloud files by size.
> **Bot:** Sorted by file size.

Result: Reorders files by file size.

Edge cases:
- **No cloud files**:
  > **User:** Sort my cloud files by name.
  > **Bot:** You don't have any cloud files to sort.
- **Only one file**:
  > **User:** Sort cloud files.
  > **Bot:** You only have one cloud file.

---

### Open file detail

> **User:** Open the first cloud file.
> **Bot:** Opening file details.

Result: Navigates to the detail view for the first cloud file.

> **User:** Show details for "Monday meeting notes."
> **Bot:** Opening "Monday meeting notes."

Result: Opens the detail view for the named file.

> **User:** Open that uploaded file.
> **Bot:** Which one? You have: "Monday meeting notes," "Interview recording," "Lecture part 2."
  > **User:** Interview recording.
  > **Bot:** Opening "Interview recording."

Result: Lists available files and opens the selected one.

Edge cases:
- **File not found**:
  > **User:** Show details for "vacation notes."
  > **Bot:** I couldn't find a file called "vacation notes."
- **No cloud files**:
  > **User:** Open my cloud file.
  > **Bot:** You don't have any cloud files yet.

---

### Delete file

> **User:** Delete this cloud file.
> **Bot:** Deleted "Monday meeting notes."

Result: Permanently removes the cloud file.

> **User:** Remove the uploaded file "Interview recording."
> **Bot:** Deleted "Interview recording."

Result: Deletes the named cloud file.

> **User:** Delete this file.
> **Bot:** Deleted.

Result: Removes the current file.

Edge cases:
- **File not found**:
  > **User:** Delete "vacation notes."
  > **Bot:** I couldn't find a file called "vacation notes."
- **Deletion fails**:
  > **User:** Delete this cloud file.
  > **Bot:** Couldn't delete right now. Try again in a moment.

---

### Edit file image

> **User:** Change the artwork for this file.
> **Bot:** Opening image editor.

Result: Opens the image picker/editor for the cloud file's cover art.

> **User:** Edit the cover image.
> **Bot:** Opening image editor.

Result: Opens the file image editor.

> **User:** Update the image for this file.
> **Bot:** Opening image editor.

Result: Opens the image picker for the cloud file.

Edge cases:
- **Image picker fails to open**:
  > **User:** Change the artwork for this file.
  > **Bot:** Couldn't open the image editor. Try again.

---

### Cast

> **User:** Cast this file.
> **Bot:** Which device? I see: Living Room speaker, Bedroom TV.
  > **User:** Living Room speaker.
  > **Bot:** Connected to Living Room speaker.

Result: Lists available devices and connects to the chosen one.

> **User:** Cast to my TV.
> **Bot:** Connected to TV.

Result: Connects to the named Chromecast device.

> **User:** Cast this cloud file to the kitchen speaker.
> **Bot:** Connected to kitchen speaker.

Result: Connects to the specified cast device.

Edge cases:
- **No devices found**:
  > **User:** Cast to my TV.
  > **Bot:** I don't see any cast devices on your network. Make sure your device is on the same WiFi.
- **Already connected**:
  > **User:** Cast to Living Room speaker.
  > **Bot:** Already connected to Living Room speaker.

---

## Winback / Subscription Management

### View available plans

> **User:** Show me available plans.
> **Bot:** Here are the available plans.

Result: Opens the plan selection screen showing all subscription tiers.

> **User:** What plans can I choose from?
> **Bot:** Opening plan options.

Result: Navigates to the plans view.

> **User:** View subscription plans.
> **Bot:** Here are the plans.

Result: Opens the plan comparison screen.

Edge cases:
- **On free tier**:
  > **User:** Show me available plans.
  > **Bot:** Opening plans. You're currently on the free tier.

---

### Change plan

> **User:** Switch to the annual plan.
> **Bot:** Switched to the annual plan.

Result: Changes the active subscription to the annual billing cycle.

> **User:** Change my plan to Plus monthly.
> **Bot:** Switched to Plus monthly.

Result: Updates the subscription to Plus monthly.

> **User:** Upgrade to Patron.
> **Bot:** Upgraded to Patron.

Result: Changes the subscription to the Patron tier.

> **User:** Downgrade to Plus.
> **Bot:** Switched to Plus. The change takes effect at your next billing date.

Result: Downgrades the subscription and notes the effective date.

Edge cases:
- **Already on the selected plan**:
  > **User:** Switch to Plus monthly.
  > **Bot:** You're already on Plus monthly.
- **Plan change fails**:
  > **User:** Switch to the annual plan.
  > **Bot:** Couldn't change your plan right now. Try again later.

---

### Claim offer

> **User:** Claim this offer.
> **Bot:** Offer claimed.

Result: Applies the promotional offer to the account.

> **User:** Redeem the offer.
> **Bot:** Offer redeemed.

Result: Activates the promotional pricing.

> **User:** Accept the deal.
> **Bot:** Offer applied to your account.

Result: Applies the offer.

Edge cases:
- **Offer expired**:
  > **User:** Claim this offer.
  > **Bot:** This offer has expired. Check back for new promotions.
- **Offer already claimed**:
  > **User:** Claim this offer.
  > **Bot:** You've already claimed this offer.
- **Offer not available**:
  > **User:** Redeem the offer.
  > **Bot:** There's no active offer available for your account right now.

---

### Keep subscription

> **User:** Keep my subscription.
> **Bot:** Your subscription is still active.

Result: Dismisses the cancellation flow and keeps the current subscription.

> **User:** Never mind, I want to keep it.
> **Bot:** Keeping your subscription as is.

Result: Cancels the winback flow and retains the subscription.

> **User:** Don't cancel.
> **Bot:** Your subscription stays active.

Result: Closes the cancellation screen without making changes.

---

### Cancel subscription

> **User:** Cancel my subscription.
> **Bot:** Are you sure? You'll lose access to Plus features at the end of your billing period.
  > **User:** Yes, cancel it.
  > **Bot:** Subscription canceled. You can keep using Plus features until June 30.

Result: Confirms cancellation and notes the remaining access period.

> **User:** I want to unsubscribe.
> **Bot:** Are you sure you want to cancel? You'll lose access to Plus features.
  > **User:** Yes.
  > **Bot:** Canceled. Your Plus access continues until June 30.

Result: Cancels the subscription with confirmation.

> **User:** End my subscription.
> **Bot:** Are you sure? You'll lose access to Plus features at the end of your billing period.
  > **User:** Go ahead.
  > **Bot:** Subscription canceled. Plus access ends on June 30.

Result: Cancels with confirmation and shows the expiry date.

Edge cases:
- **On free tier**:
  > **User:** Cancel my subscription.
  > **Bot:** You don't have an active subscription to cancel.
- **Cancellation confirmation declined**:
  > **User:** Cancel my subscription.
  > **Bot:** Are you sure? You'll lose access to Plus features.
  > **User:** Actually, keep it.
  > **Bot:** Your subscription stays active.
- **Cancellation fails**:
  > **User:** Cancel my subscription.
  > **Bot:** Couldn't process the cancellation right now. Try again later or contact support.

---

### Help and feedback

> **User:** Open help and feedback.
> **Bot:** Opening help.

Result: Navigates to the help and feedback screen from the winback flow.

> **User:** I need help with my subscription.
> **Bot:** Opening help.

Result: Opens the help center focused on subscription topics.

> **User:** Contact support about my plan.
> **Bot:** Opening help.

Result: Navigates to the help/feedback screen.
