# Podcasts

Podcasts are the central entity. Users manage subscriptions, act on episodes, organize with folders, and query their library — all by naming podcasts and episodes naturally.

---

## Subscriptions

### Subscribe

Confirmation: implicit

> **User:** Subscribe to Reply All.
> **Bot:** [confirm] Subscribed.
Result: Subscribed to Reply All.

> **User:** Follow Conan O'Brien.
> **Bot:** [confirm] Subscribed.
Result: Subscribed to Conan O'Brien Needs a Friend.

> **User:** Add Lex Fridman to my library.
> **Bot:** [confirm] Subscribed.
Result: Subscribed to Lex Fridman Podcast.

#### Edge cases
- **Already subscribed:**
  > **Bot:** [error]
- **Podcast not found:**
  > **Bot:** Couldn't find a podcast called "Reply Some". Did you mean *Reply All* or *Reply*?

### Unsubscribe

Confirmation: implicit

> **User:** Unsubscribe from Reply All.
> **Bot:** [confirm] Unsubscribed.
Result: Unsubscribed from Reply All.

> **User:** Unfollow Conan.
> **Bot:** [confirm] Unsubscribed.
Result: Unsubscribed from Conan O'Brien Needs a Friend.

> **User:** Remove Lex Fridman.
> **Bot:** [confirm] Unsubscribed.
Result: Unsubscribed from Lex Fridman Podcast.

#### Edge cases
- **Not subscribed:**
  > **Bot:** [error]

---

## Episode Actions

### Download Episode

Confirmation: implicit

> **User:** Download this episode.
> **Bot:** [confirm] Downloading.
Result: Downloading *The Getaway*.

> **User:** Download the latest Reply All.
> **Bot:** [confirm] Downloading.
Result: Downloading *The Inside Job* from Reply All.

> **User:** Save the Obama interview for offline.
> **Bot:** [confirm] Downloading.
Result: Downloading *Barack Obama* from Conan.

#### Edge cases
- **Already downloaded:**
  > **Bot:** [error]
- **Download failure (no WiFi):**
  > **Bot:** Downloads are set to WiFi only. Want me to download anyway?
- **Download failure (no storage):**
  > **Bot:** Not enough storage. Want to clean up some downloads first?

### Delete Download

Confirmation: explicit

> **User:** Delete the download for this episode.
> **Bot:** Delete download for *The Getaway*? 42 MB freed.
> **User:** Yes.
> **Bot:** Download deleted.

> **User:** Remove the downloaded file.
> **Bot:** Delete download for *The Getaway*? 42 MB freed.
> **User:** Yes.
> **Bot:** Download deleted.

#### Edge cases
- **Not downloaded:**
  > **Bot:** [error]

### Star / Unstar

Confirmation: implicit

> **User:** Star this episode.
> **Bot:** [confirm]
Result: Episode starred.

> **User:** Unstar the Conan episode.
> **Bot:** [confirm]
Result: Unstarred *Neil deGrasse Tyson*.

> **User:** Favorite that one.
> **Bot:** [confirm]
Result: Episode starred.

#### Edge cases
- **Already starred:**
  > **Bot:** [error]

### Archive / Unarchive

Confirmation: implicit

> **User:** Archive this episode.
> **Bot:** [confirm]
Result: Episode archived.

> **User:** Unarchive the latest Conan.
> **Bot:** [confirm]
Result: Unarchived *Neil deGrasse Tyson*.

#### Edge cases
- **Already archived:**
  > **Bot:** [error]

### Mark as Played / Unplayed

Confirmation: implicit

> **User:** Mark this as played.
> **Bot:** [confirm]
Result: Marked as played.

> **User:** Mark the Vergecast episode as unplayed.
> **Bot:** [confirm]
Result: Marked as unplayed.

### Remove from Listening History

Confirmation: explicit

> **User:** Remove this from my history.
> **Bot:** Remove *The Getaway* from listening history?
> **User:** Yes.
> **Bot:** Removed.

---

## Bulk Operations

### Bulk Download

Confirmation: explicit

> **User:** Download all unplayed from Reply All.
> **Bot:** Download 12 episodes from Reply All? About 480 MB.
> **User:** Yes.
> **Bot:** Downloading 12 episodes.

> **User:** Download the last 5 episodes of Conan.
> **Bot:** Download 5 episodes? About 200 MB.
> **User:** Yes.
> **Bot:** Downloading 5 episodes.

### Bulk Archive

Confirmation: explicit

> **User:** Archive all played episodes from Reply All.
> **Bot:** Archive 8 played episodes from Reply All?
> **User:** Yes.
> **Bot:** Archived 8 episodes.

> **User:** Archive all played.
> **Bot:** Archive 23 played episodes across all podcasts?
> **User:** Yes.
> **Bot:** Archived 23 episodes.

### Bulk Mark Played

Confirmation: implicit

> **User:** Mark all Reply All as played.
> **Bot:** [confirm]
Result: Marked 12 episodes as played.

> **User:** Mark everything in my queue as played.
> **Bot:** [confirm]
Result: Marked 8 episodes as played.

### Add to Playlist

Confirmation: implicit

> **User:** Add this to my Road Trip playlist.
> **Bot:** [confirm]
Result: Added to Road Trip.

> **User:** Add the latest Conan to Favorites.
> **Bot:** [confirm]
Result: Added *Neil deGrasse Tyson* to Favorites.

#### Edge cases
- **Playlist not found:**
  > **Bot:** No playlist called "Road Trip". Want to create one?

---

## Folders

### Create Folder

Confirmation: implicit

> **User:** Create a folder called News.
> **Bot:** [confirm]
Result: Created folder "News".

> **User:** Make a Tech folder.
> **Bot:** [confirm]
Result: Created folder "Tech".

### Rename Folder

Confirmation: implicit

> **User:** Rename my News folder to World News.
> **Bot:** [confirm]
Result: Renamed to "World News".

### Assign to Folder

Confirmation: implicit

> **User:** Put Reply All in my News folder.
> **Bot:** [confirm]
Result: Moved Reply All to News.

> **User:** Move Conan to Comedy.
> **Bot:** [confirm]
Result: Moved Conan to Comedy.

#### Edge cases
- **Folder not found:**
  > **Bot:** No folder called "Science". Want to create one?

### Remove from Folder

Confirmation: implicit

> **User:** Take Reply All out of the News folder.
> **Bot:** [confirm]
Result: Removed Reply All from News.

### Delete Folder

Confirmation: explicit

> **User:** Delete my News folder.
> **Bot:** Delete "News"? 4 podcasts will be ungrouped.
> **User:** Yes.
> **Bot:** Folder deleted.

### Query Folder Contents

> **User:** What's in my News folder?
> **Bot:** News has 4 podcasts: Reply All, The Daily, NPR News, and BBC Global.

#### Edge cases
- **Folder not found:**
  > **Bot:** You don't have a folder called that. Your folders are: [list].

---

## Ratings

### Rate Podcast

Confirmation: implicit

> **User:** Rate Reply All 4 stars.
> **Bot:** [confirm] 4 stars.
Result: Rated Reply All 4 stars.

> **User:** Give Conan 5 stars.
> **Bot:** [confirm] 5 stars.
Result: Rated Conan O'Brien Needs a Friend 5 stars.

#### Edge cases
- **Rating out of range:**
  > **Bot:** Ratings are 1 to 5 stars. How many?
- **Not subscribed:**
  > **Bot:** [error]

---

## Per-Podcast Settings

### Toggle Notifications

Confirmation: implicit

> **User:** Turn on notifications for Reply All.
> **Bot:** [toggle]
Result: Notifications on for Reply All.

> **User:** Stop notifying me about Conan.
> **Bot:** [toggle]
Result: Notifications off for Conan.

### Auto-Add to Up Next

Confirmation: implicit

> **User:** Auto-add new Reply All episodes to my queue.
> **Bot:** [confirm]
Result: New Reply All episodes will be added to the top of your queue.

> **User:** Auto-add Conan to the bottom.
> **Bot:** [confirm]
Result: New Conan episodes will be added to the end of your queue.

### Auto-Download

Confirmation: implicit

> **User:** Auto-download new Conan episodes.
> **Bot:** [toggle]
Result: Auto-download enabled for Conan.

> **User:** Stop auto-downloading Reply All.
> **Bot:** [toggle]
Result: Auto-download disabled for Reply All.

---

## Podcast Queries

### Query Library Count

> **User:** How many podcasts am I subscribed to?
> **Bot:** 23 podcasts in your library.

### Query Unplayed Count

> **User:** How many unplayed episodes of Reply All?
> **Bot:** 12 unplayed episodes of Reply All.

> **User:** Do I have anything new from Conan?
> **Bot:** 2 unplayed episodes from Conan.

### Query Latest Episode

> **User:** What's the latest episode of Conan?
> **Bot:** The latest is *Neil deGrasse Tyson*, released yesterday. 1 hour 22 minutes.

### Query Subscription

> **User:** Am I subscribed to Reply All?
> **Bot:** Yes, you follow Reply All.

> **User:** Do I follow Lex Fridman?
> **Bot:** No. Want me to subscribe?

### Query New Episodes

> **User:** Any new episodes?
> **Bot:** 5 new episodes. Latest: *The Inside Job* from Reply All, released 2 hours ago.

> **User:** What's new?
> **Bot:** 5 new episodes across your podcasts. Want details?

### Query Listening History

> **User:** What did I listen to yesterday?
> **Bot:** You listened to 3 episodes: *The Getaway* from Reply All, *Neil deGrasse Tyson* from Conan, and *The Vergecast*.

> **User:** What was the last thing I played?
> **Bot:** *The Missing Cryptoqueen*, Episode 4, about 3 hours ago.

### Query Podcast Description

> **User:** What is Reply All about?
> **Bot:** Reply All is a show about the internet and how it makes us feel. Hosted by PJ Vogt and Alex Goldman.

### Query Downloaded Count

> **User:** How many episodes of Reply All are downloaded?
> **Bot:** 4 episodes downloaded, 168 MB.

### Query All Subscriptions

> **User:** What podcasts do I follow?
> **Bot:** 23 podcasts. Some highlights: Reply All, Conan O'Brien, The Vergecast, The Daily, Lex Fridman. Want the full list?

#### Edge cases
- **Empty library:**
  > **Bot:** You're not subscribed to any podcasts yet. Want me to search for something?
