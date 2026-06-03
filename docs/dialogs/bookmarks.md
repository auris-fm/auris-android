# Bookmarks

Voice dialog reference for all actions in the Bookmarks category.

---

## Add bookmark

> **User:** Bookmark this spot.
> **Bot:** Bookmarked at 12:34.

Result: Creates a bookmark at the current playback position.

> **User:** Save my place here.
> **Bot:** Bookmark saved at 12:34.

Result: Creates a bookmark at the current position.

> **User:** Add a bookmark.
> **Bot:** Bookmarked at 12:34.

Result: Creates a bookmark at the current playback position.

> **User:** Drop a pin here.
> **Bot:** Bookmarked at 12:34.

Result: Creates a bookmark at the current position.

Edge cases:
- **Nothing playing**:
  > **User:** Bookmark this spot.
  > **Bot:** Nothing is playing right now. Start playing an episode first.
- **Bookmark already exists at this position**:
  > **User:** Bookmark this.
  > **Bot:** You already have a bookmark at this position.

---

## Edit bookmark title

> **User:** Rename this bookmark to "Best part."
> **Bot:** Renamed to "Best part."

Result: Updates the bookmark title to the given text.

> **User:** Change the bookmark title.
> **Bot:** What should I call it?
  > **User:** Funny bit.
  > **Bot:** Renamed to "Funny bit."

Result: Prompts for a new title and applies it.

> **User:** Call this bookmark "Key takeaway."
> **Bot:** Renamed to "Key takeaway."

Result: Updates the bookmark title.

Edge cases:
- **Bookmark not found**:
  > **User:** Rename that bookmark.
  > **Bot:** Which bookmark? I couldn't find a matching one.

---

## Save bookmark

> **User:** Save this bookmark.
> **Bot:** Saved.

Result: Persists the bookmark with any pending edits.

> **User:** Save the bookmark edits.
> **Bot:** Saved.

Result: Saves the current bookmark changes.

> **User:** Done editing, save it.
> **Bot:** Saved.

Result: Saves the bookmark and closes the edit view.

---

## Play bookmark

> **User:** Play the bookmark "Funny bit."
> **Bot:** Jumping to "Funny bit" at 12:34.

Result: Seeks to the bookmarked position and resumes playback.

> **User:** Go to my bookmark.
> **Bot:** Which one? You have: "Funny bit," "Key takeaway," "Best part."
  > **User:** Best part.
  > **Bot:** Jumping to "Best part" at 45:12.

Result: Lists bookmarks and seeks to the chosen one.

> **User:** Jump to the bookmark at 12 minutes.
> **Bot:** Playing from 12:34.

Result: Seeks to the matching bookmark position and plays.

> **User:** Resume from my last bookmark.
> **Bot:** Jumping to "Funny bit" at 12:34.

Result: Seeks to the most recently created bookmark and plays.

Edge cases:
- **No bookmarks exist**:
  > **User:** Play my bookmark.
  > **Bot:** You don't have any bookmarks for this episode yet.
- **Bookmark not found**:
  > **User:** Play the bookmark called "Intro."
  > **Bot:** I couldn't find a bookmark called "Intro."
- **Nothing playing when seeking to bookmark**:
  > **User:** Play the bookmark "Funny bit."
  > **Bot:** Starting playback from "Funny bit" at 12:34.
  Result: Starts the episode and seeks to the bookmarked position.

---

## Sort bookmarks

> **User:** Sort bookmarks by newest first.
> **Bot:** Sorted by newest.

Result: Reorders bookmarks with the most recently created at the top.

> **User:** Sort bookmarks oldest to newest.
> **Bot:** Sorted by oldest.

Result: Reorders bookmarks with the oldest at the top.

> **User:** Sort my bookmarks by timestamp.
> **Bot:** Sorted by position in episode.

Result: Reorders bookmarks chronologically by their position in the episode.

> **User:** Sort bookmarks alphabetically.
> **Bot:** Sorted alphabetically.

Result: Reorders bookmarks by title in alphabetical order.

Edge cases:
- **No bookmarks**:
  > **User:** Sort bookmarks by newest.
  > **Bot:** You don't have any bookmarks to sort.
- **Only one bookmark**:
  > **User:** Sort bookmarks.
  > **Bot:** You only have one bookmark.

---

## Search bookmarks

> **User:** Search bookmarks for "funny."
> **Bot:** Found 2 bookmarks matching "funny."

Result: Filters the bookmark list to show only matching results.

> **User:** Find bookmarks about AI.
> **Bot:** Found 3 bookmarks matching "AI."

Result: Searches bookmark titles for the query.

> **User:** Search my bookmarks.
> **Bot:** What are you looking for?
  > **User:** interview tips.
  > **Bot:** No bookmarks matching "interview tips."

Result: Prompts for a search query and filters the list.

> **User:** Filter bookmarks by "key."
> **Bot:** Found 4 bookmarks matching "key."

Result: Filters bookmarks containing the search term.

Edge cases:
- **No matching bookmarks**:
  > **User:** Search bookmarks for "quantum physics."
  > **Bot:** No bookmarks matching "quantum physics."
- **No bookmarks exist**:
  > **User:** Search my bookmarks for "funny."
  > **Bot:** You don't have any bookmarks yet.

---

## Share bookmark

> **User:** Share this bookmark.
> **Bot:** Opening share for "Funny bit."

Result: Opens the system share sheet with the bookmark details.

> **User:** Send this bookmark to someone.
> **Bot:** Opening share.

Result: Opens the share dialog for the bookmark.

> **User:** Share the bookmark at 12 minutes.
> **Bot:** Opening share for "Funny bit" at 12:34.

Result: Opens the share sheet for the matching bookmark.

Edge cases:
- **Bookmark not found**:
  > **User:** Share the bookmark "Intro."
  > **Bot:** I couldn't find a bookmark called "Intro."
- **Sharing failure**:
  > **User:** Share this bookmark.
  > **Bot:** Couldn't share right now. Try again in a moment.

---

## Delete bookmark

> **User:** Delete this bookmark.
> **Bot:** Deleted "Funny bit."

Result: Removes the bookmark permanently.

> **User:** Remove the bookmark at 12 minutes.
> **Bot:** Deleted bookmark at 12:34.

Result: Deletes the bookmark at the matching position.

> **User:** Delete all my bookmarks for this episode.
> **Bot:** Deleted all 5 bookmarks.

Result: Removes every bookmark for the current episode.

> **User:** Get rid of the bookmark called "Key takeaway."
> **Bot:** Deleted "Key takeaway."

Result: Removes the named bookmark.

Edge cases:
- **Bookmark not found**:
  > **User:** Delete the bookmark "Intro."
  > **Bot:** I couldn't find a bookmark called "Intro."
- **No bookmarks to delete**:
  > **User:** Delete all my bookmarks.
  > **Bot:** You don't have any bookmarks to delete.

---

## Multi-select bookmarks

> **User:** Select all bookmarks.
> **Bot:** All 4 bookmarks selected.

Result: Selects every bookmark and shows the multi-select action bar.

> **User:** Select the first two bookmarks.
> **Bot:** 2 bookmarks selected.

Result: Selects the first two bookmarks in the list.

> **User:** Select bookmarks called "Funny bit" and "Key takeaway."
> **Bot:** 2 bookmarks selected.

Result: Selects the named bookmarks in multi-select mode.

> **User:** Select bookmarks from the first 10 minutes.
> **Bot:** 3 bookmarks selected.

Result: Selects all bookmarks within the first 10 minutes of the episode.

### Multi-select: Delete

> **User:** Delete the selected bookmarks.
> **Bot:** Deleted 3 bookmarks.

Result: Permanently removes the selected bookmarks.

### Multi-select: Edit

> **User:** Edit the selected bookmarks.
> **Bot:** Editing 2 bookmarks.

Result: Opens the batch edit view for the selected bookmarks.

### Multi-select: Share

> **User:** Share the selected bookmarks.
> **Bot:** Opening share for 2 bookmarks.

Result: Opens the share sheet with the selected bookmarks.

Edge cases:
- **No bookmarks to select**:
  > **User:** Select all bookmarks.
  > **Bot:** You don't have any bookmarks.
- **Bookmark name not found**:
  > **User:** Select the bookmark "Intro."
  > **Bot:** I couldn't find a bookmark called "Intro."
- **Number requested exceeds count**:
  > **User:** Select the first 10 bookmarks.
  > **Bot:** You only have 4 bookmarks. Selected all of them.
