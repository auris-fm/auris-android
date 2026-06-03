# Podcasts

Voice dialog reference for all actions in the Podcasts category.

---

## Podcast List

### Open podcast detail

> **User:** Open Reply All.
> **Bot:** Opened Reply All.

Result: Navigates to the podcast detail screen for Reply All.

> **User:** Show me 99% Invisible.
> **Bot:** Here's 99% Invisible.

Result: Navigates to the podcast detail screen for 99% Invisible.

> **User:** Go to Hardcore History.
> **Bot:** Opened Hardcore History.

Result: Navigates to the podcast detail screen for Hardcore History.

> **User:** I want to see Lex Fridman's podcast.
> **Bot:** Opened Lex Fridman.

Result: Navigates to the podcast detail screen for the best match.

Edge cases:
- **Ambiguous name**: Multiple podcasts match. The bot should present a short list and ask the user to clarify.
  > **User:** Open Daily.
  > **Bot:** I found a few podcasts called "Daily" — The Daily, Daily Wire, Daily Tech News Show. Which one?
  > **User:** The Daily.
  > **Bot:** Opened The Daily.
- **No match found**:
  > **User:** Open that one about cheese making.
  > **Bot:** I couldn't find a podcast matching that. Want me to search for it?

### Open folder

> **User:** Open my News folder.
> **Bot:** Opened News.

Result: Navigates to the folder view showing its podcasts.

> **User:** Show the Tech folder.
> **Bot:** Here's your Tech folder.

Result: Navigates to the folder view.

> **User:** Go into my Comedy folder.
> **Bot:** Opened Comedy.

Result: Navigates to the folder view.

Edge cases:
- **No folders exist**:
  > **User:** Open my News folder.
  > **Bot:** You don't have any folders yet. Want to create one?
- **Folder not found**:
  > **User:** Open my Science folder.
  > **Bot:** I don't see a folder called Science. Your folders are: News, Tech, Comedy.

### Search podcasts

> **User:** Search for true crime podcasts.
> **Bot:** Searching for "true crime."

Result: Opens search with results for "true crime."

> **User:** Find podcasts about astronomy.
> **Bot:** Searching for "astronomy."

Result: Opens search with results for "astronomy."

> **User:** Look up Conan O'Brien.
> **Bot:** Searching for "Conan O'Brien."

Result: Opens search with results.

### Create folder

> **User:** Create a folder called Health.
> **Bot:** Folder "Health" created.

Result: Creates a new folder named Health.

> **User:** Make a new folder.
> **Bot:** What should the folder be called?
  > **User:** Sports.
  > **Bot:** Folder "Sports" created.

Result: Prompts for name, then creates the folder.

> **User:** New folder named Productivity.
> **Bot:** Folder "Productivity" created.

Result: Creates the folder.

Edge cases:
- **Duplicate name**:
  > **User:** Create a folder called Tech.
  > **Bot:** You already have a folder called Tech. Want to open it instead?

### Reorder podcasts / folders

> **User:** Move Reply All to the top.
> **Bot:** Moved Reply All to the top.

Result: Moves the podcast to the first position.

> **User:** Put the News folder at the bottom.
> **Bot:** Moved News to the bottom.

Result: Moves the folder to the last position.

> **User:** Move Hardcore History above 99% Invisible.
> **Bot:** Moved Hardcore History above 99% Invisible.

Result: Reorders the two podcasts accordingly.

Edge cases:
- **Podcast is inside a folder**:
  > **User:** Move Reply All to the top.
  > **Bot:** Reply All is inside your Comedy folder. Want me to move it to the top of that folder, or take it out first?
- **Name not found**:
  > **User:** Move Serial to the top.
  > **Bot:** I couldn't find a podcast called Serial in your library.

### Pull to refresh

> **User:** Refresh my podcasts.
> **Bot:** Refreshed.

Result: Reloads the podcast list from the server.

> **User:** Check for new episodes.
> **Bot:** Checking now.

Result: Refreshes the podcast list.

> **User:** Refresh.
> **Bot:** Refreshed.

Result: Pulls latest data.

### Cast to device

> **User:** Cast to Living Room speaker.
> **Bot:** Connected to Living Room speaker.

Result: Connects to the named Chromecast device.

> **User:** Cast to my TV.
> **Bot:** Connected to TV.

Result: Connects to the Chromecast device.

> **User:** Cast this.
> **Bot:** Which device? I see: Living Room speaker, Bedroom TV.
  > **User:** Living Room speaker.
  > **Bot:** Connected to Living Room speaker.

Result: Lists available devices, then connects.

Edge cases:
- **No devices found**:
  > **User:** Cast to my TV.
  > **Bot:** I don't see any cast devices on your network. Make sure your device is on the same WiFi.
- **Already connected**:
  > **User:** Cast to Living Room speaker.
  > **Bot:** Already connected to Living Room speaker.

---

## Podcast Detail

### Subscribe / Unsubscribe

> **User:** Subscribe to Radiolab.
> **Bot:** Subscribed to Radiolab.

Result: Adds the podcast to the user's library.

> **User:** Follow Lex Fridman.
> **Bot:** Following Lex Fridman.

Result: Subscribes to the podcast.

> **User:** Unfollow 99% Invisible.
> **Bot:** Unfollowed 99% Invisible.

Result: Unsubscribes from the podcast.

> **User:** Unsubscribe from Hardcore History.
> **Bot:** Unsubscribed from Hardcore History.

Result: Removes the podcast from the library.

Edge cases:
- **Already subscribed**:
  > **User:** Subscribe to Reply All.
  > **Bot:** You're already subscribed to Reply All.
- **Not subscribed when trying to unsubscribe**:
  > **User:** Unsubscribe from Serial.
  > **Bot:** You're not subscribed to Serial.

### Switch tab

> **User:** Show me bookmarks for this podcast.
> **Bot:** Showing bookmarks.

Result: Switches to the Bookmarks tab.

> **User:** Go to recommendations.
> **Bot:** Here are the recommendations.

Result: Switches to the Recommendations tab.

> **User:** Show episodes.
> **Bot:** Showing episodes.

Result: Switches to the Episodes tab.

### Toggle show archived

> **User:** Show archived episodes.
> **Bot:** Showing archived episodes.

Result: Toggles on the "Show archived" filter.

> **User:** Hide archived.
> **Bot:** Hiding archived episodes.

Result: Toggles off the "Show archived" filter.

> **User:** Can I see archived episodes?
> **Bot:** Showing archived episodes now.

Result: Enables the archived filter.

### Archive all episodes

> **User:** Archive all episodes.
> **Bot:** Archived all episodes.

Result: Archives every episode of this podcast.

> **User:** Archive everything in Reply All.
> **Bot:** Archived all episodes of Reply All.

Result: Archives all episodes.

Edge cases:
- **No unarchived episodes**:
  > **User:** Archive all episodes.
  > **Bot:** All episodes are already archived.

### Unarchive all episodes

> **User:** Unarchive all episodes.
> **Bot:** Unarchived all episodes.

Result: Restores all archived episodes.

> **User:** Bring back all archived episodes.
> **Bot:** Unarchived all episodes.

Result: Unarchives every episode.

Edge cases:
- **No archived episodes**:
  > **User:** Unarchive all episodes.
  > **Bot:** There are no archived episodes to unarchive.

### Archive played episodes

> **User:** Archive played episodes.
> **Bot:** Archived all played episodes.

Result: Archives episodes that have been fully listened to.

> **User:** Clean up episodes I've finished.
> **Bot:** Archived 12 played episodes.

Result: Archives all played episodes and reports the count.

Edge cases:
- **No played episodes**:
  > **User:** Archive played episodes.
  > **Bot:** No played episodes to archive.

### Download all episodes

> **User:** Download all episodes.
> **Bot:** Downloading all episodes.

Result: Starts downloading every available episode.

> **User:** Download everything from this podcast.
> **Bot:** Downloading all episodes.

Result: Queues all episodes for download.

Edge cases:
- **All episodes already downloaded**:
  > **User:** Download all episodes.
  > **Bot:** All episodes are already downloaded.
- **No WiFi and WiFi-only setting is on**:
  > **User:** Download all episodes.
  > **Bot:** Downloads are set to WiFi only. Connect to WiFi first, or I can change that setting.
- **Not enough storage**:
  > **User:** Download all episodes.
  > **Bot:** Not enough storage to download all episodes. You have 200 MB free.

### Change sort order

> **User:** Sort by newest first.
> **Bot:** Sorted newest first.

Result: Sets sort order to newest first.

> **User:** Show oldest episodes first.
> **Bot:** Sorted oldest first.

Result: Sets sort order to oldest first.

> **User:** Sort by shortest.
> **Bot:** Sorted by shortest duration.

Result: Sets sort order to shortest first.

> **User:** Sort by longest.
> **Bot:** Sorted by longest duration.

Result: Sets sort order to longest first.

### Change grouping

> **User:** Group by season.
> **Bot:** Grouped by season.

Result: Sets grouping to "by season."

> **User:** Turn off grouping.
> **Bot:** Grouping removed.

Result: Sets grouping to "none."

> **User:** Don't group episodes.
> **Bot:** Grouping removed.

Result: Sets grouping to "none."

### Search episodes

> **User:** Search for the AI episode.
> **Bot:** Searching episodes for "AI."

Result: Filters the episode list by the search query.

> **User:** Find the interview with Obama.
> **Bot:** Searching episodes for "interview with Obama."

Result: Searches episodes for the query.

> **User:** Search episodes about climate.
> **Bot:** Searching episodes for "climate."

Result: Filters episodes by the search term.

### Toggle notifications

> **User:** Turn on notifications for this podcast.
> **Bot:** Notifications on.

Result: Enables push notifications for new episodes.

> **User:** Turn off notifications for Reply All.
> **Bot:** Notifications off for Reply All.

Result: Disables notifications.

> **User:** Mute this podcast.
> **Bot:** Notifications off.

Result: Disables notifications.

### Open notification settings

> **User:** Open notification settings.
> **Bot:** Opening notification settings.

Result: Navigates to the notification settings screen.

> **User:** Show me notification preferences.
> **Bot:** Opening notification settings.

Result: Navigates to notification settings.

### Assign to / remove from folder

> **User:** Move this podcast to my News folder.
> **Bot:** Moved to News.

Result: Assigns the podcast to the named folder.

> **User:** Take this out of the Comedy folder.
> **Bot:** Removed from Comedy.

Result: Removes the podcast from its current folder.

> **User:** Add Reply All to my Tech folder.
> **Bot:** Added Reply All to Tech.

Result: Assigns the podcast to the folder.

Edge cases:
- **Podcast already in the folder**:
  > **User:** Move Reply All to Tech.
  > **Bot:** Reply All is already in Tech.
- **Folder not found**:
  > **User:** Move to Science folder.
  > **Bot:** You don't have a folder called Science. Want to create one?

### Pull to refresh

> **User:** Refresh this podcast.
> **Bot:** Refreshed.

Result: Reloads the podcast detail from the server.

> **User:** Check for new episodes of Reply All.
> **Bot:** Checking now.

Result: Refreshes the podcast.

---

## Episode Actions

### Play

> **User:** Play the latest episode.
> **Bot:** Playing the latest episode.

Result: Starts playback of the most recent episode.

> **User:** Play episode 42 of Hardcore History.
> **Bot:** Playing episode 42.

Result: Starts playback of the specified episode.

> **User:** Play it.
> **Bot:** Playing.

Result: Starts playback of the selected or current episode.

### Download / Delete download

> **User:** Download this episode.
> **Bot:** Downloading.

Result: Starts downloading the episode.

> **User:** Download the latest episode.
> **Bot:** Downloading the latest episode.

Result: Queues the most recent episode for download.

> **User:** Delete the download.
> **Bot:** Download deleted.

Result: Removes the downloaded file.

> **User:** Remove the download for this episode.
> **Bot:** Download removed.

Result: Deletes the local file.

Edge cases:
- **Already downloaded**:
  > **User:** Download this episode.
  > **Bot:** This episode is already downloaded.
- **Download failure (no WiFi)**:
  > **User:** Download this episode.
  > **Bot:** Downloads are set to WiFi only. Connect to WiFi or I can change that setting.
- **Download failure (no storage)**:
  > **User:** Download this episode.
  > **Bot:** Not enough storage to download this episode.
- **Not downloaded when trying to delete**:
  > **User:** Delete the download.
  > **Bot:** This episode isn't downloaded.

### Star / Unstar

> **User:** Star this episode.
> **Bot:** Starred.

Result: Adds the episode to starred.

> **User:** Unstar this one.
> **Bot:** Unstarred.

Result: Removes the star.

> **User:** Favorite this episode.
> **Bot:** Starred.

Result: Stars the episode.

Edge cases:
- **Already starred**:
  > **User:** Star this episode.
  > **Bot:** This episode is already starred.

### Archive / Unarchive

> **User:** Archive this episode.
> **Bot:** Archived.

Result: Archives the episode.

> **User:** Unarchive this episode.
> **Bot:** Unarchived.

Result: Restores the episode.

> **User:** Archive it.
> **Bot:** Archived.

Result: Archives the selected episode.

Edge cases:
- **Already archived**:
  > **User:** Archive this episode.
  > **Bot:** This episode is already archived.

### Share

> **User:** Share this episode.
> **Bot:** Opening share sheet.

Result: Opens the system share dialog for the episode.

> **User:** Send this to someone.
> **Bot:** Opening share.

Result: Opens the share sheet.

### Add to playlist

> **User:** Add this to my Road Trip playlist.
> **Bot:** Added to Road Trip.

Result: Adds the episode to the named playlist.

> **User:** Add to playlist.
> **Bot:** Which playlist? You have: Road Trip, Daily Listen, Favorites.
  > **User:** Daily Listen.
  > **Bot:** Added to Daily Listen.

Result: Lists playlists and adds to the chosen one.

Edge cases:
- **Playlist not found**:
  > **User:** Add to my Work playlist.
  > **Bot:** You don't have a playlist called Work. Want to create one?
- **Already in the playlist**:
  > **User:** Add this to Road Trip.
  > **Bot:** This episode is already in Road Trip.

### Mark as played / unplayed

> **User:** Mark as played.
> **Bot:** Marked as played.

Result: Marks the episode as played.

> **User:** Mark as unplayed.
> **Bot:** Marked as unplayed.

Result: Resets the episode to unplayed.

> **User:** I finished this one.
> **Bot:** Marked as played.

Result: Marks as played.

### Remove from listening history

> **User:** Remove this from my history.
> **Bot:** Removed from history.

Result: Deletes the episode from listening history.

> **User:** Clear this from listening history.
> **Bot:** Removed from history.

Result: Removes the episode.

---

## Multi-Select (Episodes)

### Enter multi-select

> **User:** Select multiple episodes.
> **Bot:** Tap the episodes you want to select.

Result: Enters multi-select mode.

> **User:** Select all episodes.
> **Bot:** All episodes selected.

Result: Enters multi-select mode with all episodes selected.

### Select all / Deselect all

> **User:** Select all.
> **Bot:** All episodes selected.

Result: Selects every episode in the list.

> **User:** Deselect all.
> **Bot:** All deselected.

Result: Clears the selection.

### Select all above / below

> **User:** Select all above this one.
> **Bot:** Selected 8 episodes above.

Result: Selects all episodes above the current one.

> **User:** Select everything below.
> **Bot:** Selected 5 episodes below.

Result: Selects all episodes below the current one.

### Download selected

> **User:** Download the selected episodes.
> **Bot:** Downloading 12 episodes.

Result: Starts downloading all selected episodes.

> **User:** Download them all. (while in multi-select)
> **Bot:** Downloading 12 episodes.

Result: Downloads the selected episodes.

Edge cases:
- **Some already downloaded**:
  > **User:** Download the selected episodes.
  > **Bot:** 8 are already downloaded. Downloading the remaining 4.

### Delete downloads selected

> **User:** Delete downloads for the selected episodes.
> **Bot:** Deleted 6 downloads.

Result: Removes downloaded files for all selected episodes.

> **User:** Remove those downloads.
> **Bot:** Deleted 6 downloads.

Result: Removes the downloads.

Edge cases:
- **None are downloaded**:
  > **User:** Delete downloads for the selected episodes.
  > **Bot:** None of the selected episodes are downloaded.

### Archive / Unarchive selected

> **User:** Archive the selected episodes.
> **Bot:** Archived 10 episodes.

Result: Archives all selected episodes.

> **User:** Unarchive these.
> **Bot:** Unarchived 10 episodes.

Result: Unarchives the selected episodes.

Edge cases:
- **Already archived**:
  > **User:** Archive the selected episodes.
  > **Bot:** These are already archived.

### Delete selected (user episodes)

> **User:** Delete the selected episodes.
> **Bot:** Deleted 3 episodes.

Result: Permanently deletes the selected user-uploaded episodes.

> **User:** Remove these.
> **Bot:** Deleted 3 episodes.

Result: Deletes the selected files.

### Share selected

> **User:** Share the selected episodes.
> **Bot:** Opening share for 4 episodes.

Result: Opens the share sheet for the selected episodes.

### Mark as played / unplayed (selected)

> **User:** Mark selected as played.
> **Bot:** Marked 7 episodes as played.

Result: Marks all selected episodes as played.

> **User:** Mark these as unplayed.
> **Bot:** Marked 7 episodes as unplayed.

Result: Resets playback status.

### Play next / Play last (selected)

> **User:** Play these next.
> **Bot:** Added 3 episodes to the top of Up Next.

Result: Adds selected episodes to the top of the queue.

> **User:** Play these last.
> **Bot:** Added 3 episodes to the bottom of Up Next.

Result: Adds selected episodes to the end of the queue.

### Star / Unstar selected

> **User:** Star the selected episodes.
> **Bot:** Starred 5 episodes.

Result: Stars all selected episodes.

> **User:** Unstar these.
> **Bot:** Unstarred 5 episodes.

Result: Removes stars from all selected episodes.

Edge cases:
- **Already starred**:
  > **User:** Star the selected episodes.
  > **Bot:** All 5 are already starred.

### Remove from history (selected)

> **User:** Remove these from history.
> **Bot:** Removed 4 episodes from history.

Result: Deletes the selected episodes from listening history.

### Add to playlist (selected)

> **User:** Add these to Road Trip.
> **Bot:** Added 6 episodes to Road Trip.

Result: Adds all selected episodes to the named playlist.

> **User:** Add selected to a playlist.
> **Bot:** Which playlist? You have: Road Trip, Daily Listen.
  > **User:** Daily Listen.
  > **Bot:** Added 6 episodes to Daily Listen.

Result: Lists playlists and adds to the chosen one.

---

## Folders

### Create folder

> **User:** Create a folder called Science.
> **Bot:** Folder "Science" created.

Result: Creates a new folder named Science.

> **User:** Make a new folder and call it Politics.
> **Bot:** Folder "Politics" created.

Result: Creates the folder.

> **User:** New folder.
> **Bot:** What should the folder be called?
  > **User:** Health.
  > **Bot:** Folder "Health" created.

Result: Prompts for the name, then creates the folder.

Edge cases:
- **Duplicate name**:
  > **User:** Create a folder called Tech.
  > **Bot:** You already have a folder called Tech. Want to open it instead?

### Edit folder name

> **User:** Rename the News folder to World News.
> **Bot:** Renamed News to World News.

Result: Updates the folder name.

> **User:** Change the Comedy folder name to Stand-up.
> **Bot:** Renamed Comedy to Stand-up.

Result: Updates the folder name.

> **User:** Call my Tech folder something else.
> **Bot:** What should I rename Tech to?
  > **User:** Tech & Science.
  > **Bot:** Renamed to Tech & Science.

Result: Prompts for the new name and applies it.

### Change folder color

> **User:** Change the News folder to blue.
> **Bot:** News folder set to blue.

Result: Updates the folder color.

> **User:** Make my Comedy folder red.
> **Bot:** Comedy folder set to red.

Result: Updates the folder color.

> **User:** Change the color of Tech to green.
> **Bot:** Tech folder set to green.

Result: Updates the folder color.

Edge cases:
- **Unrecognized color**:
  > **User:** Make News folder burgundy.
  > **Bot:** I don't have that color. Available colors are: red, orange, yellow, green, blue, purple, pink, grey.

### Add podcast to folder

> **User:** Add Reply All to my Comedy folder.
> **Bot:** Added Reply All to Comedy.

Result: Assigns the podcast to the folder.

> **User:** Put 99% Invisible in Tech.
> **Bot:** Added 99% Invisible to Tech.

Result: Moves the podcast into the folder.

> **User:** Move Serial to the News folder.
> **Bot:** Moved Serial to News.

Result: Adds Serial to the News folder.

Edge cases:
- **Already in the folder**:
  > **User:** Add Reply All to Comedy.
  > **Bot:** Reply All is already in Comedy.
- **Folder not found**:
  > **User:** Add Reply All to Science.
  > **Bot:** You don't have a folder called Science. Want to create one?

### Remove podcast from folder

> **User:** Take Reply All out of the Comedy folder.
> **Bot:** Removed Reply All from Comedy.

Result: Removes the podcast from the folder.

> **User:** Remove Serial from News.
> **Bot:** Removed Serial from News.

Result: Removes the podcast from the folder.

Edge cases:
- **Not in the folder**:
  > **User:** Take Reply All out of Tech.
  > **Bot:** Reply All isn't in Tech.

### Delete folder

> **User:** Delete the News folder.
> **Bot:** Deleted News folder. The podcasts are still in your library.

Result: Deletes the folder but keeps the podcasts.

> **User:** Get rid of the Comedy folder.
> **Bot:** Deleted Comedy folder. The podcasts are still in your library.

Result: Removes the folder.

Edge cases:
- **Folder not found**:
  > **User:** Delete my Science folder.
  > **Bot:** You don't have a folder called Science.

### Change folder sort order

> **User:** Sort my News folder by name.
> **Bot:** News folder sorted by name.

Result: Sets the folder's internal sort order to alphabetical.

> **User:** Sort the Tech folder by newest.
> **Bot:** Tech folder sorted by newest episode.

Result: Sets the sort order to newest first.

> **User:** Sort Comedy by oldest first.
> **Bot:** Comedy folder sorted by oldest first.

Result: Sets the sort order to oldest first.

Edge cases:
- **Folder not found**:
  > **User:** Sort Science folder by name.
  > **Bot:** You don't have a folder called Science.

---

## Ratings

### Give star rating (1-5)

> **User:** Give Reply All 5 stars.
> **Bot:** Rated Reply All 5 stars.

Result: Sets the rating to 5 stars.

> **User:** Rate this podcast 3 stars.
> **Bot:** Rated 3 stars.

Result: Sets the rating to 3 stars on the current podcast.

> **User:** Give Hardcore History four stars.
> **Bot:** Rated Hardcore History 4 stars.

Result: Sets the rating.

> **User:** Rate Radiolab 1 star.
> **Bot:** Rated Radiolab 1 star.

Result: Sets the rating.

Edge cases:
- **Rating outside 1-5**:
  > **User:** Give this 7 stars.
  > **Bot:** Ratings go from 1 to 5 stars. How many would you like to give?
- **Not subscribed**:
  > **User:** Rate this podcast 4 stars.
  > **Bot:** You need to subscribe to this podcast before rating it.
- **Non-numeric input**:
  > **User:** Give this a great rating.
  > **Bot:** How many stars? Pick a number from 1 to 5.

### Submit rating

> **User:** Submit my rating.
> **Bot:** Rating submitted.

Result: Sends the selected star rating to the server.

> **User:** Send the rating.
> **Bot:** Rating submitted.

Result: Submits the rating.

Edge cases:
- **No rating selected**:
  > **User:** Submit my rating.
  > **Bot:** You haven't selected a rating yet. Pick from 1 to 5 stars first.
