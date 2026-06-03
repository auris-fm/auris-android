# Sharing

Voice dialog reference for all actions in the Sharing category.

---

## Share episode

> **User:** Share this episode.
> **Bot:** Opening share for "The Future of Urban Transit."

Result: Opens the system share sheet with a link to the episode.

> **User:** Send this episode to a friend.
> **Bot:** Opening share.

Result: Opens the share dialog with the episode link.

> **User:** Share the current episode.
> **Bot:** Opening share for "The Future of Urban Transit."

Result: Opens the share sheet with the current episode.

> **User:** Let me share this one.
> **Bot:** Opening share.

Result: Opens the share sheet for the episode.

Edge cases:
- **No episode selected:**
  > **User:** Share this episode.
  > **Bot:** Which episode? Open one first or tell me the name.
- **Sharing failure:**
  > **User:** Share this episode.
  > **Bot:** Couldn't share right now. Try again in a moment.
- **No sharing apps installed:**
  > **User:** Share this episode.
  > **Bot:** No apps available to share with. Install a messaging or email app first.

---

## Share episode at timestamp

> **User:** Share this episode at the current timestamp.
> **Bot:** Opening share for "The Future of Urban Transit" at 12:34.

Result: Opens the share sheet with a timestamped link to the episode.

> **User:** Share from here.
> **Bot:** Opening share at 12:34.

Result: Opens the share sheet with a link starting at the current playback position.

> **User:** Send a link to this part of the episode.
> **Bot:** Opening share at 12:34.

Result: Opens the share dialog with a timestamped episode link.

> **User:** Share at 15 minutes.
> **Bot:** Opening share at 15:00.

Result: Opens the share sheet with a link starting at the 15-minute mark.

Edge cases:
- **Nothing playing and no position specified:**
  > **User:** Share from here.
  > **Bot:** Nothing is playing. Share the episode without a timestamp instead?
- **Timestamp exceeds episode duration:**
  > **User:** Share at 2 hours.
  > **Bot:** That's past the end of the episode.

---

## Share podcast

> **User:** Share this podcast.
> **Bot:** Opening share for "The Daily Podcast."

Result: Opens the share sheet with a link to the podcast.

> **User:** Send the podcast link to someone.
> **Bot:** Opening share.

Result: Opens the share dialog with the podcast link.

> **User:** Share the podcast with a friend.
> **Bot:** Opening share for "The Daily Podcast."

Result: Opens the share sheet for the podcast.

> **User:** Let me share this show.
> **Bot:** Opening share.

Result: Opens the share sheet with the podcast link.

Edge cases:
- **No podcast selected:**
  > **User:** Share this podcast.
  > **Bot:** Which podcast? Open one first or tell me the name.
- **Sharing failure:**
  > **User:** Share this podcast.
  > **Bot:** Couldn't share right now. Try again in a moment.

---

## Share clip

> **User:** Share this clip.
> **Bot:** Opening share for your clip.

Result: Opens the share sheet with the audio clip.

> **User:** Send this clip to someone.
> **Bot:** Opening share.

Result: Opens the share dialog for the clip.

> **User:** Share the clip I just made.
> **Bot:** Opening share for your clip.

Result: Opens the share sheet with the clip.

Edge cases:
- **Clip too short to share:**
  > **User:** Share this clip.
  > **Bot:** The clip is too short to share. It needs to be at least 5 seconds.
- **No clip created:**
  > **User:** Share this clip.
  > **Bot:** You haven't created a clip yet. Make one first.
- **Sharing failure:**
  > **User:** Share the clip.
  > **Bot:** Couldn't share right now. Try again in a moment.

---

## Share bookmark

> **User:** Share this bookmark.
> **Bot:** Opening share for "Funny bit."

Result: Opens the share sheet with the bookmark details and timestamp.

> **User:** Send this bookmark to someone.
> **Bot:** Opening share.

Result: Opens the share dialog for the bookmark.

> **User:** Share the bookmark at 12 minutes.
> **Bot:** Opening share for "Funny bit" at 12:34.

Result: Opens the share sheet for the matching bookmark.

> **User:** Share my bookmark.
> **Bot:** Which one? You have: "Funny bit," "Key takeaway," "Best part."
  > **User:** Best part.
  > **Bot:** Opening share for "Best part."

Result: Lists bookmarks and opens share for the chosen one.

Edge cases:
- **Bookmark not found:**
  > **User:** Share the bookmark "Intro."
  > **Bot:** I couldn't find a bookmark called "Intro."
- **No bookmarks:**
  > **User:** Share a bookmark.
  > **Bot:** You don't have any bookmarks to share.
- **Sharing failure:**
  > **User:** Share this bookmark.
  > **Bot:** Couldn't share right now. Try again in a moment.

---

## Share transcript

> **User:** Share this transcript.
> **Bot:** Opening share for the transcript.

Result: Opens the share sheet with the transcript or a link to it.

> **User:** Share the transcript of this episode.
> **Bot:** Opening share.

Result: Opens the share dialog with the transcript content.

> **User:** Send the transcript to someone.
> **Bot:** Opening share for the transcript.

Result: Opens the share sheet with the transcript.

Edge cases:
- **Transcript not available:**
  > **User:** Share the transcript.
  > **Bot:** This episode doesn't have a transcript.
- **No episode selected:**
  > **User:** Share the transcript.
  > **Bot:** Which episode? Open one first.

---

## Create / share shared list

> **User:** Create a shared list.
> **Bot:** What would you like to name it?
  > **User:** My favorites.
  > **Bot:** Created shared list "My favorites."

Result: Creates a new shared podcast list with the given name.

> **User:** Make a shared podcast list.
> **Bot:** What should I call it?
  > **User:** Tech podcasts.
  > **Bot:** Created "Tech podcasts."

Result: Creates a shared list and prompts for a name.

> **User:** Share my podcast list.
> **Bot:** Opening share for "My favorites."

Result: Opens the share sheet with a link to the shared list.

> **User:** Share the list "Tech podcasts."
> **Bot:** Opening share for "Tech podcasts."

Result: Opens the share sheet for the named list.

Edge cases:
- **No lists to share:**
  > **User:** Share my podcast list.
  > **Bot:** You don't have any shared lists yet. Create one first.
- **List name not found:**
  > **User:** Share the list "News."
  > **Bot:** I couldn't find a list called "News."
- **Shared list creation failure:**
  > **User:** Create a shared list.
  > **Bot:** Couldn't create the list right now. Try again in a moment.

---

## Accept incoming shared list

> **User:** Open this shared list.
> **Bot:** Adding "Sarah's picks" to your library.

Result: Imports the shared list and adds it to the user's podcast library.

> **User:** Accept the shared list.
> **Bot:** Adding "Tech podcasts" to your library.

Result: Imports the shared list from the link.

> **User:** Add this list to my podcasts.
> **Bot:** Adding "My favorites" to your library.

Result: Imports the shared list.

Edge cases:
- **Invalid shared list link:**
  > **User:** Open this shared list.
  > **Bot:** This link doesn't look like a valid shared list.
- **List already added:**
  > **User:** Accept the shared list.
  > **Bot:** You already have "Tech podcasts" in your library.
- **Network error accepting list:**
  > **User:** Add this list to my podcasts.
  > **Bot:** Couldn't load the list. Check your connection and try again.
