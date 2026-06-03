# Search & Discover

Voice search is conversational — users ask, the bot finds and summarizes results, then the user interacts by name or number. No "opening a search screen." Discover is redesigned as voice queries for recommendations and trending content.

---

## Search

Search is always available — no "open search" step. The bot searches, summarizes results, and the user interacts conversationationally.

### Search

Confirmation: implicit

> **User:** Search for true crime podcasts.
> **Bot:** Found 8 results. Top match: *Serial* from Serial Productions. Want to play, subscribe, or tell you more?

> **User:** Find episodes about artificial intelligence.
> **Bot:** Found 12 results. Top match: *The AI Revolution* from Lex Fridman Podcast. Play, subscribe, or more details?

> **User:** Look up Serial.
> **Bot:** Found 2 results: *Serial* from Serial Productions, and *S-Town* from Serial Productions. Which one?

#### Edge cases
- **No results:**
  > **Bot:** Nothing found for "quantum fish farming". Try different words?
- **Network error:**
  > **Bot:** Search didn't work — check your connection and try again.

### Filter Results

Confirmation: implicit

> **User:** Show only podcasts.
> **Bot:** 5 podcast results: *Serial*, *S-Town*, *Criminal*, *In the Dark*, *Up and Vanished*.

> **User:** Episodes only.
> **Bot:** 3 episode results: *The Alibi* from Serial, *Episode 1* from S-Town, *Episode 47* from Criminal.

> **User:** Just podcasts please.
> **Bot:** 5 podcast results: *Serial*, *S-Town*, *Criminal*, *In the Dark*, *Up and Vanished*.

### Subscribe from Results

Confirmation: implicit

> **User:** Subscribe to that one.
> **Bot:** [confirm] Subscribed to *Serial*.

> **User:** Follow the first result.
> **Bot:** [confirm] Subscribed to *Serial*.

### Play from Results

Confirmation: implicit

> **User:** Play the second one.
Result: Plays *Episode 1* from S-Town.

> **User:** Play Serial.
Result: Plays *The Alibi* from Serial.

### Describe Result

Confirmation: implicit

> **User:** Tell me about the first one.
> **Bot:** *Serial* from Sarah Koenig and Serial Productions. A podcast investigating a real-life murder case, one episode at a time. 2 seasons. 52 episodes total.

> **User:** What is that about?
> **Bot:** *Serial* — investigative journalism from Sarah Koenig, following one story over an entire season.

### Search History Re-Run

Confirmation: implicit

> **User:** Search for Reply All again.
> **Bot:** Searching for "Reply All"... Found *Reply All* from Gimlet. Play or subscribe?

> **User:** Run my last search.
> **Bot:** Searching for "true crime"... Found 8 results. Top match: *Serial*.

#### Edge cases
- **No search history:**
  > **Bot:** [error]

### Clear Search History

Confirmation: explicit

> **User:** Clear my search history.
> **Bot:** Clear all search history?
> **User:** Yes.
> **Bot:** Cleared.

#### Edge cases
- **Empty history:**
  > **Bot:** [error]

---

## Discover

Discover is redesigned as conversational recommendations instead of visual browsing.

### Trending

Confirmation: implicit

> **User:** What's trending?
> **Bot:** Top 5 trending: *The Retrievals* (society), *Wiser Than Me* (comedy), *Scamanda* (true crime), *The Witch Trials* (culture), *Stolen Youth* (documentary). Want to subscribe to any?

> **User:** What's popular right now?
> **Bot:** Top 5: *The Retrievals*, *Wiser Than Me*, *Scamanda*, *The Witch Trials*, *Stolen Youth*. Hear more about any of these?

### Recommendations

Confirmation: implicit

> **User:** Recommend a podcast.
> **Bot:** Based on your library, try *Radiolab*. It's about science and philosophy, similar to Reply All. Want to subscribe?

> **User:** What should I listen to?
> **Bot:** You might like *Criminal* — true crime stories told with nuance. You've been listening to a lot of Reply All and this has a similar narrative style. Subscribe?

> **User:** Suggest something new.
> **Bot:** How about *Ear Hustle*? Stories from inside prison, produced with inmates. 4.8 stars. Want to try it?

### Category Browse

Confirmation: implicit

> **User:** Show me comedy podcasts.
> **Bot:** Top comedy: *Conan O'Brien Needs a Friend*, *SmartLess*, *Armchair Expert*, *WTF with Marc Maron*. Want details on any?

> **User:** What's good in tech?
> **Bot:** Top tech: *The Vergecast*, *Hard Fork*, *All-In*, *Lex Fridman Podcast*. Subscribe to any?

> **User:** Any good true crime?
> **Bot:** Top true crime: *Serial*, *Criminal*, *In the Dark*, *Scamanda*. Want to hear about one?

### New Releases

Confirmation: implicit

> **User:** What's new this week?
> **Bot:** 5 new episodes from your podcasts this week. Latest: *The Inside Job* from Reply All, released yesterday.

> **User:** Any new episodes?
> **Bot:** 5 new episodes from your shows. Latest: *The Inside Job* from Reply All, 2 hours ago.

#### Edge cases
- **Nothing new:**
  > **Bot:** [error]

### Change Region

Confirmation: implicit

> **User:** Show me UK charts.
> **Bot:** [confirm] Showing UK charts.

> **User:** Switch to US.
> **Bot:** [confirm] Showing US charts.

#### Edge cases
- **Region not available:**
  > **Bot:** That region isn't available. Want to see what's available?
