# Up Next Queue

## Add episode to top of queue

> **User:** Play the latest Lex Fridman episode next.
> **Bot:** Added "Lex Fridman #420" to play next.
>
> Result: Episode is inserted at the top of the Up Next queue, directly after the currently playing episode.

> **User:** Queue this one up next.
> **Bot:** Queued next.
>
> Result: Episode is inserted at the top of the Up Next queue.

> **User:** I want to hear that episode right after this one.
> **Bot:** Got it, it'll play next.
>
> Result: Episode is inserted at the top of the Up Next queue.

> **User:** Put the new Vergecast at the top of my queue.
> **Bot:** Moved "The Vergecast" to the top.
>
> Result: Episode is inserted at the top of the Up Next queue.

### Edge cases

- **Episode already in queue:** Bot says "That episode is already in your queue." and does not add a duplicate.
- **Episode is currently playing:** Bot says "That's the episode that's playing right now." and takes no action.
- **No episode specified:** Bot says "Which episode should I queue next?" and waits for clarification.
- **Nothing is playing:** Episode is added to the top of the queue and begins playing immediately.

---

## Add episode to bottom of queue

> **User:** Add that episode to the end of my queue.
> **Bot:** Added to the bottom of your queue.
>
> Result: Episode is appended to the end of the Up Next queue.

> **User:** Play that last.
> **Bot:** Will do, added to the end.
>
> Result: Episode is appended to the end of the Up Next queue.

> **User:** Save this one for later, put it at the bottom.
> **Bot:** Added to the end of your queue.
>
> Result: Episode is appended to the end of the Up Next queue.

> **User:** Queue the new Conan O'Brien episode for the end.
> **Bot:** "Conan O'Brien Needs a Friend" added to the bottom.
>
> Result: Episode is appended to the end of the Up Next queue.

### Edge cases

- **Episode already in queue:** Bot says "That episode is already in your queue." and does not add a duplicate.
- **No episode specified:** Bot says "Which episode should I add?" and waits for clarification.
- **Queue is empty (nothing playing):** Episode is added and begins playing immediately.
- **Episode is currently playing:** Bot says "That's already playing." and takes no action.

---

## Remove from queue

> **User:** Remove that from my queue.
> **Bot:** Removed.
>
> Result: Episode is removed from the Up Next queue. The queue shifts up to fill the gap.

> **User:** Take the third one out of Up Next.
> **Bot:** Removed from your queue.
>
> Result: The third episode in the queue is removed.

> **User:** I don't want to listen to that one anymore, remove it.
> **Bot:** Done, removed from your queue.
>
> Result: The referenced episode is removed from the queue.

> **User:** Drop the Vergecast from my queue.
> **Bot:** Removed "The Vergecast" from your queue.
>
> Result: The specified episode is removed from the queue.

### Edge cases

- **Episode not in queue:** Bot says "That episode isn't in your queue." and takes no action.
- **No episode specified:** Bot says "Which episode should I remove?" and waits for clarification.
- **Queue is empty:** Bot says "Your queue is empty." and takes no action.
- **Episode is currently playing:** Bot says "I can't remove the episode that's currently playing. Want to skip to the next one?" and offers to skip instead.

---

## Reorder episode

> **User:** Move that episode up two spots.
> **Bot:** Moved up two spots.
>
> Result: Episode is moved two positions higher in the Up Next queue.

> **User:** Move the third episode to first.
> **Bot:** Done, moved to the top.
>
> Result: Episode at position 3 is moved to position 1 in the queue.

> **User:** Put Conan after the Vergecast.
> **Bot:** Reordered, Conan is now after The Vergecast.
>
> Result: The Conan episode is placed directly after the Vergecast in the queue.

> **User:** Swap the second and fourth episodes.
> **Bot:** Swapped.
>
> Result: The episodes at positions 2 and 4 exchange places in the queue.

### Edge cases

- **Queue has only one episode:** Bot says "There's only one episode in your queue." and takes no action.
- **Episode not in queue:** Bot says "That episode isn't in your queue." and takes no action.
- **Target position is out of range:** Bot says "That position is outside the queue. I've moved it as far as I can." and places the episode at the nearest valid position.
- **Queue is empty:** Bot says "Your queue is empty." and takes no action.
- **No episode specified:** Bot says "Which episode should I move?" and waits for clarification.

---

## Move to top

> **User:** Move this to the top of my queue.
> **Bot:** Moved to the top.
>
> Result: Episode is moved to position 1 in the Up Next queue.

> **User:** I want to hear that one next, move it up.
> **Bot:** Done, it'll play next.
>
> Result: Episode is moved to the top of the queue.

> **User:** Put the Joe Rogan episode first in line.
> **Bot:** Moved "Joe Rogan" to the top.
>
> Result: The specified episode is moved to position 1.

> **User:** Prioritize the third episode.
> **Bot:** Moved to the top of your queue.
>
> Result: Episode at position 3 is moved to position 1.

### Edge cases

- **Episode is already at the top:** Bot says "That's already at the top of your queue." and takes no action.
- **Episode not in queue:** Bot says "That episode isn't in your queue. Want me to add it?" and offers to add it instead.
- **Queue is empty:** Bot says "Your queue is empty." and takes no action.
- **No episode specified:** Bot says "Which episode should I move to the top?" and waits for clarification.

---

## Move to bottom

> **User:** Move this to the bottom of my queue.
> **Bot:** Moved to the bottom.
>
> Result: Episode is moved to the last position in the Up Next queue.

> **User:** Push that one to the end.
> **Bot:** Moved to the end of your queue.
>
> Result: Episode is moved to the last position in the queue.

> **User:** Save Conan for last.
> **Bot:** Done, moved to the bottom.
>
> Result: The Conan episode is moved to the last position.

> **User:** I want to hear that one last.
> **Bot:** Moved it to the end of your queue.
>
> Result: Episode is moved to the last position in the queue.

### Edge cases

- **Episode is already at the bottom:** Bot says "That's already at the bottom of your queue." and takes no action.
- **Episode not in queue:** Bot says "That episode isn't in your queue. Want me to add it?" and offers to add it instead.
- **Queue is empty:** Bot says "Your queue is empty." and takes no action.
- **Queue has only one episode:** Bot says "That's the only episode in your queue." and takes no action.
- **No episode specified:** Bot says "Which episode should I move to the bottom?" and waits for clarification.

---

## Clear queue

> **User:** Clear my queue.
> **Bot:** Queue cleared.
>
> Result: All episodes are removed from the Up Next queue. The currently playing episode continues playing.

> **User:** Empty my Up Next.
> **Bot:** Done, your queue is now empty.
>
> Result: All episodes are removed from the Up Next queue.

> **User:** Start fresh, clear everything from my queue.
> **Bot:** Cleared.
>
> Result: All episodes are removed from the Up Next queue.

> **User:** Get rid of everything in Up Next.
> **Bot:** Queue cleared.
>
> Result: All episodes are removed from the Up Next queue.

### Edge cases

- **Queue is already empty:** Bot says "Your queue is already empty." and takes no action.
- **Nothing is playing and queue is cleared:** Bot says "Queue cleared." The now-empty state is reflected in the UI.
- **Currently playing episode:** The playing episode is not affected; only queued episodes are cleared.

---

## Sort queue

> **User:** Sort my queue by newest first.
> **Bot:** Sorted by newest first.
>
> Result: Episodes in the Up Next queue are reordered with the most recently published at the top.

> **User:** Sort Up Next oldest to newest.
> **Bot:** Done, sorted oldest first.
>
> Result: Episodes are reordered with the oldest at the top.

> **User:** Organize my queue by podcast.
> **Bot:** Sorted, episodes grouped by podcast.
>
> Result: Episodes are reordered, grouped by podcast name.

> **User:** Sort my queue by shortest duration.
> **Bot:** Sorted by shortest first.
>
> Result: Episodes are reordered with the shortest duration at the top.

### Edge cases

- **Queue is empty:** Bot says "Your queue is empty, nothing to sort." and takes no action.
- **Queue has only one episode:** Bot says "There's only one episode in your queue." and takes no action.
- **Unrecognized sort order:** Bot says "I can sort by newest, oldest, duration, or podcast. Which would you like?" and waits for clarification.

---

## Multi-select in Up Next

> **User:** Select all episodes in my queue.
> **Bot:** All 5 episodes selected.
>
> Result: All episodes in the Up Next queue are selected, and the multi-select action bar appears.

> **User:** Select the first three episodes.
> **Bot:** 3 episodes selected.
>
> Result: The first three episodes are selected and the multi-select action bar appears.

> **User:** Select the Joe Rogan and Conan episodes.
> **Bot:** 2 episodes selected.
>
> Result: The two named episodes are selected in multi-select mode.

> **User:** Select everything from Vergecast in my queue.
> **Bot:** 3 Vergecast episodes selected.
>
> Result: All Vergecast episodes in the queue are selected.

### Edge cases

- **Queue is empty:** Bot says "Your queue is empty." and takes no action.
- **Episode name not found in queue:** Bot says "I couldn't find that episode in your queue." and waits for clarification.
- **No matching episodes for a podcast filter:** Bot says "There are no episodes from that podcast in your queue." and takes no action.
- **Number requested exceeds queue size:** Bot says "There are only X episodes in your queue. Selected all of them." and selects all available episodes.
