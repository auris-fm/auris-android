# Filters / Playlists

---

## Open Playlist

> **User:** Open my Road Trip playlist.
> **Bot:** Opened Road Trip.

Result: Navigates to the Road Trip playlist detail view.

> **User:** Show me Tech Talks.
> **Bot:** Here's Tech Talks.

Result: Opens the Tech Talks playlist.

> **User:** Go to my workout playlist.
> **Bot:** Opened Workout Mix.

Result: Navigates to the Workout Mix playlist.

> **User:** Open the playlist called Sunday Morning.
> **Bot:** Opened Sunday Morning.

Result: Opens the Sunday Morning playlist.

- **Playlist not found:** If the name doesn't match any existing playlist, the bot should say so.
  > **User:** Open my Favorites playlist.
  > **Bot:** I don't see a playlist called Favorites.
- **Ambiguous name:** If multiple playlists partially match, ask for clarification.

---

## Create Playlist

> **User:** Make a new playlist called Daily Commute.
> **Bot:** Created Daily Commute. Want to add some episodes?

Result: Creates a new empty playlist named "Daily Commute" and opens it.

> **User:** Create a smart playlist for short episodes.
> **Bot:** Created a smart playlist. I set the duration to under 20 minutes—want to adjust that?

Result: Creates a new smart playlist filtered by duration (under 20 min default).

> **User:** New playlist.
> **Bot:** Created a new playlist. You can name it and add episodes whenever you like.

Result: Creates a new untitled playlist.

- **Name already exists:**
  > **User:** Create a playlist called Tech Talks.
  > **Bot:** You already have a playlist called Tech Talks. Want to open it instead?
- **Cannot create (storage or limit issue):**
  > **Bot:** Sorry, I couldn't create that playlist right now.

---

## Delete Playlist

> **User:** Delete my Old Mix playlist.
> **Bot:** Deleted Old Mix.

Result: The playlist is removed.

> **User:** Remove the playlist called Bedtime.
> **Bot:** Deleted Bedtime.

Result: The playlist is removed.

> **User:** Get rid of the Test playlist.
> **Bot:** Deleted Test.

Result: The playlist is removed.

- **Playlist not found:**
  > **User:** Delete my Favorites playlist.
  > **Bot:** I don't see a playlist called Favorites.
- **Deleting last playlist:** The app should allow it; confirm it's intentional.
  > **User:** Delete my only playlist.
  > **Bot:** That's your last playlist—sure you want to delete it?
  > **User:** Yes.
  > **Bot:** Deleted.

---

## Rename Playlist

> **User:** Rename Tech Talks to Tech Podcasts.
> **Bot:** Renamed to Tech Podcasts.

Result: The playlist name is updated.

> **User:** Change the name of my workout playlist to Gym Mix.
> **Bot:** Renamed to Gym Mix.

Result: The playlist name is updated.

- **Name already exists:**
  > **User:** Rename Road Trip to Tech Talks.
  > **Bot:** You already have a playlist called Tech Talks. Want to pick a different name?

---

## Reorder Playlists

> **User:** Move Road Trip to the top.
> **Bot:** Moved Road Trip to the top.

Result: The playlist is moved to position 1 in the list.

> **User:** Put Tech Talks at the bottom.
> **Bot:** Moved Tech Talks to the bottom.

Result: The playlist is moved to the last position.

> **User:** Move Sunday Morning above Workout Mix.
> **Bot:** Done. Sunday Morning is now above Workout Mix.

Result: The playlist is reordered accordingly.

- **Playlist not found:**
  > **User:** Move Favorites to the top.
  > **Bot:** I don't see a playlist called Favorites.

---

## Smart Playlist — Set Rules

> **User:** Create a smart playlist for episodes from Reply All that are longer than 30 minutes.
> **Bot:** Created a smart playlist filtering Reply All episodes over 30 minutes.

Result: Smart playlist created with podcast and duration filters applied.

> **User:** Make a playlist of all unplayed episodes.
> **Bot:** Created a smart playlist for unplayed episodes.

Result: Smart playlist created with episode status set to unplayed.

> **User:** Show me downloaded episodes from Stuff You Should Know.
> **Bot:** Created a smart playlist for downloaded Stuff You Should Know episodes.

Result: Smart playlist with download status and podcast filters.

> **User:** Create a playlist of short starred episodes.
> **Bot:** Created a smart playlist for starred episodes under 20 minutes.

Result: Smart playlist with starred filter and short duration filter.

- **No matching episodes:**
  > **User:** Make a playlist of episodes from Planet Money that are over 5 hours long.
  > **Bot:** Created the playlist, but nothing matches those filters yet. It'll fill in as episodes match.

---

## Smart Playlist — Auto-Download

> **User:** Turn on auto-download for my Tech Talks playlist.
> **Bot:** Auto-download is on for Tech Talks.

Result: Auto-download toggle is enabled for the playlist.

> **User:** Set my Road Trip playlist to auto-download and keep the latest 10 episodes.
> **Bot:** Auto-download is on for Road Trip with a limit of 10 episodes.

Result: Auto-download enabled with a 10-episode limit.

- **No WiFi for auto-download:**
  > **Bot:** Auto-download is on, but episodes will queue until you're on WiFi.

---

## Play All (Playlist Detail)

> **User:** Play all episodes in Road Trip.
> **Bot:** Playing all episodes from Road Trip.

Result: All episodes in the playlist begin playing in order.

> **User:** Play everything in Tech Talks.
> **Bot:** Playing all from Tech Talks.

Result: All episodes in the playlist begin playing.

> **User:** Start my Daily Commute playlist.
> **Bot:** Playing Daily Commute.

Result: All episodes begin playing from the start.

- **Empty playlist:**
  > **User:** Play all in my empty playlist.
  > **Bot:** That playlist is empty—there's nothing to play yet.

---

## Download All (Playlist Detail)

> **User:** Download all episodes in Road Trip.
> **Bot:** Downloading all episodes from Road Trip.

Result: All undownloaded episodes in the playlist are queued for download.

> **User:** Download everything in Tech Talks.
> **Bot:** Downloading all from Tech Talks.

Result: All episodes queued for download.

- **No WiFi:**
  > **User:** Download all in my Road Trip playlist.
  > **Bot:** You're not on WiFi. Download anyway?
  > **User:** Yes.
  > **Bot:** Downloading all from Road Trip.
- **Empty playlist:**
  > **Bot:** That playlist is empty—nothing to download.

---

## Sort Episodes (Playlist Detail)

> **User:** Sort my Tech Talks by newest first.
> **Bot:** Sorted by newest first.

Result: Episodes are reordered with the most recent at the top.

> **User:** Sort Road Trip by shortest duration.
> **Bot:** Sorted by shortest first.

Result: Episodes reordered by duration, shortest first.

> **User:** Sort this playlist alphabetically.
> **Bot:** Sorted alphabetically.

Result: Episodes reordered A-Z by title.

---

## Toggle Show Archived

> **User:** Show archived episodes in Tech Talks.
> **Bot:** Archived episodes are now visible in Tech Talks.

Result: The "Show archived" toggle is turned on.

> **User:** Hide archived episodes in my Road Trip playlist.
> **Bot:** Hidden archived episodes in Road Trip.

Result: The "Show archived" toggle is turned off.

---

## Archive / Unarchive All

> **User:** Archive all episodes in Road Trip.
> **Bot:** Archived all episodes in Road Trip.

Result: All episodes in the playlist are archived.

> **User:** Unarchive everything in Tech Talks.
> **Bot:** Unarchived all episodes in Tech Talks.

Result: All archived episodes in the playlist are restored.

- **Empty playlist:**
  > **Bot:** That playlist is empty—nothing to archive.

---

## Multi-Select

> **User:** Select all episodes in Tech Talks.
> **Bot:** Selected all episodes in Tech Talks.

Result: All episodes in the playlist are highlighted in multi-select mode.

> **User:** Select the first three episodes in Road Trip.
> **Bot:** Selected the first 3 episodes.

Result: The first three episodes are highlighted in multi-select mode.

> **User:** Clear selection.
> **Bot:** Selection cleared.

Result: Multi-select mode is exited.

---

## Manual Playlist — Add / Remove Episodes

> **User:** Add this episode to my Road Trip playlist.
> **Bot:** Added to Road Trip.

Result: The current/selected episode is appended to the Road Trip playlist.

> **User:** Add the latest episode of Reply All to Daily Commute.
> **Bot:** Added to Daily Commute.

Result: The specified episode is added to the playlist.

> **User:** Remove this episode from Tech Talks.
> **Bot:** Removed from Tech Talks.

Result: The episode is removed from the playlist.

- **Episode already in playlist:**
  > **User:** Add this to Tech Talks.
  > **Bot:** This episode is already in Tech Talks.
- **Playlist full or limit reached:**
  > **Bot:** Couldn't add that—Tech Talks has reached its episode limit.

---

## Manual Playlist — Reorder Episodes

> **User:** Move this episode to the top of my Road Trip playlist.
> **Bot:** Moved to the top of Road Trip.

Result: The episode is moved to position 1 in the playlist.

> **User:** Put this episode at the bottom of Tech Talks.
> **Bot:** Moved to the bottom of Tech Talks.

Result: The episode is moved to the last position.
