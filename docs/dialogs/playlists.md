# Playlists

Playlists (called "filters" in the app) are powerful but complex in the GUI. Voice simplifies playlist creation with natural language rules and makes managing playlists conversational.

---

## Playlist Management

### Create Playlist

Confirmation: implicit

> **User:** Create a playlist called Road Trip.
> **Bot:** [confirm] Created "Road Trip".

> **User:** Make a new playlist.
> **Bot:** What should I call it?
  > **User:** Summer Drives.
  > **Bot:** [confirm] Created "Summer Drives".

#### Edge cases
- **Name already exists:**
  > **Bot:** You already have a playlist called "Road Trip". Want a different name?

### Create Smart Playlist

Confirmation: implicit

> **User:** Make a playlist of unplayed Reply All episodes over 30 minutes.
> **Bot:** Created "Reply All Long & Unplayed" with 12 matching episodes.

> **User:** Create a filter for downloaded tech podcasts.
> **Bot:** Which tech podcasts? I found 5 in your library.
  > **User:** All of them.
  > **Bot:** Created "Downloaded Tech" with 8 matching episodes.

> **User:** Make a smart playlist for starred episodes.
> **Bot:** Created "Starred Episodes" with 15 episodes.

> **User:** Show me all episodes shorter than 15 minutes.
> **Bot:** Created "Quick Listens" with 23 matching episodes. Want to save it as a playlist?
  > **User:** Yes.
  > **Bot:** Saved as "Quick Listens".

#### Edge cases
- **No matching episodes:**
  > **Bot:** No episodes match those criteria. Want to adjust the rules?
- **Ambiguous rules:**
  > **Bot:** I can filter by podcast, duration, download status, played status, or media type. What matters most?

### Delete Playlist

Confirmation: explicit

> **User:** Delete my Road Trip playlist.
> **Bot:** Delete "Road Trip"? 12 episodes.
> **User:** Yes.
> **Bot:** Deleted.

#### Edge cases
- **Playlist not found:**
  > **Bot:** No playlist called "Road Trip". Your playlists are: [list].

### Rename Playlist

Confirmation: implicit

> **User:** Rename Road Trip to Summer Drives.
> **Bot:** [confirm] Renamed.

### Play All

Confirmation: implicit

> **User:** Play my Road Trip playlist.
Result: Plays Road Trip playlist. 12 episodes.

> **User:** Shuffle my Favorites.
Result: Shuffles Favorites playlist. 8 episodes.

### Download All

Confirmation: explicit

> **User:** Download all episodes in Road Trip.
> **Bot:** Download 12 episodes? About 480 MB.
> **User:** Yes.
> **Bot:** Downloading 12 episodes.

#### Edge cases
- **Already all downloaded:**
  > **Bot:** [error]

### Add Episode

Confirmation: implicit

> **User:** Add this to my Road Trip playlist.
> **Bot:** [confirm]

> **User:** Put the latest Conan in Favorites.
> **Bot:** [confirm]

### Remove Episode

Confirmation: implicit

> **User:** Remove this from Road Trip.
> **Bot:** [confirm]

### Archive / Unarchive All

Confirmation: explicit (archive), implicit (unarchive)

> **User:** Archive all in Road Trip.
> **Bot:** Archive 12 episodes in Road Trip?
> **User:** Yes.
> **Bot:** Archived 12 episodes.

> **User:** Unarchive all in Road Trip.
> **Bot:** Unarchived 12 episodes.

### Auto-Download

Confirmation: implicit

> **User:** Auto-download my Road Trip playlist.
> **Bot:** [toggle] Auto-download on for Road Trip.

> **User:** Stop auto-downloading Road Trip.
> **Bot:** [toggle] Auto-download off for Road Trip.

---

## Playlist Queries

### List Playlists

> **User:** What playlists do I have?
> **Bot:** You have 4 playlists: Road Trip, Favorites, Quick Listens, and Downloaded Tech.

#### Edge cases
- **No playlists:**
  > **Bot:** You don't have any playlists yet. Say "create a playlist" to make one.

### Playlist Contents

> **User:** What's in my Road Trip playlist?
> **Bot:** Road Trip has 12 episodes: *The Getaway* from Reply All, *Neil deGrasse Tyson* from Conan, *The Vergecast*, and 9 more.

> **User:** What's in Favorites?
> **Bot:** 8 episodes. Latest: *The Missing Cryptoqueen*, Episode 4.

### Playlist Count

> **User:** How many episodes in Road Trip?
> **Bot:** 12 episodes.

### Matching Episodes

> **User:** How many episodes match my Tech News filter?
> **Bot:** 23 episodes match.

#### Edge cases
- **No matching episodes:**
  > **Bot:** [error]
