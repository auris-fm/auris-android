# Sharing

Sharing by voice is natural — users share what they are listening to in the moment. The bot handles opening the share sheet with the right content pre-filled. Most share actions open the system share sheet so the user can pick a destination on screen; the bot confirms what it prepared, not where it sent anything.

---

## Share Episode

Confirmation: implicit

> **User:** Share this episode.
> **Bot:** [confirm]

Result: System share sheet opens with a link to the currently playing (or most recently played) episode.

> **User:** Send this to a friend.
> **Bot:** [confirm]

Result: Same as above — natural paraphrase resolves to share-episode intent.

> **User:** Share the episode I listened to yesterday.
> **Bot:** [confirm]

Result: Share sheet opens with the specified episode from history.

> **User:** Share the newest episode of Reply All.
> **Bot:** [confirm]

Result: Share sheet opens with the latest episode of the named podcast.

### Edge Cases

**Nothing playing and no recent episode.**

> **User:** Share this episode.
> **Bot:** [error]

**Episode title too long for a concise response.** The bot truncates with an ellipsis.

> **User:** Share this.
> **Bot:** [confirm]

---

## Share at Current Timestamp

Confirmation: implicit

> **User:** Share from here.
> **Bot:** [confirm]

Result: Share sheet opens with a timestamped link starting at the current playback position.

> **User:** Share at this point.
> **Bot:** [confirm]

Result: Same action, natural paraphrase.

> **User:** Share where I am right now.
> **Bot:** [confirm]

Result: Same action, natural paraphrase.

> **User:** Send this moment to someone.
> **Bot:** [confirm]

Result: Same action, natural paraphrase.

### Edge Cases

**Nothing playing.**

> **User:** Share from here.
> **Bot:** [error]

---

## Share at Specific Time

Confirmation: implicit

> **User:** Share at 15 minutes.
> **Bot:** [confirm]

Result: Share sheet opens with a timestamped link starting at exactly 15:00.

> **User:** Share from 30 minutes in.
> **Bot:** [confirm]

Result: Share sheet opens at 30:00.

> **User:** Share starting at one hour twelve.
> **Bot:** [confirm]

Result: Share sheet opens at the spoken time, parsed from natural language.

> **User:** Share from the beginning.
> **Bot:** [confirm]

Result: Share sheet opens at the start of the episode.

### Edge Cases

**Time exceeds episode length.**

> **User:** Share at 90 minutes.
> **Bot:** That's past the end. Sharing from 42:15 instead.

**Nothing playing.**

> **User:** Share at 10 minutes.
> **Bot:** [error]

---

## Share Podcast

Confirmation: implicit

> **User:** Share this podcast.
> **Bot:** [confirm]

Result: Share sheet opens with a link to the podcast show page.

> **User:** Send a link to Reply All.
> **Bot:** [confirm]

Result: Same — podcast identified by name.

> **User:** Share the podcast I'm listening to.
> **Bot:** [confirm]

Result: Shares the podcast of the currently playing episode.

> **User:** Share the show.
> **Bot:** [confirm]

Result: Shares the podcast of the currently playing episode.

### Edge Cases

**Podcast name not found.**

> **User:** Share a link to Bananacasts.
> **Bot:** I couldn't find "Bananacasts". Check the name?

**Nothing playing and no podcast specified.**

> **User:** Share the show.
> **Bot:** Nothing playing right now. Which podcast?

---

## Share Clip

Confirmation: implicit

> **User:** Share a clip.
> **Bot:** [confirm]

Result: Clip editor opens with the current playback position as the default start point.

> **User:** Clip this part.
> **Bot:** [confirm]

Result: Same action.

> **User:** Make a clip of the last 30 seconds.
> **Bot:** [confirm]

Result: Clip editor opens with the specified range pre-filled.

> **User:** Clip from 5 to 10 minutes.
> **Bot:** [confirm]

Result: Clip editor opens with the specified range pre-filled.

### Edge Cases

**Clip range too short.**

> **User:** Clip the last 2 seconds.
> **Bot:** Clips need at least 5 seconds. Opening the editor — adjust the range there.

**Nothing playing.**

> **User:** Share a clip.
> **Bot:** [error]

---

## Share Bookmark

Confirmation: implicit

> **User:** Share my bookmark "the funny bit".
> **Bot:** [confirm]

Result: Share sheet opens with a link at the bookmark's timestamp.

> **User:** Share the bookmark I just made.
> **Bot:** [confirm]

Result: Share sheet opens for the most recently created bookmark.

> **User:** Share all my bookmarks from this episode.
> **Bot:** [confirm]

Result: Share sheet opens with a list of bookmarked timestamps.

### Edge Cases

**Bookmark not found.**

> **User:** Share my bookmark "the conclusion".
> **Bot:** No bookmark called "the conclusion". Check the title?

**No bookmarks.**

> **User:** Share my bookmarks.
> **Bot:** No bookmarks yet. Add one first.

---

## Share Transcript

Confirmation: implicit

> **User:** Share the transcript.
> **Bot:** [confirm]

Result: Share sheet opens with the episode transcript as text.

> **User:** Send the transcript to someone.
> **Bot:** [confirm]

Result: Same action.

> **User:** Copy the transcript.
> **Bot:** [confirm]

Result: Transcript text copied to clipboard.

> **User:** Share this part of the transcript.
> **Bot:** [confirm]

Result: Share sheet opens with the portion of the transcript around the current position.

### Edge Cases

**Transcript unavailable.**

> **User:** Share the transcript.
> **Bot:** [error]

**Nothing playing.**

> **User:** Share the transcript.
> **Bot:** [error]

---

## Create Shared List

Confirmation: implicit

Slot-filling dialog collects a name and at least one podcast. If the user provides everything upfront, the bot confirms and opens the share sheet in one turn.

> **User:** Create a shared list of my favorite podcasts.
> **Bot:** What should the list be called?
> **User:** Best of 2024.
> **Bot:** Which podcasts? Say a name or "done" to finish.
> **User:** Reply All, and 99% Invisible.
> **Bot:** Got it. Anything else?
> **User:** That's it.
> **Bot:** [confirm]

Result: A new shared list named "Best of 2024" containing Reply All and 99% Invisible is created and the share sheet opens.

> **User:** Create a shared list called Tech Podcasts with The Vergecast and Decoder Ring.
> **Bot:** [confirm]

Result: List created with both podcasts, share sheet opens.

> **User:** Share my podcast list.
> **Bot:** Which list? Favorites, or a new one?
> **User:** Favorites.
> **Bot:** [confirm]

Result: A shared list is generated from the user's existing Favorites list and the share sheet opens.

> **User:** Make a list with this podcast and send it to Sarah.
> **Bot:** What should the list be called?
> **User:** Podcast recs.
> **Bot:** [confirm]

Result: List created with the current podcast, share sheet opens pre-directed to Messages.

### Edge Cases

**No podcasts provided.**

> **User:** Create a shared list.
> **Bot:** Sure. What should it be called?
> **User:** Empty list.
> **Bot:** Add at least one podcast to share.

**Podcast not found.**

> **User:** Add BananaCasts to my list.
> **Bot:** Couldn't find "BananaCasts". Try again?

**User cancels mid-flow.**

> **User:** Actually, never mind.
> **Bot:** Cancelled. No list was created.

---

## Share via Specific App

Confirmation: implicit

> **User:** Share this on WhatsApp.
> **Bot:** [confirm]

Result: Share sheet bypassed; the episode link opens directly in WhatsApp's share screen.

> **User:** Send via email.
> **Bot:** [confirm]

Result: Email compose screen opens with the episode link.

> **User:** Share on Slack.
> **Bot:** [confirm]

Result: Slack share screen opens with the episode link.

> **User:** Text this to someone.
> **Bot:** [confirm]

Result: SMS/Messages compose screen opens with the episode link.

### Edge Cases

**App not installed.**

> **User:** Share this on WhatsApp.
> **Bot:** WhatsApp isn't installed. Open the share sheet instead?

**No sharing apps available.**

> **User:** Share this.
> **Bot:** No apps to share with. Check your installed apps?

**Sharing fails.**

> **User:** Share on Slack.
> **Bot:** Couldn't open Slack. Try the share sheet instead?

---

## Accept Shared List

Confirmation: implicit

This action is context-dependent — the user has received or opened a shared-list link and the app presents it. The bot describes what was received and asks whether to subscribe.

> **User:** Open that shared list.
> **Bot:** Shared list from Alex: 5 podcasts. Subscribe to all?

> **User:** Yes.
> **Bot:** Subscribed to 5 podcasts.

Result: All podcasts in the shared list are added to the user's library.

> **User:** Let me pick.
> **Bot:** Here are the 5 podcasts. Say the ones you want.
> **User:** Reply All and 99% Invisible.
> **Bot:** Subscribed to 2 podcasts.

Result: Only the named podcasts are added.

> **User:** No thanks.
> **Bot:** No problem. The list is saved if you change your mind.

Result: List is stored but no subscriptions are added.

### Edge Cases

**Invalid or expired link.**

> **User:** Open that shared list.
> **Bot:** That link didn't work. Ask the sender for a new one?

**Already subscribed to all.**

> **User:** Subscribe to all.
> **Bot:** You're already subscribed to all 5. Nothing to add.

**Network error.**

> **User:** Subscribe to all.
> **Bot:** Couldn't load the list. Check your connection and try again.
