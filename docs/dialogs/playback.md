# Playback

Playback is the primary voice use case. Users control playback hands-free while driving, cooking, walking, or falling asleep. Transport actions are self-confirming — the audio change itself tells the user it worked. Earcons are used for state changes without audible side effects.

**Response strategy:**
- Transport (play, pause, skip, seek) → silent or earcon; the audio change IS the confirmation
- Effects and toggles → earcon only
- Sleep timer → earcon only
- Queries → spoken response

---

## Transport

### Play / Resume

Confirmation: implicit (silent — audio resumes)

> **User:** Play.
Result: Podcast audio resumes.

> **User:** Resume.
Result: Audio resumes from paused position.

> **User:** Keep going.
Result: Audio resumes.

> **User:** What was I listening to?
> **Bot:** *The Missing Cryptoqueen*, Episode 4 at 12:34. Resuming.
Result: Bot announces episode and resumes.

#### Edge cases
- **Nothing to resume:**
  > **Bot:** Nothing to resume. Want me to play something from your queue?
- **Already playing:**
  > **Bot:** [error]

### Pause

Confirmation: implicit (earcon — soft pause tone)

> **User:** Pause.
> **Bot:** [pause]
Result: Playback paused.

> **User:** Hold on.
> **Bot:** [pause]
Result: Playback paused.

> **User:** Stop.
> **Bot:** [pause]
Result: Playback paused.

#### Edge cases
- **Not playing:**
  > **Bot:** [error]

### Skip Forward

Confirmation: implicit (silent — audio jumps)

> **User:** Skip ahead.
Result: Skips forward by default amount.

> **User:** Skip 2 minutes.
Result: Skips forward 2 minutes.

> **User:** Jump forward 45 seconds.
Result: Skips forward 45 seconds.

> **User:** Fast forward a bit.
Result: Skips forward by default amount.

#### Edge cases
- **Near end of episode:**
  > **Bot:** Skipped to the end. 5 seconds remaining.
- **Nothing playing:**
  > **Bot:** [error]

### Skip Backward

Confirmation: implicit (silent — audio jumps)

> **User:** Go back.
Result: Skips backward by default amount.

> **User:** Rewind 30 seconds.
Result: Skips backward 30 seconds.

> **User:** Back that up a minute.
Result: Skips backward 1 minute.

#### Edge cases
- **Near start:**
  > **Bot:** Back to the beginning.

### Seek to Position

Confirmation: implicit (silent — audio jumps)

> **User:** Go to 15 minutes.
Result: Seeks to 15:00.

> **User:** Skip to 45 minutes in.
Result: Seeks to 45:00.

> **User:** Jump to the last 10 minutes.
Result: Seeks to duration minus 10 minutes.

> **User:** Go back to the beginning.
Result: Seeks to 0:00.

#### Edge cases
- **Position beyond duration:**
  > **Bot:** Episode is only [duration]. Jumped to the end.
- **Nothing playing:**
  > **Bot:** [error]

### Skip to Next Episode

Confirmation: implicit (earcon + brief announcement)

> **User:** Next episode.
> **Bot:** [skip] *Episode 5 — The Getaway*.
Result: Skips to next episode. Bot announces title.

> **User:** Skip this one.
> **Bot:** [skip] *Episode 5 — The Getaway*.
Result: Skips to next episode.

#### Edge cases
- **No next episode:**
  > **Bot:** That was the last in your queue. Want me to find something else?
- **Nothing playing:**
  > **Bot:** [error]

---

## Playback Effects

### Set Playback Speed

Confirmation: implicit (earcon — speed confirmation tone)

> **User:** Speed up to 1.5.
> **Bot:** [confirm] 1.5x.
Result: Speed changed.

> **User:** 2x speed.
> **Bot:** [confirm] 2x.
Result: Speed changed.

> **User:** Slow down.
> **Bot:** [confirm] 0.8x.
Result: Speed reduced by one step.

> **User:** Normal speed.
> **Bot:** [confirm] 1x.
Result: Speed reset.

> **User:** Faster.
> **Bot:** [confirm] 1.5x.
Result: Speed increased.

#### Edge cases
- **Already at max/min:**
  > **Bot:** [error]
- **Nothing playing:**
  > **Bot:** Nothing is playing right now.

### Trim Silence

Confirmation: implicit (earcon)

> **User:** Turn on trim silence.
> **Bot:** [toggle] Medium.

> **User:** Trim silence high.
> **Bot:** [toggle] High.

> **User:** Turn off trim silence.
> **Bot:** [toggle] Off.

#### Edge cases
- **Already at that level:**
  > **Bot:** [error]

### Volume Boost

Confirmation: implicit (earcon)

> **User:** Turn on volume boost.
> **Bot:** [toggle] On.

> **User:** Turn off volume boost.
> **Bot:** [toggle] Off.

### Apply Scope

Confirmation: implicit (earcon)

> **User:** Make this my default speed.
> **Bot:** [confirm] Applied to all podcasts.

> **User:** Only use this speed for Reply All.
> **Bot:** [confirm] Reply All only.

---

## Sleep Timer

### Set Timer

Confirmation: implicit (earcon)

> **User:** Set a sleep timer for 30 minutes.
> **Bot:** [confirm] 30 minutes.

> **User:** Stop playing in an hour.
> **Bot:** [confirm] 60 minutes.

> **User:** I'm falling asleep. 15 minutes.
> **Bot:** [confirm] 15 minutes.

#### Edge cases
- **Timer already running:**
  > **Bot:** Timer has 12 minutes left. Replace it?
  > **User:** Yes.
  > **Bot:** [confirm] 30 minutes.

### Sleep at End of Episode

Confirmation: implicit (earcon)

> **User:** Stop after this episode.
> **Bot:** [confirm] End of episode.

> **User:** Sleep when this is over.
> **Bot:** [confirm] End of episode.

### Sleep at End of Chapter

Confirmation: implicit (earcon)

> **User:** Stop after this chapter.
> **Bot:** [confirm] End of chapter.

#### Edge cases
- **No chapters:**
  > **Bot:** No chapters. Sleep at end of episode instead?

### Add Extra Time

Confirmation: implicit (earcon)

> **User:** Add 10 more minutes.
> **Bot:** [confirm] 22 minutes left.

> **User:** A little more time.
> **Bot:** [confirm] 17 minutes left.

#### Edge cases
- **No timer running:**
  > **Bot:** [error] Want to set one?

### Cancel Timer

Confirmation: implicit (earcon)

> **User:** Cancel the sleep timer.
> **Bot:** [confirm] Cancelled.

> **User:** Turn off sleep timer.
> **Bot:** [confirm] Cancelled.

#### Edge cases
- **No timer running:**
  > **Bot:** [error]

---

## Playback State Queries

### Query Effects State

> **User:** What speed am I at?
> **Bot:** 1.5x. Trim silence medium. Volume boost off.

> **User:** Is trim silence on?
> **Bot:** Yes, medium.

> **User:** Am I boosted?
> **Bot:** Volume boost is off.

### Query Sleep Timer

> **User:** How long on the sleep timer?
> **Bot:** 18 minutes left.

> **User:** When will it stop?
> **Bot:** 18 minutes on the timer.

#### Edge cases
- **No timer:**
  > **Bot:** No sleep timer is active.
