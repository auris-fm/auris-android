# Queue

Queue management is a core voice use case. Users build and modify their listening queue hands-free by naming episodes and podcasts.

---

## Queue Management

### Add to Top

Confirmation: implicit

> **User:** Play the latest Reply All next.
> **Bot:** [confirm] Added *The Secret, Gruesome Internet*.
Result: Episode added after current, before rest of queue.

> **User:** Put that next in my queue.
> **Bot:** [confirm] Added.
Result: Episode added to top of queue.

> **User:** Queue up the Conan episode next.
> **Bot:** [confirm] Added *Neil deGrasse Tyson*.
Result: Episode added to top of queue.

#### Edge cases
- **Episode already in queue:**
  > **Bot:** That's already in your queue at position 3. Want to move it to the top?
  > **User:** Yes.
  > **Bot:** [confirm] Moved.
- **Ambiguous episode name:**
  > **Bot:** I found 3 Conan episodes. Which one — *Neil deGrasse Tyson*, *Will Ferrell*, or *Bill Burr*?

### Add to Bottom

Confirmation: implicit

> **User:** Play the Obama interview last.
> **Bot:** [confirm] Added *The Obama Interview*.
Result: Episode appended to queue.

> **User:** Add that to the end of my queue.
> **Bot:** [confirm] Added.
Result: Episode appended.

> **User:** Queue up Lex Fridman for later.
> **Bot:** [confirm] Added *Lex Fridman #412*.
Result: Episode appended.

### Remove from Queue

Confirmation: implicit

> **User:** Remove the Vergecast episode from my queue.
> **Bot:** [confirm] Removed *The Vergecast*.
Result: Episode removed.

> **User:** Take that out of the queue.
> **Bot:** [confirm] Removed.
Result: Episode removed.

#### Edge cases
- **Episode not in queue:**
  > **Bot:** That's not in your queue. Want me to find it?
- **Ambiguous name:**
  > **Bot:** I found 2 Reply All episodes in your queue. Remove both, or just one?

### Move to Top

Confirmation: implicit

> **User:** Move the Conan episode to the top.
> **Bot:** [confirm] Moved *Neil deGrasse Tyson*.
Result: Episode moved to top of queue.

> **User:** Play that sooner.
> **Bot:** [confirm] Moved.
Result: Episode moved to top.

#### Edge cases
- **Already at top:**
  > **Bot:** [error]

### Move to Bottom

Confirmation: implicit

> **User:** Move the Vergecast to the bottom.
> **Bot:** [confirm] Moved *The Vergecast*.
Result: Episode moved to bottom.

> **User:** Push that to the end.
> **Bot:** [confirm] Moved.
Result: Episode moved to bottom.

### Clear Queue

Confirmation: explicit

> **User:** Clear my queue.
> **Bot:** Clear all 8 episodes from your queue?
> **User:** Yes.
> **Bot:** Queue cleared.
Result: All queue episodes removed (current episode keeps playing).

> **User:** Empty the queue.
> **Bot:** Clear all 8 episodes?
> **User:** Yes.
> **Bot:** Queue cleared.

#### Edge cases
- **Queue already empty:**
  > **Bot:** [error]

### Remove by Podcast

Confirmation: implicit

> **User:** Remove all Vergecast episodes from my queue.
> **Bot:** [confirm] Removed 3 episodes.
Result: All episodes from that podcast removed.

> **User:** Clear out Reply All from the queue.
> **Bot:** [confirm] Removed 2 episodes.
Result: All episodes from that podcast removed.

#### Edge cases
- **None of that podcast in queue:**
  > **Bot:** [error]

### Sort Queue

Confirmation: implicit

> **User:** Sort my queue by newest first.
> **Bot:** [confirm] Sorted.
Result: Queue reordered.

> **User:** Sort queue oldest to newest.
> **Bot:** [confirm] Sorted.
Result: Queue reordered.

---

## Queue Queries

### Query Queue Contents

Confirmation: implicit (read-only)

> **User:** What's coming up?
> **Bot:** Up next: *Neil deGrasse Tyson* from Conan, then *The Secret, Gruesome Internet* from Reply All, then *The Vergecast*. 8 episodes total.

> **User:** What's in my queue?
> **Bot:** 8 episodes. Next: *Neil deGrasse Tyson*. Then *The Secret, Gruesome Internet*, *The Vergecast*, and 5 more.

#### Edge cases
- **Queue empty:**
  > **Bot:** Your queue is empty. Want me to add something?

### Query Next Episode

Confirmation: implicit (read-only)

> **User:** What's playing next?
> **Bot:** Next up is *The Secret, Gruesome Internet* from Reply All.

#### Edge cases
- **Queue empty:**
  > **Bot:** Nothing is queued up next.

### Query Queue Length

Confirmation: implicit (read-only)

> **User:** How many in my queue?
> **Bot:** 8 episodes in your queue.

### Query if Episode is Queued

Confirmation: implicit (read-only)

> **User:** Is the Obama interview in my queue?
> **Bot:** Yes, it's 4th in line.

> **User:** Is the Conan episode queued?
> **Bot:** No. Want me to add it?
  > **User:** Yes, play it next.
  > **Bot:** [confirm] Added *Neil deGrasse Tyson*.
