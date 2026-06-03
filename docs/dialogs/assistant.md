# Assistant

The AI episode assistant is voice-first by design. Users ask questions about the current episode, get summaries, and jump to topics — all without navigating to a chat screen. This file also covers casting, referrals, and End of Year features.

---

## AI Episode Q&A

The primary voice pattern is direct Q&A — no "open chat" step needed. Users just ask.

### Ask About Episode

Confirmation: implicit

> **User:** What did they say about climate change?
> **Bot:** Around 12:30, the host argues that climate policy has stalled because of lobbying. The guest disagrees, pointing to recent legislation as progress.

> **User:** What's this episode about?
> **Bot:** This episode explores how tech companies influence climate policy. The host interviews a former lobbyist and a climate scientist.

> **User:** Summarize the main arguments.
> **Bot:** Three key points: first, tech companies publicly support climate action but quietly fund opposition. Second, recent legislation is a start but falls short. Third, the guest argues consumer pressure is the most effective lever.

> **User:** What's the TLDR?
> **Bot:** Big tech talks green but funds the opposite. Policy is lagging. Consumer pressure works.

#### Edge cases
- **Not a Plus subscriber:**
  > **Bot:** Episode chat is a Plus feature. Want to start a free trial?
- **Chat unavailable for episode:**
  > **Bot:** [error]
- **AI timeout:**
  > **Bot:** Taking a moment to think. Try again in a second.

### Summarize Episode

Confirmation: implicit

> **User:** Summarize this episode.
> **Bot:** The host investigates tech companies' climate lobbying with a former insider and a scientist. Key takeaway: follow the money, not the press releases.

> **User:** Give me the key points.
> **Bot:** Three key points: lobbying doublespeak, recent policy wins, and why consumer action matters more than voting.

> **User:** Quick summary.
> **Bot:** Tech says green, funds brown. Policy is slow. Consumers have power.

### Query Episode Content

Confirmation: implicit

> **User:** Does this mention artificial intelligence?
> **Bot:** Yes, AI comes up around 18:45. The guest argues AI can help optimize energy grids but won't solve the core political problem.

> **User:** Who's the guest on this episode?
> **Bot:** The guest is Dr. Sarah Chen, a climate policy researcher at MIT. She's written two books on corporate influence.

> **User:** What's the main topic?
> **Bot:** How tech companies influence climate policy through lobbying and PR.

> **User:** Does the host agree with the guest?
> **Bot:** Mostly yes — both agree corporate lobbying is the biggest obstacle. But they disagree on whether recent legislation is meaningful. The host is more skeptical.

### Jump to Topic

Confirmation: implicit

> **User:** Skip to the part about AI.
Result: Seeks to 18:45.

> **User:** Find the discussion about lobbying.
Result: Seeks to 8:20 where they get into the lobbying data.

> **User:** When do they talk about solutions?
Result: Seeks to 35:10 where solutions come up.

#### Edge cases
- **Topic not found:**
  > **Bot:** Couldn't find that topic. The closest match is "technology" at 18:45. Jump there?

### Play Quote

Confirmation: implicit

> **User:** Play that part again.
Result: Seeks to last referenced timestamp (12:30) and plays.

> **User:** Play the quote about lobbying.
Result: Seeks to matching quote at 8:20 and plays.

### Stop Quote

Confirmation: implicit

> **User:** Go back to where I was.
Result: Restores previous playback position (14:22).

> **User:** Stop the quote.
Result: Restores previous playback position (14:22).

### Retry Failed Message

Confirmation: implicit

> **User:** Try again.
> **Bot:** [confirm]

> **User:** Retry that.
> **Bot:** [confirm]

#### Edge cases
- **Network error:**
  > **Bot:** Still can't connect. Check your internet and try again.

### Clear Chat

Confirmation: explicit

> **User:** Clear the conversation.
> **Bot:** Clear all messages?
> **User:** Yes.
> **Bot:** Chat cleared.

> **User:** Start over.
> **Bot:** Clear all messages?
> **User:** Yes.
> **Bot:** Chat cleared.

---

## Chromecast / Casting

### Cast to Device

Confirmation: implicit

> **User:** Cast to Living Room.
> **Bot:** Connected to Living Room.

> **User:** Play on my TV.
> **Bot:** Found 2 devices: Living Room and Bedroom. Which one?
  > **User:** Living Room.
  > **Bot:** Connected to Living Room.

> **User:** Connect to Chromecast.
> **Bot:** Found 2 devices: Living Room and Bedroom. Which one?

#### Edge cases
- **No cast devices found:**
  > **Bot:** No cast devices on your network. Make sure your device is on the same WiFi.
- **Connection failure:**
  > **Bot:** Couldn't connect to Living Room. Try again?
- **Already casting to that device:**
  > **Bot:** [error]

### Stop Casting

Confirmation: implicit

> **User:** Stop casting.
> **Bot:** [confirm] Disconnected.

> **User:** Disconnect.
> **Bot:** [confirm] Disconnected.

> **User:** Play on phone instead.
> **Bot:** [confirm] Disconnected from Living Room. Playing on phone.

### Query Cast Device

Confirmation: implicit (read-only)

> **User:** What device am I casting to?
> **Bot:** Connected to Living Room.

> **User:** Am I casting?
> **Bot:** Playing on your phone. Not casting.

---

## Referrals

### Send Guest Pass

Confirmation: implicit

> **User:** Share my referral link.
> **Bot:** [confirm]

> **User:** Send a guest pass.
> **Bot:** [confirm]

### Claim Guest Pass

Confirmation: implicit

> **User:** Claim this guest pass.
> **Bot:** [confirm]

> **User:** Activate my referral.
> **Bot:** [confirm]

#### Edge cases
- **Already claimed:**
  > **Bot:** [error]
- **No referral link available:**
  > **Bot:** You need a Plus subscription to send guest passes.

---

## End of Year

### View Stories

Confirmation: implicit

> **User:** Show me my year in review.
> **Bot:** [confirm]

> **User:** Play my listening story.
> **Bot:** [confirm]

#### Edge cases
- **Not available (wrong time of year):**
  > **Bot:** Your year in review isn't ready yet. Check back in December!
- **Not enough listening history:**
  > **Bot:** You need a bit more listening history for your year in review. Keep listening!

### Next / Previous Story

Confirmation: implicit

> **User:** Next.
Result: Story advances.

> **User:** Previous.
Result: Story goes back.

> **User:** Go back.
Result: Story goes back.

### Share Story

Confirmation: implicit

> **User:** Share this one.
> **Bot:** [confirm]

> **User:** Share my story.
> **Bot:** [confirm]

### Replay Stories

Confirmation: implicit

> **User:** Replay.
> **Bot:** [confirm]

> **User:** Start over.
> **Bot:** [confirm]
