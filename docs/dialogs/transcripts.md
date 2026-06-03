# Transcripts

Transcripts are inherently voice-friendly. Users search episode content by speaking naturally, navigate by meaning rather than timestamp, and ask questions about what's being said. Every action is designed for eyes-free use — reading text aloud, jumping to passages, and answering questions about content without looking at a screen.

---

## Open Transcript

Confirmation: implicit

> **User:** Show me the transcript.
> **Bot:** [confirm]
Result: Transcript panel opens and syncs to current playback position.

> **User:** Open transcript.
> **Bot:** [confirm]
Result: Transcript panel opens.

> **User:** Can I see the transcript?
> **Bot:** [confirm]
Result: Transcript panel opens.

> **User:** Read along.
> **Bot:** [confirm]
Result: Transcript panel opens and highlights the current line.

### Edge cases

- **No transcript available:**
  > **User:** Show me the transcript.
  > **Bot:** [error]

- **Transcript failed to load:**
  > **User:** Open transcript.
  > **Bot:** Couldn't load the transcript. Try again in a moment.

- **Nothing playing:**
  > **Bot:** Nothing is playing right now. Play an episode first.

---

## Search Transcript

Confirmation: implicit

> **User:** Search the transcript for climate change.
> **Bot:** Found 7 mentions of "climate change". First at 12:34.
Result: Transcript highlights all matches and scrolls to the first one.

> **User:** Does the transcript mention AI?
> **Bot:** Found 3 mentions of "AI". First at 5:12.
Result: All matches highlighted, scrolled to first.

> **User:** Find where they talk about Paris.
> **Bot:** Found 4 mentions of "Paris". First at 22:08.
Result: All matches highlighted, scrolled to first.

> **User:** Is there anything about regulation?
> **Bot:** Found 2 mentions of "regulation". First at 31:15.
Result: All matches highlighted, scrolled to first.

### Edge cases

- **No matches:**
  > **User:** Search the transcript for cryptocurrency.
  > **Bot:** No mentions of "cryptocurrency" found. Want me to try a similar term?

- **No transcript available:**
  > **Bot:** [error]

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Navigate Matches

Confirmation: implicit

> **User:** Next match.
> **Bot:** At 14:22. "...the impact of **climate change** on coastal cities..."
Result: Seeks to next match and reads surrounding context.

> **User:** Previous match.
> **Bot:** At 12:34. "...**climate change** is accelerating faster than..."
Result: Seeks to previous match and reads surrounding context.

> **User:** Go to the third one.
> **Bot:** At 22:08. "...we discussed **climate change** policy with..."
Result: Seeks to the third match and reads surrounding context.

> **User:** Next.
> **Bot:** At 28:41. "...addressing **climate change** requires global..."
Result: Seeks to next match.

### Edge cases

- **No active search:**
  > **User:** Next match.
  > **Bot:** [error]

- **Already at last match:**
  > **User:** Next match.
  > **Bot:** That was the last match. Back to the first?
  > **User:** Yes.
  > **Bot:** At 12:34. "...the impact of **climate change** on..."
Result: Wraps to first match.

- **Already at first match:**
  > **User:** Previous match.
  > **Bot:** That was the first match. Go to the last?
  > **User:** Sure.
  > **Bot:** At 45:03. "...**climate change** will define the next..."
Result: Wraps to last match.

---

## Content-Based Seek

Confirmation: implicit

> **User:** Skip to where they talk about climate.
Result: Seeks playback to the first transcript mention of "climate" at 12:34.

> **User:** Jump to the part about Paris.
Result: Seeks playback to the first transcript mention of "Paris" at 22:08.

> **User:** Go to where the guest makes their main argument.
Result: Seeks to the best-matching section at 35:10.

> **User:** When do they discuss regulation?
Result: Seeks to the first mention of "regulation" at 31:15.

### Edge cases

- **No transcript available:**
  > **Bot:** [error]

- **Topic not found:**
  > **User:** Skip to where they talk about cryptocurrency.
  > **Bot:** I couldn't find that topic. The closest match is "finance" at 18:42. Jump there?
  > **User:** Yes.
Result: Seeks to closest match at 18:42.

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Read Current Line

Confirmation: implicit

> **User:** What are they saying right now?
> **Bot:** "...and that's why the policy needs to change at a fundamental level."
Result: Reads the current transcript line aloud.

> **User:** What did they just say?
> **Bot:** "...and that's why the policy needs to change at a fundamental level."
Result: Reads the most recently spoken line.

> **User:** Read that again.
> **Bot:** "...and that's why the policy needs to change at a fundamental level."
Result: Re-reads the current line.

> **User:** What was that?
> **Bot:** "...and that's why the policy needs to change at a fundamental level."
Result: Reads the current line.

### Edge cases

- **No transcript:**
  > **Bot:** [error]

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

- **Between lines (silence):**
  > **Bot:** Nothing being said right now. They paused at 14:22.

---

## Query Topic

Confirmation: implicit

> **User:** Do they mention AI?
> **Bot:** Yes, "AI" comes up 3 times. First mention at 5:12.
Result: Reports count and first timestamp.

> **User:** Is regulation discussed in this episode?
> **Bot:** Yes, "regulation" comes up 2 times. First mention at 31:15.
Result: Reports count and first timestamp.

> **User:** Do they talk about Paris?
> **Bot:** Yes, "Paris" comes up 4 times. First mention at 22:08.
Result: Reports count and first timestamp.

> **User:** Does this episode get into privacy concerns?
> **Bot:** Yes, "privacy" comes up 5 times. First mention at 8:45.
Result: Reports count and first timestamp.

### Edge cases

- **Topic not found:**
  > **User:** Do they mention cryptocurrency?
  > **Bot:** No mentions of "cryptocurrency" in this episode. The closest topic is "finance" with 2 mentions.

- **No transcript:**
  > **Bot:** [error]

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Find Quote

Confirmation: implicit

> **User:** Find where they say "the future of work is remote".
> **Bot:** Found at 41:07: "the future of work is remote."
Result: Seeks to the exact quote and highlights it.

> **User:** Where do they say "this changes everything"?
> **Bot:** Found at 15:33: "this changes everything."
Result: Seeks to the exact quote.

> **User:** Find the part about "never giving up".
> **Bot:** Found at 52:20: "you just never give up, no matter what."
Result: Seeks to the closest match containing the phrase.

> **User:** Where does she say "we built this from nothing"?
> **Bot:** Found at 33:45: "we built this from nothing."
Result: Seeks to the matching quote.

### Edge cases

- **Quote not found:**
  > **User:** Find where they say "technology will save us all".
  > **Bot:** Couldn't find that exact quote. The closest match is at 19:12: "technology alone won't save us." Jump there?
  > **User:** Yes.
Result: Seeks to closest match at 19:12.

- **Multiple matches:**
  > **User:** Find where they say "you know".
  > **Bot:** That phrase appears 14 times. First at 2:15. Want a more specific quote?

- **No transcript:**
  > **Bot:** [error]

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Read Section

Confirmation: implicit

> **User:** Read from 15 minutes to 20 minutes.
> **Bot:** Reading from 15:00 to 20:00.
Result: Reads transcript text for that range aloud. Playback does not change.

> **User:** Read the next minute.
> **Bot:** Reading the next minute.
Result: Reads transcript text from current position through the next 60 seconds.

> **User:** Read from here to the end of the chapter.
> **Bot:** Reading from 34:12 to 38:50.
Result: Reads transcript text for that section.

> **User:** Read the last two minutes.
> **Bot:** Reading from 43:15 to 45:22.
Result: Reads transcript text for the final two minutes of the episode.

### Edge cases

- **Range extends past end of episode:**
  > **User:** Read from 40 minutes to 50 minutes.
  > **Bot:** The episode ends at 45:22. Reading from 40:00 to the end.

- **Invalid range (start after end):**
  > **User:** Read from 50 minutes to 40 minutes.
  > **Bot:** That start time is past the end of the episode. The episode is 45 minutes long.

- **No transcript:**
  > **Bot:** [error]

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

- **Section is very long:**
  > **User:** Read the whole transcript.
  > **Bot:** That's a 45-minute episode. I can read a shorter section instead. How much do you need?
