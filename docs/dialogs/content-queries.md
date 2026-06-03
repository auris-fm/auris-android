# Content Queries

These are voice-first read-only queries that the GUI doesn't serve well but the app's APIs fully support. Users ask about their listening state, episode metadata, listening statistics, and library aggregates — getting instant spoken answers without navigating to any screen.

---

## What's Playing

Confirmation: implicit

> **User:** What's playing?
> **Bot:** Playing "The Algorithm" from *Recode Decode*. 14:22 in, 31 minutes remaining.
Result: Reads current playback state aloud.

> **User:** What episode is this?
> **Bot:** This is "The Algorithm" from *Recode Decode*. 14:22 in, 31 minutes remaining.
Result: Same info, natural paraphrase.

> **User:** What's on?
> **Bot:** "The Algorithm" from *Recode Decode*. 14:22 in.
Result: Brief answer with position.

> **User:** What am I listening to?
> **Bot:** "The Algorithm" from *Recode Decode*. 31 minutes left.
Result: Brief answer with time remaining.

### Edge cases

- **Nothing playing:**
  > **User:** What's playing?
  > **Bot:** Nothing is playing right now. Say "play" to resume, or pick a podcast.

---

## Where Am I

Confirmation: implicit

> **User:** Where am I?
> **Bot:** 14:22 in. 31 minutes remaining, 32% through.
Result: Reports current position, time remaining, and percentage.

> **User:** How far along am I?
> **Bot:** 14:22 in. 31 minutes remaining, 32% through.
Result: Same info.

> **User:** Where am I in this episode?
> **Bot:** 14:22 in. 31 minutes remaining.
Result: Position and time remaining.

> **User:** What's my progress?
> **Bot:** 32% through. 31 minutes remaining.
Result: Percentage and time remaining.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

- **Just started:**
  > **User:** Where am I?
  > **Bot:** Just started. 45 minutes remaining.

- **Almost done:**
  > **User:** How far along am I?
  > **Bot:** 44:50 in. 32 seconds remaining. Almost done!

---

## How Much Is Left

Confirmation: implicit

> **User:** How much is left?
> **Bot:** 31 minutes remaining.
Result: Reports time remaining.

> **User:** When does this end?
> **Bot:** 31 minutes remaining.
Result: Reports time remaining.

> **User:** How much longer?
> **Bot:** 31 minutes left.
Result: Reports time remaining.

> **User:** How much time is left in this episode?
> **Bot:** 31 minutes remaining.
Result: Reports time remaining.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

- **Less than a minute:**
  > **User:** How much is left?
  > **Bot:** 32 seconds remaining.

---

## What Podcast Is This

Confirmation: implicit

> **User:** What podcast is this from?
> **Bot:** This is from *Recode Decode*.
Result: Reports podcast name.

> **User:** What show is this?
> **Bot:** This is *Recode Decode*.
Result: Reports podcast name.

> **User:** Which podcast am I listening to?
> **Bot:** You're listening to *Recode Decode*.
Result: Reports podcast name.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Episode Duration

Confirmation: implicit

> **User:** How long is this episode?
> **Bot:** "The Algorithm" is 45 minutes.
Result: Reports total duration.

> **User:** What's the length of this episode?
> **Bot:** 45 minutes.
Result: Reports total duration.

> **User:** How long is this one?
> **Bot:** 45 minutes.
Result: Reports total duration.

> **User:** Is this a long episode?
> **Bot:** 45 minutes. That's about average for *Recode Decode*.
Result: Reports duration with context from the podcast's typical range.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Publish Date

Confirmation: implicit

> **User:** When was this published?
> **Bot:** Published June 1st, 2026.
Result: Reports publish date.

> **User:** When did this come out?
> **Bot:** Published June 1st, 2026.
Result: Reports publish date.

> **User:** How old is this episode?
> **Bot:** Published June 1st. That was yesterday.
Result: Reports date with relative time.

> **User:** Is this a recent episode?
> **Bot:** Published June 1st. That was yesterday.
Result: Reports date with relative time.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Episode Description

Confirmation: implicit

> **User:** What's this episode about?
> **Bot:** The host interviews a tech CEO about the future of AI regulation and its impact on startups.
Result: Reads the short description aloud.

> **User:** Can you tell me about this episode?
> **Bot:** The host interviews a tech CEO about the future of AI regulation and its impact on startups.
Result: Reads the short description aloud.

> **User:** What's the description?
> **Bot:** The host interviews a tech CEO about the future of AI regulation and its impact on startups.
Result: Reads the short description aloud.

> **User:** What is this one about?
> **Bot:** A deep dive into AI regulation with a tech CEO. They cover startups, policy, and what comes next.
Result: Reads the short description aloud.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

- **No description available:**
  > **Bot:** There's no description for this episode.

- **Description is very long:**
  > **User:** What's this episode about?
  > **Bot:** Here's the summary: the host interviews a tech CEO about AI regulation. Want me to read the full description?
  > **User:** Yes.
  > **Bot:** [reads full description]
Result: Summarizes first, then offers to read in full.

---

## Download Status

Confirmation: implicit

> **User:** Is this downloaded?
> **Bot:** Yes, 42 MB.
Result: Reports download status and file size.

> **User:** Do I have this offline?
> **Bot:** Yes, 42 MB downloaded.
Result: Reports download status.

> **User:** Is this saved?
> **Bot:** No, it's streamed.
Result: Reports that the episode is not downloaded.

> **User:** Is this taking up space?
> **Bot:** Yes, 42 MB.
Result: Reports download status and file size.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

- **Download in progress:**
  > **User:** Is this downloaded?
  > **Bot:** Still downloading. 68% complete.

---

## Episode Title

Confirmation: implicit

> **User:** What's the title of this episode?
> **Bot:** "The Algorithm".
Result: Reports episode title.

> **User:** What's this one called?
> **Bot:** "The Algorithm".
Result: Reports episode title.

> **User:** What's the episode name?
> **Bot:** "The Algorithm".
Result: Reports episode title.

### Edge cases

- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

---

## Listening Time

Confirmation: implicit

> **User:** How long have I listened this week?
> **Bot:** You've listened for 12 hours this week.
Result: Reports listening time for the requested period.

> **User:** How much have I listened today?
> **Bot:** You've listened for 1 hour and 20 minutes today.
Result: Reports listening time for today.

> **User:** How long have I listened all time?
> **Bot:** You've listened for 487 hours total. That's about 20 days!
Result: Reports total listening time with human-readable context.

> **User:** How much this month?
> **Bot:** You've listened for 34 hours this month.
Result: Reports listening time for the current month.

### Edge cases

- **No listening history (new user):**
  > **User:** How long have I listened?
  > **Bot:** You haven't listened to anything yet. Start with a podcast from your library!

- **Stats unavailable:**
  > **Bot:** I couldn't pull up your listening stats right now. Try again in a moment.

---

## Top Podcasts

Confirmation: implicit

> **User:** What do I listen to the most?
> **Bot:** Your top 3: *Recode Decode* (87 hours), *Hard Fork* (62 hours), *The Daily* (41 hours).
Result: Reports top podcasts by listening time.

> **User:** What's my most-listened podcast?
> **Bot:** *Recode Decode*, with 87 hours.
Result: Reports single top podcast.

> **User:** What are my favorite podcasts?
> **Bot:** Your top 3 by listening time: *Recode Decode* (87 hours), *Hard Fork* (62 hours), *The Daily* (41 hours).
Result: Reports top 3 podcasts.

> **User:** What shows do I listen to most?
> **Bot:** Your top 3: *Recode Decode* (87 hours), *Hard Fork* (62 hours), *The Daily* (41 hours).
Result: Reports top 3 podcasts.

### Edge cases

- **No listening history:**
  > **Bot:** You haven't listened to anything yet. Try playing an episode!

- **Only one podcast:**
  > **User:** What do I listen to the most?
  > **Bot:** You've only listened to *Recode Decode*, with 12 hours total.

---

## Episodes Finished

Confirmation: implicit

> **User:** How many episodes have I finished this month?
> **Bot:** You finished 14 episodes this month.
Result: Reports completed episode count.

> **User:** How many episodes did I complete this week?
> **Bot:** You finished 3 episodes this week.
Result: Reports completed episode count.

> **User:** How many have I finished all time?
> **Bot:** You've finished 203 episodes total.
Result: Reports total completed episodes.

> **User:** How many episodes have I listened to?
> **Bot:** You've finished 203 episodes total.
Result: Reports total completed episodes.

### Edge cases

- **No listening history:**
  > **Bot:** You haven't finished any episodes yet.

- **Zero for the period:**
  > **User:** How many episodes have I finished today?
  > **Bot:** You haven't finished any episodes today. You're 20 minutes into one though!

---

## Listening Streak

Confirmation: implicit

> **User:** What's my listening streak?
> **Bot:** You've listened 12 days in a row!
Result: Reports current listening streak.

> **User:** How many days in a row have I listened?
> **Bot:** 12 days in a row. Keep it going!
Result: Reports current listening streak.

> **User:** What's my streak?
> **Bot:** 12 days in a row!
Result: Reports current listening streak.

### Edge cases

- **No streak (hasn't listened recently):**
  > **User:** What's my listening streak?
  > **Bot:** No active streak right now. Listen today to start one!

- **No listening history:**
  > **Bot:** You haven't listened to anything yet. Start a streak by playing an episode!

- **Streak just started:**
  > **User:** What's my streak?
  > **Bot:** 1 day. The beginning of a great streak!

---

## Subscription Count

Confirmation: implicit

> **User:** How many podcasts am I subscribed to?
> **Bot:** 23 podcasts in your library.
Result: Reports subscription count.

> **User:** How many shows do I follow?
> **Bot:** 23 podcasts in your library.
Result: Reports subscription count.

> **User:** How big is my library?
> **Bot:** 23 podcasts in your library.
Result: Reports subscription count.

### Edge cases

- **No subscriptions:**
  > **User:** How many podcasts am I subscribed to?
  > **Bot:** Your library is empty. Say "search for [podcast]" to add some!

---

## Unplayed Total

Confirmation: implicit

> **User:** How many unplayed episodes do I have?
> **Bot:** 47 unplayed episodes across 18 podcasts.
Result: Reports total unplayed count and podcast breakdown.

> **User:** How much is in my inbox?
> **Bot:** 47 unplayed episodes across 18 podcasts.
Result: Reports total unplayed count.

> **User:** How many episodes do I have to catch up on?
> **Bot:** 47 unplayed episodes across 18 podcasts.
Result: Reports total unplayed count.

### Edge cases

- **No unplayed episodes:**
  > **Bot:** You're all caught up! No unplayed episodes.

- **No subscriptions:**
  > **Bot:** Your library is empty. Subscribe to a podcast to get started.

---

## Download Stats

Confirmation: implicit

> **User:** How much storage are downloads using?
> **Bot:** 38 downloaded episodes, 2.4 GB.
Result: Reports download count and total storage.

> **User:** How many episodes are downloaded?
> **Bot:** 38 downloaded episodes, 2.4 GB.
Result: Reports download count and total storage.

> **User:** How much space are my podcasts taking?
> **Bot:** 2.4 GB across 38 downloaded episodes.
Result: Reports total storage and download count.

### Edge cases

- **No downloads:**
  > **User:** How many episodes are downloaded?
  > **Bot:** You have no downloaded episodes. Everything is streamed.

- **Stats unavailable:**
  > **Bot:** I couldn't check your downloads right now. Try again in a moment.

---

## Queue Total

Confirmation: implicit

> **User:** How much listening is in my queue?
> **Bot:** Your queue has 8 episodes, about 5 hours total.
Result: Reports queue count and total listening time.

> **User:** How many episodes are in my queue?
> **Bot:** 8 episodes in your queue, about 5 hours total.
Result: Reports queue count and total time.

> **User:** How long is my queue?
> **Bot:** 8 episodes, about 5 hours of listening.
Result: Reports count and total time.

> **User:** What's the total time in my queue?
> **Bot:** About 5 hours across 8 episodes.
Result: Reports total time and count.

### Edge cases

- **Empty queue:**
  > **User:** How much listening is in my queue?
  > **Bot:** Your queue is empty. Add episodes by saying "add to queue".

---

## New Episodes

Confirmation: implicit

> **User:** What's new since I last listened?
> **Bot:** 5 new episodes. Latest: "The Algorithm" from *Recode Decode*, 2 hours ago.
Result: Reports new episode count with the most recent.

> **User:** How many new episodes dropped today?
> **Bot:** 3 new episodes today. Latest: "Hard Fork" from *The New York Times*, 1 hour ago.
Result: Reports today's new episodes with the latest.

> **User:** What's new?
> **Bot:** 5 new episodes. Latest: "The Algorithm" from *Recode Decode*, 2 hours ago.
Result: Reports new episode count with the most recent.

> **User:** Any new episodes?
> **Bot:** 5 new episodes since your last session. Latest: "The Algorithm" from *Recode Decode*.
Result: Reports count with latest episode.

### Edge cases

- **Nothing new:**
  > **User:** What's new?
  > **Bot:** No new episodes since your last session. You're all caught up!

- **No subscriptions:**
  > **Bot:** You're not subscribed to any podcasts yet. Say "search for [podcast]" to get started.

- **Network error:**
  > **Bot:** Couldn't check for new episodes right now. Check your connection and try again.

---

## Time Since Last Listen

Confirmation: implicit

> **User:** When did I last listen?
> **Bot:** Your last session was 3 hours ago. You were listening to "The Algorithm" from *Recode Decode*.
Result: Reports time since last session and what was playing.

> **User:** How long has it been since I listened?
> **Bot:** 3 hours since your last session. You were listening to "The Algorithm".
Result: Reports elapsed time and last episode.

> **User:** When was my last session?
> **Bot:** 3 hours ago. You were listening to "The Algorithm" from *Recode Decode*.
Result: Reports time and last episode.

### Edge cases

- **No listening history:**
  > **Bot:** You haven't listened to anything yet. Say "play" to get started!

- **Currently playing:**
  > **User:** When did I last listen?
  > **Bot:** You're listening right now! "The Algorithm" from *Recode Decode*.
