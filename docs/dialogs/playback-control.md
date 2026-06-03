# Playback Control

Voice dialog reference for all actions in the Playback Control category.

---

## Transport

### Play

> **User:** Play.
> **Bot:** Playing.

Result: Resumes playback of the current episode.

> **User:** Keep going.
> **Bot:** Resuming.

Result: Resumes playback from the paused position.

> **User:** Resume playback.
> **Bot:** Playing.

Result: Resumes playback of the current episode.

> **User:** Start playing again.
> **Bot:** Playing.

Result: Resumes playback from the paused position.

Edge cases:
- **Nothing loaded (nothing has been played recently):**
  > **User:** Play.
  > **Bot:** Nothing to play. Pick an episode first.
- **Episode is already playing:**
  > **User:** Play.
  > **Bot:** Already playing.

---

### Pause

> **User:** Pause.
> **Bot:** Paused.

Result: Pauses playback of the current episode.

> **User:** Stop.
> **Bot:** Paused.

Result: Pauses playback.

> **User:** Hold on, pause it.
> **Bot:** Paused.

Result: Pauses the current episode.

> **User:** Pause playback.
> **Bot:** Paused.

Result: Pauses playback.

Edge cases:
- **Nothing playing:**
  > **User:** Pause.
  > **Bot:** Nothing is playing right now.
- **Already paused:**
  > **User:** Pause.
  > **Bot:** Already paused.

---

### Skip forward

> **User:** Skip forward.
> **Bot:** Skipped ahead 30 seconds.

Result: Playback jumps forward by the configured skip duration (default 30 s).

> **User:** Jump ahead.
> **Bot:** Skipped 30 seconds.

Result: Playback jumps forward by the skip duration.

> **User:** Fast forward a bit.
> **Bot:** Skipped ahead 30 seconds.

Result: Playback jumps forward by the skip duration.

> **User:** Skip 2 minutes.
> **Bot:** Skipped ahead 2 minutes.

Result: Playback jumps forward by the specified amount.

Edge cases:
- **Nothing playing:**
  > **User:** Skip forward.
  > **Bot:** Nothing is playing right now.
- **Near the end of the episode (skip would exceed duration):**
  > **User:** Skip forward.
  > **Bot:** Skipped to the end of the episode.

---

### Skip backward

> **User:** Skip back.
> **Bot:** Went back 15 seconds.

Result: Playback jumps backward by the configured skip duration (default 15 s).

> **User:** Rewind.
> **Bot:** Went back 15 seconds.

Result: Playback jumps backward by the skip duration.

> **User:** Go back a bit.
> **Bot:** Went back 15 seconds.

Result: Playback jumps backward by the skip duration.

> **User:** Rewind 1 minute.
> **Bot:** Went back 1 minute.

Result: Playback jumps backward by the specified amount.

Edge cases:
- **Nothing playing:**
  > **User:** Skip back.
  > **Bot:** Nothing is playing right now.
- **Already at the beginning of the episode:**
  > **User:** Skip back.
  > **Bot:** Already at the beginning.

---

### Seek to position

> **User:** Go to 15 minutes.
> **Bot:** Jumped to 15:00.

Result: Playback seeks to the 15-minute mark.

> **User:** Skip to the 45-minute mark.
> **Bot:** Jumped to 45:00.

Result: Playback seeks to the specified position.

> **User:** Jump to 1 hour 12 minutes.
> **Bot:** Jumped to 1:12:00.

Result: Playback seeks to the specified position.

> **User:** Go to the beginning.
> **Bot:** Jumped to the start.

Result: Playback seeks to 0:00.

> **User:** Skip to the end.
> **Bot:** Jumped to the end.

Result: Playback seeks to the end of the episode.

Edge cases:
- **Nothing playing:**
  > **User:** Go to 10 minutes.
  > **Bot:** Nothing is playing right now.
- **Position exceeds episode duration:**
  > **User:** Go to 5 hours.
  > **Bot:** That's past the end of the episode. Jumped to the end instead.
- **Negative or zero position:**
  > **User:** Go to minus 5 minutes.
  > **Bot:** I can't seek to a negative position.

---

### Skip to next episode

> **User:** Next episode.
> **Bot:** Playing the next episode.

Result: Skips to the next episode in the Up Next queue.

> **User:** Skip this one, play the next.
> **Bot:** Skipped, playing the next episode.

Result: The current episode is skipped and the next one begins.

> **User:** Move on to the next one.
> **Bot:** Playing the next episode.

Result: Advances to the next episode in the queue.

> **User:** Next.
> **Bot:** Playing the next episode.

Result: Skips to the next episode.

Edge cases:
- **Nothing playing:**
  > **User:** Next episode.
  > **Bot:** Nothing is playing right now.
- **No next episode in queue:**
  > **User:** Next episode.
  > **Bot:** There's nothing queued up next.

---

### Mark as played

> **User:** Mark this as played.
> **Bot:** Marked as played.

Result: The current or specified episode is marked as played.

> **User:** I finished this one.
> **Bot:** Marked as played.

Result: The episode is marked as played.

> **User:** Mark as played.
> **Bot:** Marked as played.

Result: The episode is marked as played.

Edge cases:
- **Episode already marked as played:**
  > **User:** Mark this as played.
  > **Bot:** That's already marked as played.
- **Nothing playing and no episode specified:**
  > **User:** Mark as played.
  > **Bot:** Which episode should I mark as played?

---

### Mark as unplayed

> **User:** Mark this as unplayed.
> **Bot:** Marked as unplayed.

Result: The specified episode is marked as unplayed.

> **User:** I want to re-listen, mark it unplayed.
> **Bot:** Marked as unplayed.

Result: The episode is marked as unplayed.

> **User:** Reset the play status.
> **Bot:** Marked as unplayed.

Result: The episode play status is reset to unplayed.

Edge cases:
- **Episode already marked as unplayed:**
  > **User:** Mark as unplayed.
  > **Bot:** That's already marked as unplayed.
- **No episode specified:**
  > **User:** Mark as unplayed.
  > **Bot:** Which episode should I mark as unplayed?

---

## Speed & Effects

### Open effects panel

> **User:** Open effects.
> **Bot:** Opening effects panel.

Result: The playback effects panel is displayed.

> **User:** Show me the effects settings.
> **Bot:** Opening effects.

Result: The effects panel opens.

> **User:** Playback settings.
> **Bot:** Opening effects panel.

Result: The effects panel is displayed.

Edge cases:
- **Nothing playing:**
  > **User:** Open effects.
  > **Bot:** Nothing is playing. Effects apply during playback.

---

### Set playback speed

> **User:** Set speed to 1.5x.
> **Bot:** Speed set to 1.5x.

Result: Playback speed changes to 1.5x.

> **User:** Speed up.
> **Bot:** Speed set to 1.5x.

Result: Playback speed increases by one step.

> **User:** Slow down.
> **Bot:** Speed set to 0.8x.

Result: Playback speed decreases by one step.

> **User:** Play at double speed.
> **Bot:** Speed set to 2x.

Result: Playback speed changes to 2x.

> **User:** Set it to normal speed.
> **Bot:** Speed set to 1x.

Result: Playback speed resets to 1x.

Edge cases:
- **Value out of range (below 0.5x):**
  > **User:** Set speed to 0.1x.
  > **Bot:** The slowest speed is 0.5x. Set it to 0.5x.
- **Value out of range (above 5.0x):**
  > **User:** Speed it up to 10x.
  > **Bot:** The fastest speed is 5x. Set it to 5x.
- **Already at minimum speed and asking to slow down:**
  > **User:** Slow down.
  > **Bot:** Already at the slowest speed, 0.5x.
- **Already at maximum speed and asking to speed up:**
  > **User:** Speed up.
  > **Bot:** Already at the fastest speed, 5x.

---

### Trim silence

> **User:** Turn on trim silence.
> **Bot:** Trim silence set to medium.

Result: Trim silence is enabled at the default level (medium).

> **User:** Set trim silence to high.
> **Bot:** Trim silence set to high.

Result: Trim silence mode is set to high.

> **User:** Turn off trim silence.
> **Bot:** Trim silence off.

Result: Trim silence is disabled.

> **User:** Maximize trim silence.
> **Bot:** Trim silence set to high.

Result: Trim silence is set to the highest level.

Edge cases:
- **Trim silence unavailable for current content:**
  > **User:** Turn on trim silence.
  > **Bot:** Trim silence isn't available for this content.
- **Already at the requested level:**
  > **User:** Set trim silence to medium.
  > **Bot:** Already set to medium.

---

### Volume boost

> **User:** Turn on volume boost.
> **Bot:** Volume boost on.

Result: Volume boost is enabled.

> **User:** Boost the volume.
> **Bot:** Volume boost on.

Result: Volume boost is enabled.

> **User:** Turn off volume boost.
> **Bot:** Volume boost off.

Result: Volume boost is disabled.

> **User:** Make it louder.
> **Bot:** Volume boost on.

Result: Volume boost is enabled.

Edge cases:
- **Already on and asked to turn on:**
  > **User:** Turn on volume boost.
  > **Bot:** Volume boost is already on.
- **Already off and asked to turn off:**
  > **User:** Turn off volume boost.
  > **Bot:** Volume boost is already off.

---

### Apply to all podcasts or one

> **User:** Apply this speed to all podcasts.
> **Bot:** Speed set to 1.5x for all podcasts.

Result: The current speed setting is applied globally across all podcasts.

> **User:** Only use this speed for this podcast.
> **Bot:** Speed set to 1.5x for this podcast only.

Result: The speed setting applies only to the current podcast.

> **User:** Make these effects global.
> **Bot:** Effects applied to all podcasts.

Result: All current effect settings are applied globally.

> **User:** Use these settings only for this podcast.
> **Bot:** Effects applied to this podcast only.

Result: Effect settings are scoped to the current podcast.

Edge cases:
- **Nothing playing and asked to set per-podcast:**
  > **User:** Only use this speed for this podcast.
  > **Bot:** Nothing is playing. Start an episode to set per-podcast effects.

---

### Save effects

> **User:** Save these effects.
> **Bot:** Effects saved.

Result: Current effect settings are persisted.

> **User:** Save my playback settings.
> **Bot:** Saved.

Result: Effect settings are saved.

> **User:** Keep these settings.
> **Bot:** Effects saved.

Result: Current effects are persisted.

Edge cases:
- **No changes to save:**
  > **User:** Save effects.
  > **Bot:** No changes to save.

---

## Sleep Timer

### Set timer (minutes)

> **User:** Set a sleep timer for 30 minutes.
> **Bot:** Sleep timer set for 30 minutes.

Result: A sleep timer starts counting down from 30 minutes. Playback pauses when it expires.

> **User:** Stop playing in 15 minutes.
> **Bot:** Sleep timer set for 15 minutes.

Result: A 15-minute sleep timer is started.

> **User:** Sleep timer, 60 minutes.
> **Bot:** Timer set for 60 minutes.

Result: A 60-minute sleep timer starts.

> **User:** I'm going to sleep, set a timer for 45 minutes.
> **Bot:** Sleep timer set for 45 minutes.

Result: A 45-minute sleep timer is started.

> **User:** Auto-stop in 10 minutes.
> **Bot:** Sleep timer set for 10 minutes.

Result: A 10-minute timer begins.

Edge cases:
- **Timer already running:**
  > **User:** Set a sleep timer for 30 minutes.
  > **Bot:** You already have a timer running with 12 minutes left. Want me to reset it to 30 minutes?
    > **User:** Yes.
    > **Bot:** Timer reset to 30 minutes.
- **Invalid duration:**
  > **User:** Set a sleep timer for 3 minutes.
  > **Bot:** The shortest timer is 5 minutes. Set it to 5 minutes?
    > **User:** Sure.
    > **Bot:** Sleep timer set for 5 minutes.

---

### Sleep at end of episode

> **User:** Stop at the end of this episode.
> **Bot:** Will sleep at the end of this episode.

Result: Playback pauses automatically when the current episode finishes.

> **User:** Sleep after this episode.
> **Bot:** Sleep at end of episode.

Result: A sleep trigger is set for the end of the current episode.

> **User:** Stop playing when this episode is done.
> **Bot:** Will sleep at the end of this episode.

Result: Playback will pause when the episode completes.

Edge cases:
- **Nothing playing:**
  > **User:** Stop at the end of this episode.
  > **Bot:** Nothing is playing right now.

---

### Sleep at end of chapter

> **User:** Sleep at the end of this chapter.
> **Bot:** Will sleep at the end of this chapter.

Result: Playback pauses when the current chapter finishes.

> **User:** Stop after this chapter.
> **Bot:** Sleep at end of chapter.

Result: A sleep trigger is set for the end of the current chapter.

> **User:** Pause when the chapter ends.
> **Bot:** Will sleep at the end of this chapter.

Result: Playback pauses at the end of the current chapter.

Edge cases:
- **Episode has no chapters:**
  > **User:** Sleep at the end of this chapter.
  > **Bot:** This episode doesn't have chapters. Want me to sleep at the end of the episode instead?
    > **User:** Yes.
    > **Bot:** Will sleep at the end of this episode.

---

### Add extra time

> **User:** Add 5 more minutes.
> **Bot:** Added 5 minutes. Timer is now at 17 minutes.

Result: The sleep timer is extended by the specified amount.

> **User:** Extend the sleep timer.
> **Bot:** Added 5 minutes. Timer is now at 22 minutes.

Result: The sleep timer is extended by the default increment (5 minutes).

> **User:** Give me 10 more minutes.
> **Bot:** Added 10 minutes. Timer is now at 30 minutes.

Result: The sleep timer is extended by 10 minutes.

Edge cases:
- **No timer running:**
  > **User:** Add 5 more minutes.
  > **Bot:** There's no sleep timer running. Want me to set one?
- **Timer already expired:**
  > **User:** Add more time.
  > **Bot:** The timer has already ended. Want me to start a new one?

---

### Cancel timer

> **User:** Cancel the sleep timer.
> **Bot:** Sleep timer cancelled.

Result: The active sleep timer is removed. Playback continues normally.

> **User:** Turn off the sleep timer.
> **Bot:** Timer cancelled.

Result: The sleep timer is cancelled.

> **User:** Never mind, cancel the timer.
> **Bot:** Sleep timer cancelled.

Result: The active sleep timer is removed.

> **User:** Stop the sleep timer.
> **Bot:** Timer cancelled.

Result: The sleep timer is cancelled.

Edge cases:
- **No timer running:**
  > **User:** Cancel the sleep timer.
  > **Bot:** There's no sleep timer running.

---

## Chapters

### Open chapters list

> **User:** Show chapters.
> **Bot:** Opening chapters.

Result: The chapters list for the current episode is displayed.

> **User:** What chapters are in this episode?
> **Bot:** Opening chapters list.

Result: The chapters list is shown.

> **User:** Open the chapter list.
> **Bot:** Opening chapters.

Result: The chapters list is displayed.

Edge cases:
- **Episode has no chapters:**
  > **User:** Show chapters.
  > **Bot:** This episode doesn't have chapters.
- **Nothing playing:**
  > **User:** Show chapters.
  > **Bot:** Nothing is playing right now.

---

### Play a chapter

> **User:** Play chapter 3.
> **Bot:** Playing chapter 3, "The Big Reveal."

Result: Playback seeks to the beginning of the specified chapter and resumes.

> **User:** Jump to the chapter called "Interview."
> **Bot:** Playing "Interview."

Result: Playback jumps to the matching chapter.

> **User:** Skip to the next chapter.
> **Bot:** Playing next chapter, "Closing Thoughts."

Result: Playback advances to the next chapter.

> **User:** Go back to the previous chapter.
> **Bot:** Playing previous chapter, "Introduction."

Result: Playback returns to the previous chapter.

Edge cases:
- **Chapter number out of range:**
  > **User:** Play chapter 12.
  > **Bot:** This episode only has 6 chapters.
- **Chapter name not found:**
  > **User:** Play the chapter called "Conclusion."
  > **Bot:** I couldn't find a chapter called "Conclusion." The chapters are: "Introduction," "The Setup," "The Big Reveal," "Closing Thoughts."
- **Episode has no chapters:**
  > **User:** Play chapter 2.
  > **Bot:** This episode doesn't have chapters.
- **Already at the last chapter and asked for next:**
  > **User:** Next chapter.
  > **Bot:** This is the last chapter.

---

### Tap chapter link

> **User:** Open the link in chapter 2.
> **Bot:** Opening link from "The Setup."

Result: The URL linked in the specified chapter is opened in the browser.

> **User:** Open the chapter link.
> **Bot:** Opening link from "Resources."

Result: The linked URL in the current chapter is opened.

Edge cases:
- **Chapter has no link:**
  > **User:** Open the link in this chapter.
  > **Bot:** This chapter doesn't have a link.
- **Chapter not found:**
  > **User:** Open the link in chapter 8.
  > **Bot:** This episode only has 6 chapters.

---

## Video

### Enter full screen

> **User:** Full screen.
> **Bot:** Going full screen.

Result: The video player enters full-screen mode.

> **User:** Make the video full screen.
> **Bot:** Full screen.

Result: The video expands to fill the screen.

> **User:** Go full screen.
> **Bot:** Going full screen.

Result: The player enters full-screen mode.

Edge cases:
- **Audio-only content (no video):**
  > **User:** Full screen.
  > **Bot:** This is audio only, there's no video to show.
- **Already in full screen:**
  > **User:** Full screen.
  > **Bot:** Already in full screen.
- **Nothing playing:**
  > **User:** Full screen.
  > **Bot:** Nothing is playing right now.

---

### Enter PiP

> **User:** Picture in picture.
> **Bot:** Entering picture in picture.

Result: The video switches to picture-in-picture mode.

> **User:** Turn on PiP.
> **Bot:** Picture in picture.

Result: The video enters PiP mode.

> **User:** Minimize the video.
> **Bot:** Entering PiP.

Result: The video shrinks to a floating PiP window.

Edge cases:
- **Audio-only content (no video):**
  > **User:** Turn on PiP.
  > **Bot:** This is audio only, there's no video for picture in picture.
- **Already in PiP:**
  > **User:** Picture in picture.
  > **Bot:** Already in picture in picture.
- **Nothing playing:**
  > **User:** PiP.
  > **Bot:** Nothing is playing right now.
