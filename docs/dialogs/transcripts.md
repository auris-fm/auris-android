# Transcripts

Voice dialog reference for all actions in the Transcripts category.

---

## Open transcript

> **User:** Open the transcript.
> **Bot:** Opening transcript.

Result: Opens the transcript view for the current episode.

> **User:** Show me the transcript.
> **Bot:** Here's the transcript.

Result: Opens the transcript overlay/panel for the current episode.

> **User:** Read along.
> **Bot:** Opening transcript.

Result: Opens the transcript view, scrolled to the current playback position.

> **User:** Show transcript for this episode.
> **Bot:** Opening transcript.

Result: Opens the transcript for the current episode.

Edge cases:
- **No transcript available**:
  > **User:** Open the transcript.
  > **Bot:** This episode doesn't have a transcript available.
- **Nothing playing**:
  > **User:** Show me the transcript.
  > **Bot:** Nothing is playing right now. Start an episode first.
- **Transcript failed to load**:
  > **User:** Open the transcript.
  > **Bot:** The transcript couldn't load. Want to try again?

---

## Search in transcript

> **User:** Search the transcript for "climate change."
> **Bot:** Found 3 matches for "climate change."

Result: Highlights all matches in the transcript and scrolls to the first one.

> **User:** Find where they talk about AI.
> **Bot:** Found 5 matches for "AI."

Result: Searches the transcript text and highlights matching lines.

> **User:** Search transcript for "quantum computing."
> **Bot:** No matches for "quantum computing."

Result: Searches the transcript but finds no results; displays the "no matches" state.

> **User:** Look for "election" in the transcript.
> **Bot:** Found 2 matches for "election."

Result: Filters or highlights matching transcript lines.

Edge cases:
- **No matches found**:
  > **User:** Search for "banana."
  > **Bot:** No matches found in this transcript.
- **Transcript not loaded yet**:
  > **User:** Search the transcript for "AI."
  > **Bot:** The transcript is still loading. Try again in a moment.
- **Transcript not open**:
  > **User:** Search the transcript for "AI."
  > **Bot:** Let me open the transcript first.
  Result: Opens the transcript and then searches.

---

## Navigate matches

> **User:** Next match.
> **Bot:** Match 2 of 5.

Result: Scrolls to the next search match in the transcript.

> **User:** Previous match.
> **Bot:** Match 4 of 5.

Result: Scrolls to the previous search match in the transcript.

> **User:** Go to the third match.
> **Bot:** Match 3 of 5.

Result: Jumps directly to the third search match.

> **User:** Jump to the last result.
> **Bot:** Match 5 of 5.

Result: Scrolls to the final search match.

Edge cases:
- **No active search**:
  > **User:** Next match.
  > **Bot:** Search the transcript first and I can navigate through matches.
- **Already on the last match**:
  > **User:** Next match.
  > **Bot:** That's the last match. Go back to the first?
- **Already on the first match**:
  > **User:** Previous match.
  > **Bot:** That's the first match.

---

## Clear search

> **User:** Clear the search.
> **Bot:** Search cleared.

Result: Removes the search highlight and returns to the full transcript view.

> **User:** Stop searching.
> **Bot:** Search cleared.

Result: Clears the search filter and resets the transcript view.

> **User:** Show the whole transcript again.
> **Bot:** Search cleared, showing full transcript.

Result: Removes search highlighting and shows the complete transcript.

---

## Seek to position

> **User:** Go to where it says "the stock market crashed."
> **Bot:** Jumping to that part.

Result: Seeks playback to the timestamp where the quoted text appears in the transcript.

> **User:** Tap on "climate change" in the transcript.
> **Bot:** Playing from that point.

Result: Seeks to the position of the tapped transcript text.

> **User:** Jump to the part about machine learning.
> **Bot:** Jumping to the section on machine learning.

Result: Seeks to the approximate timestamp where machine learning is discussed.

> **User:** Skip to where they mention Tesla.
> **Bot:** Playing from that point.

Result: Seeks playback to the transcript line mentioning Tesla.

Edge cases:
- **Text not found in transcript**:
  > **User:** Go to where they talk about gardening.
  > **Bot:** I couldn't find that in the transcript. Want me to search for it?
- **Nothing playing**:
  > **User:** Jump to the part about AI.
  > **Bot:** Starting playback from that point.
  Result: Starts the episode and seeks to the matched position.

---

## Share transcript

> **User:** Share the transcript.
> **Bot:** Opening share for the transcript.

Result: Opens the system share sheet with the transcript content or link.

> **User:** Send this transcript to someone.
> **Bot:** Opening share.

Result: Opens the share dialog for the transcript.

> **User:** Share the transcript as text.
> **Bot:** Opening share for transcript text.

Result: Opens the share sheet with the transcript formatted as plain text.

Edge cases:
- **Transcript not loaded**:
  > **User:** Share the transcript.
  > **Bot:** The transcript hasn't loaded yet. Try again in a moment.
- **Sharing failure**:
  > **User:** Share the transcript.
  > **Bot:** Couldn't share right now. Try again in a moment.

---

## Reload transcript

> **User:** Reload the transcript.
> **Bot:** Reloading transcript.

Result: Fetches a fresh copy of the transcript from the server.

> **User:** The transcript looks wrong, refresh it.
> **Bot:** Refreshing transcript.

Result: Reloads the transcript data.

> **User:** Try loading the transcript again.
> **Bot:** Reloading.

Result: Attempts to fetch the transcript again.

Edge cases:
- **Network error**:
  > **User:** Reload the transcript.
  > **Bot:** Couldn't reload. Check your connection and try again.
- **Transcript not available**:
  > **User:** Reload the transcript.
  > **Bot:** This episode doesn't have a transcript available.
- **Transcript not open**:
  > **User:** Reload the transcript.
  > **Bot:** Let me open the transcript first.
  Result: Opens the transcript view and then reloads.
