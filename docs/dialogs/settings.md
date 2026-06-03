# Settings

Voice dialog reference for all actions in the Settings category.

---

## Appearance

### Change theme

> **User:** Switch to dark theme.
> **Bot:** Theme set to dark.

Result: App theme changes to Dark mode.

> **User:** Change to light mode.
> **Bot:** Theme set to light.

Result: App theme changes to Light mode.

> **User:** Use the classic dark theme.
> **Bot:** Theme set to classic dark.

Result: App theme changes to Classic Dark.

> **User:** Switch to ink theme.
> **Bot:** Theme set to ink.

Result: App theme changes to Ink.

> **User:** Set my theme to dark.
> **Bot:** Theme set to dark.

Result: App theme changes to Dark mode.

Edge cases:
- **Invalid theme name:**
  > **User:** Switch to midnight theme.
  > **Bot:** I don't recognize that theme. You can choose from: light, dark, classic dark, or ink.
- **Already on the requested theme:**
  > **User:** Switch to dark theme.
  > **Bot:** Already on dark.

---

### Follow system theme

> **User:** Turn on system theme.
> **Bot:** System theme enabled.

Result: The app theme follows the system-wide dark/light setting.

> **User:** Match the system theme.
> **Bot:** System theme enabled.

Result: App theme now syncs with the device theme.

> **User:** Use my phone's theme.
> **Bot:** System theme enabled.

Result: App theme follows the device setting.

> **User:** Turn off system theme.
> **Bot:** System theme disabled. Using dark.

Result: The app stops following the system theme and uses the last manually selected theme.

Edge cases:
- **Already on and asked to enable:**
  > **User:** Turn on system theme.
  > **Bot:** System theme is already enabled.
- **Already off and asked to disable:**
  > **User:** Turn off system theme.
  > **Bot:** System theme is already disabled.

---

### Change app icon

> **User:** Change my app icon.
> **Bot:** Opening icon picker.

Result: Displays the app icon selection screen.

> **User:** Pick a new icon.
> **Bot:** Opening icon picker.

Result: Opens the icon picker.

> **User:** Switch the app icon to dark.
> **Bot:** Icon changed to dark.

Result: The launcher icon changes to the dark variant.

> **User:** Use the classic icon.
> **Bot:** Icon changed to classic.

Result: The launcher icon changes to the classic variant.

Edge cases:
- **Requested icon not available:**
  > **User:** Switch the icon to round.
  > **Bot:** That icon isn't available. Want me to open the icon picker so you can choose?

---

### Toggle dark Up Next

> **User:** Turn on dark Up Next.
> **Bot:** Dark Up Next enabled.

Result: The Up Next queue uses a dark theme regardless of the app theme.

> **User:** Turn off dark Up Next theme.
> **Bot:** Dark Up Next disabled.

Result: The Up Next queue follows the app's theme.

> **User:** Enable dark mode for Up Next.
> **Bot:** Dark Up Next enabled.

Result: Up Next uses the dark theme.

> **User:** Use the regular theme for Up Next.
> **Bot:** Dark Up Next disabled.

Result: Up Next follows the app theme.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on dark Up Next.
  > **Bot:** Dark Up Next is already on.

---

### Toggle dynamic colors widget

> **User:** Turn on dynamic colors widget.
> **Bot:** Dynamic colors widget enabled.

Result: The widget uses Material You dynamic colors.

> **User:** Disable dynamic colors on the widget.
> **Bot:** Dynamic colors widget disabled.

Result: The widget uses the default color scheme.

> **User:** Use dynamic colors for the widget.
> **Bot:** Dynamic colors widget enabled.

Result: Widget colors adapt to the system Material You palette.

Edge cases:
- **Device doesn't support Material You:**
  > **User:** Turn on dynamic colors widget.
  > **Bot:** Dynamic colors require Android 12 or later.

---

### Toggle artwork on lock screen

> **User:** Show artwork on my lock screen.
> **Bot:** Lock screen artwork enabled.

Result: Episode artwork appears on the lock screen during playback.

> **User:** Turn off lock screen artwork.
> **Bot:** Lock screen artwork disabled.

Result: The lock screen no longer shows episode artwork.

> **User:** Hide the artwork on the lock screen.
> **Bot:** Lock screen artwork disabled.

Result: Lock screen artwork is hidden.

> **User:** Show the episode cover on the lock screen.
> **Bot:** Lock screen artwork enabled.

Result: Episode artwork appears on the lock screen.

Edge cases:
- **Already in the requested state:**
  > **User:** Show artwork on my lock screen.
  > **Bot:** Lock screen artwork is already enabled.

---

### Toggle episode artwork

> **User:** Turn on episode artwork.
> **Bot:** Episode artwork enabled.

Result: Each episode displays its own artwork instead of the podcast-level artwork.

> **User:** Use episode artwork.
> **Bot:** Episode artwork enabled.

Result: Individual episode artwork is shown.

> **User:** Turn off episode artwork.
> **Bot:** Episode artwork disabled.

Result: Episodes use the podcast-level artwork.

> **User:** Use the podcast cover for all episodes.
> **Bot:** Episode artwork disabled.

Result: All episodes display the podcast's cover image.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on episode artwork.
  > **Bot:** Episode artwork is already enabled.

---

### Refresh all artwork

> **User:** Refresh all artwork.
> **Bot:** Refreshing all artwork.

Result: All podcast and episode artwork is re-downloaded from the server.

> **User:** Reload all my podcast covers.
> **Bot:** Refreshing all artwork.

Result: Every artwork image is refreshed.

> **User:** Update all the artwork.
> **Bot:** Refreshing all artwork.

Result: All artwork is re-fetched.

> **User:** Re-download all the images.
> **Bot:** Refreshing all artwork.

Result: All artwork images are re-downloaded.

Edge cases:
- **Network unavailable:**
  > **User:** Refresh all artwork.
  > **Bot:** You're offline. I'll refresh artwork when you're back online.

---

## Auto Download

### Auto-download Up Next

> **User:** Turn on auto-download for Up Next.
> **Bot:** Auto-download for Up Next enabled.

Result: Episodes added to Up Next are automatically downloaded.

> **User:** Auto-download my Up Next queue.
> **Bot:** Auto-download for Up Next enabled.

Result: Up Next episodes are downloaded automatically.

> **User:** Stop auto-downloading Up Next.
> **Bot:** Auto-download for Up Next disabled.

Result: Episodes in Up Next are no longer auto-downloaded.

> **User:** Turn off Up Next auto-download.
> **Bot:** Auto-download for Up Next disabled.

Result: Up Next auto-download is turned off.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on auto-download for Up Next.
  > **Bot:** Up Next auto-download is already enabled.

---

### Auto-download new episodes

> **User:** Turn on auto-download for new episodes.
> **Bot:** Auto-download new episodes enabled.

Result: New episodes from followed podcasts are downloaded automatically.

> **User:** Automatically download new episodes.
> **Bot:** Auto-download new episodes enabled.

Result: New episodes are auto-downloaded.

> **User:** Stop auto-downloading new episodes.
> **Bot:** Auto-download new episodes disabled.

Result: New episodes are no longer auto-downloaded.

> **User:** Turn off auto-download.
> **Bot:** Auto-download new episodes disabled.

Result: Auto-download for new episodes is disabled.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on auto-download for new episodes.
  > **Bot:** Auto-download for new episodes is already enabled.

---

### Auto-download on follow

> **User:** Auto-download when I follow a podcast.
> **Bot:** Auto-download on follow enabled.

Result: New episodes are auto-downloaded for any podcast you follow.

> **User:** Turn on auto-download when I follow.
> **Bot:** Auto-download on follow enabled.

Result: Following a podcast enables auto-download for it.

> **User:** Turn off auto-download on follow.
> **Bot:** Auto-download on follow disabled.

Result: Following a podcast no longer enables auto-download.

> **User:** Don't auto-download when I follow a show.
> **Bot:** Auto-download on follow disabled.

Result: Auto-download on follow is disabled.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on auto-download when I follow.
  > **Bot:** Auto-download on follow is already enabled.

---

### Set download limit

> **User:** Set the download limit to 5 episodes per podcast.
> **Bot:** Download limit set to 5 episodes.

Result: Each podcast auto-downloads at most 5 episodes.

> **User:** Limit auto-downloads to 3 episodes.
> **Bot:** Download limit set to 3 episodes.

Result: The per-podcast auto-download limit changes to 3.

> **User:** Set the episode limit to 10.
> **Bot:** Download limit set to 10 episodes.

Result: Each podcast retains up to 10 auto-downloaded episodes.

> **User:** Remove the download limit.
> **Bot:** Download limit removed. All episodes will be kept.

Result: The per-podcast episode limit is removed.

Edge cases:
- **Invalid number:**
  > **User:** Set download limit to zero.
  > **Bot:** The minimum limit is 1 episode.

---

### WiFi-only

> **User:** Turn on WiFi-only downloads.
> **Bot:** WiFi-only downloads enabled.

Result: Auto-downloads only happen on WiFi.

> **User:** Only download on WiFi.
> **Bot:** WiFi-only downloads enabled.

Result: Downloads are restricted to WiFi.

> **User:** Allow downloads on mobile data.
> **Bot:** WiFi-only downloads disabled.

Result: Auto-downloads can use mobile data.

> **User:** Turn off WiFi-only.
> **Bot:** WiFi-only downloads disabled.

Result: Downloads are no longer restricted to WiFi.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on WiFi-only downloads.
  > **Bot:** WiFi-only is already enabled.

---

### Charging-only

> **User:** Only download while charging.
> **Bot:** Charging-only downloads enabled.

Result: Auto-downloads only start when the device is charging.

> **User:** Turn on charging-only downloads.
> **Bot:** Charging-only downloads enabled.

Result: Downloads are restricted to when the device is plugged in.

> **User:** Turn off charging-only downloads.
> **Bot:** Charging-only downloads disabled.

Result: Downloads can happen regardless of charging state.

> **User:** Allow downloads when not charging.
> **Bot:** Charging-only downloads disabled.

Result: The charging restriction is removed.

Edge cases:
- **Already in the requested state:**
  > **User:** Only download while charging.
  > **Bot:** Charging-only is already enabled.

---

### Storage check

> **User:** Turn on storage check for downloads.
> **Bot:** Storage check enabled.

Result: Auto-downloads are skipped when device storage is low.

> **User:** Only download when there's enough storage.
> **Bot:** Storage check enabled.

Result: Downloads are gated on available storage.

> **User:** Turn off the storage check.
> **Bot:** Storage check disabled.

Result: Downloads proceed regardless of available storage.

> **User:** Download even if storage is low.
> **Bot:** Storage check disabled.

Result: The storage threshold check is removed.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on storage check.
  > **Bot:** Storage check is already enabled.

---

### Per-podcast auto-download

> **User:** Turn on auto-download for Lex Fridman.
> **Bot:** Auto-download enabled for Lex Fridman.

Result: New episodes of the specified podcast are auto-downloaded.

> **User:** Auto-download Conan O'Brien.
> **Bot:** Auto-download enabled for Conan O'Brien Needs a Friend.

Result: The specified podcast is added to auto-download.

> **User:** Stop auto-downloading The Vergecast.
> **Bot:** Auto-download disabled for The Vergecast.

Result: The specified podcast is removed from auto-download.

> **User:** Turn off auto-download for that podcast.
> **Bot:** Auto-download disabled for Reply All.

Result: Auto-download is turned off for the specified podcast.

Edge cases:
- **Podcast not found:**
  > **User:** Auto-download Serial.
  > **Bot:** I couldn't find a podcast called "Serial" in your library. Check the name and try again.
- **Already in the requested state:**
  > **User:** Turn on auto-download for Lex Fridman.
  > **Bot:** Auto-download is already enabled for Lex Fridman.
- **No podcast specified:**
  > **User:** Turn on auto-download.
  > **Bot:** Which podcast should I auto-download?

---

### Per-playlist auto-download

> **User:** Turn on auto-download for my Tech News playlist.
> **Bot:** Auto-download enabled for Tech News.

Result: Episodes in the specified playlist are auto-downloaded.

> **User:** Auto-download my Favorites playlist.
> **Bot:** Auto-download enabled for Favorites.

Result: The playlist's episodes are set to auto-download.

> **User:** Stop auto-downloading the Weekend playlist.
> **Bot:** Auto-download disabled for Weekend.

Result: The specified playlist is removed from auto-download.

> **User:** Turn off auto-download for that playlist.
> **Bot:** Auto-download disabled for Daily Picks.

Result: Auto-download is turned off for the specified playlist.

Edge cases:
- **Playlist not found:**
  > **User:** Auto-download my Road Trip playlist.
  > **Bot:** I couldn't find a playlist called "Road Trip." Check the name and try again.
- **Already in the requested state:**
  > **User:** Turn on auto-download for Tech News.
  > **Bot:** Auto-download is already enabled for Tech News.
- **No playlist specified:**
  > **User:** Auto-download my playlist.
  > **Bot:** Which playlist should I auto-download?

---

### Stop all downloads

> **User:** Stop all downloads.
> **Bot:** All downloads stopped.

Result: All active and queued downloads are cancelled.

> **User:** Cancel my downloads.
> **Bot:** All downloads stopped.

Result: Every pending download is cancelled.

> **User:** Stop downloading everything.
> **Bot:** All downloads stopped.

Result: All downloads are halted.

> **User:** Cancel all active downloads.
> **Bot:** All downloads stopped.

Result: Active and queued downloads are cancelled.

Edge cases:
- **No active downloads:**
  > **User:** Stop all downloads.
  > **Bot:** There are no downloads in progress.

---

### Clear download errors

> **User:** Clear download errors.
> **Bot:** Download errors cleared.

Result: All saved download error notifications are removed.

> **User:** Clear the download error messages.
> **Bot:** Download errors cleared.

Result: Download error history is wiped.

> **User:** Get rid of the download errors.
> **Bot:** Download errors cleared.

Result: All download errors are dismissed.

Edge cases:
- **No download errors:**
  > **User:** Clear download errors.
  > **Bot:** There are no download errors to clear.

---

## Headphone Controls

### Set next-track action

> **User:** Set next track to skip forward.
> **Bot:** Next track set to skip forward.

Result: Pressing the next-track button on headphones skips forward in the current episode.

> **User:** Make the next button add a bookmark.
> **Bot:** Next track set to add bookmark.

Result: The next-track headphone button creates a bookmark at the current position.

> **User:** Change the next track action to skip back.
> **Bot:** Next track set to skip back.

Result: The next-track button triggers a skip-back action.

> **User:** When I press next on my headphones, skip forward.
> **Bot:** Next track set to skip forward.

Result: The headphone next-track button skips forward.

Edge cases:
- **Invalid action:**
  > **User:** Set next track to pause.
  > **Bot:** The next-track button can only be set to skip forward, skip back, or add bookmark.

---

### Set previous-track action

> **User:** Set previous track to skip back.
> **Bot:** Previous track set to skip back.

Result: Pressing the previous-track button on headphones skips backward in the current episode.

> **User:** Make the back button add a bookmark.
> **Bot:** Previous track set to add bookmark.

Result: The previous-track headphone button creates a bookmark.

> **User:** Change the previous track action to skip forward.
> **Bot:** Previous track set to skip forward.

Result: The previous-track button triggers a skip-forward action.

> **User:** When I press back on my headphones, skip back.
> **Bot:** Previous track set to skip back.

Result: The headphone previous-track button skips back.

Edge cases:
- **Invalid action:**
  > **User:** Set previous track to play.
  > **Bot:** The previous-track button can only be set to skip forward, skip back, or add bookmark.

---

### Toggle confirmation sound

> **User:** Turn on the bookmark confirmation sound.
> **Bot:** Confirmation sound enabled.

Result: A chime plays when a bookmark is created via headphone controls.

> **User:** Turn off the bookmark chime.
> **Bot:** Confirmation sound disabled.

Result: No sound plays when creating a bookmark via headphones.

> **User:** Play a sound when I add a bookmark.
> **Bot:** Confirmation sound enabled.

Result: The bookmark confirmation chime is turned on.

> **User:** Mute the bookmark sound.
> **Bot:** Confirmation sound disabled.

Result: The confirmation chime is turned off.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on the bookmark confirmation sound.
  > **Bot:** The confirmation sound is already on.

---

## Auto Add to Up Next

### Enable auto-add per podcast

> **User:** Auto-add Lex Fridman to Up Next.
> **Bot:** Auto-add enabled for Lex Fridman.

Result: New episodes from the specified podcast are automatically added to Up Next.

> **User:** Turn on auto-add for Conan O'Brien.
> **Bot:** Auto-add enabled for Conan O'Brien Needs a Friend.

Result: The podcast is set to auto-add new episodes to Up Next.

> **User:** Stop auto-adding The Vergecast to Up Next.
> **Bot:** Auto-add disabled for The Vergecast.

Result: New episodes from the podcast are no longer auto-added.

> **User:** Turn off auto-add for that podcast.
> **Bot:** Auto-add disabled for Reply All.

Result: Auto-add to Up Next is turned off for the podcast.

Edge cases:
- **Podcast not found:**
  > **User:** Auto-add Serial to Up Next.
  > **Bot:** I couldn't find a podcast called "Serial" in your library.
- **Already in the requested state:**
  > **User:** Auto-add Lex Fridman to Up Next.
  > **Bot:** Auto-add is already enabled for Lex Fridman.
- **No podcast specified:**
  > **User:** Auto-add to Up Next.
  > **Bot:** Which podcast should I auto-add to Up Next?

---

### Set position (top / bottom)

> **User:** Add new episodes to the top of Up Next.
> **Bot:** New episodes will be added to the top.

Result: Auto-added episodes are placed at the top of the Up Next queue.

> **User:** Put auto-added episodes at the bottom of Up Next.
> **Bot:** New episodes will be added to the bottom.

Result: Auto-added episodes are appended to the end of the Up Next queue.

> **User:** Set the auto-add position to top.
> **Bot:** New episodes will be added to the top.

Result: Auto-added episodes go to the top of Up Next.

> **User:** Queue new episodes at the bottom.
> **Bot:** New episodes will be added to the bottom.

Result: Auto-added episodes are placed at the bottom of Up Next.

Edge cases:
- **Already in the requested position:**
  > **User:** Add new episodes to the top of Up Next.
  > **Bot:** Auto-add is already set to top.

---

### Set limit

> **User:** Set the auto-add limit to 5 episodes.
> **Bot:** Auto-add limit set to 5 episodes.

Result: At most 5 episodes from this podcast are kept in Up Next via auto-add.

> **User:** Limit auto-add to 3 episodes.
> **Bot:** Auto-add limit set to 3 episodes.

Result: The maximum auto-added episodes is set to 3.

> **User:** Set the Up Next limit to 10 for this podcast.
> **Bot:** Auto-add limit set to 10 episodes.

Result: Up to 10 episodes can be auto-added from this podcast.

> **User:** Remove the auto-add limit.
> **Bot:** Auto-add limit removed.

Result: There is no longer a cap on auto-added episodes from this podcast.

Edge cases:
- **Invalid number:**
  > **User:** Set auto-add limit to zero.
  > **Bot:** The minimum limit is 1 episode.
- **No podcast context:**
  > **User:** Set the auto-add limit to 5.
  > **Bot:** Which podcast should I set the limit for?

---

## Auto Archive

### Set archive after played

> **User:** Archive episodes after I finish them.
> **Bot:** Auto-archive after playing set to immediately.

Result: Episodes are archived as soon as they are marked as played.

> **User:** Archive played episodes after 24 hours.
> **Bot:** Auto-archive after playing set to 24 hours.

Result: Played episodes are archived one day after completion.

> **User:** Set archive after played to 1 hour.
> **Bot:** Auto-archive after playing set to 1 hour.

Result: Played episodes are archived after 1 hour.

> **User:** Don't auto-archive after playing.
> **Bot:** Auto-archive after playing disabled.

Result: Played episodes are kept indefinitely until manually archived.

> **User:** Archive played episodes after a week.
> **Bot:** Auto-archive after playing set to 7 days.

Result: Played episodes are archived after 7 days.

Edge cases:
- **Invalid duration:**
  > **User:** Archive after 2 seconds.
  > **Bot:** I can set it to immediately, 1 hour, 24 hours, 3 days, 1 week, or 2 weeks.
- **Already set to the requested value:**
  > **User:** Archive episodes after I finish them.
  > **Bot:** Auto-archive after playing is already set to immediately.

---

### Set archive inactive

> **User:** Archive inactive episodes after 2 weeks.
> **Bot:** Auto-archive inactive set to 2 weeks.

Result: Episodes that haven't been played for 2 weeks are auto-archived.

> **User:** Set archive inactive to never.
> **Bot:** Auto-archive inactive disabled.

Result: Inactive episodes are never auto-archived.

> **User:** Archive episodes I haven't listened to after a month.
> **Bot:** Auto-archive inactive set to 30 days.

Result: Inactive episodes are archived after 30 days.

> **User:** Don't auto-archive inactive episodes.
> **Bot:** Auto-archive inactive disabled.

Result: The inactive auto-archive rule is turned off.

Edge cases:
- **Invalid duration:**
  > **User:** Archive inactive after 1 day.
  > **Bot:** I can set it to never, 2 weeks, 4 weeks, 3 months, or 6 months.

---

### Include starred in auto-archive

> **User:** Include starred episodes in auto-archive.
> **Bot:** Starred episodes will now be auto-archived.

Result: Starred episodes are subject to auto-archive rules.

> **User:** Turn on auto-archive for starred.
> **Bot:** Starred episodes will now be auto-archived.

Result: Starred episodes can be auto-archived.

> **User:** Don't auto-archive starred episodes.
> **Bot:** Starred episodes are now excluded from auto-archive.

Result: Starred episodes are protected from auto-archive.

> **User:** Exclude starred from auto-archive.
> **Bot:** Starred episodes are now excluded from auto-archive.

Result: Starred episodes will not be auto-archived.

Edge cases:
- **Already in the requested state:**
  > **User:** Include starred episodes in auto-archive.
  > **Bot:** Starred episodes are already included in auto-archive.

---

## Notifications

### Toggle notification categories

> **User:** Turn on new episode notifications.
> **Bot:** New episode notifications enabled.

Result: Notifications are shown when new episodes are released.

> **User:** Turn off daily recommendations.
> **Bot:** Daily recommendation notifications disabled.

Result: Daily recommendation notifications are turned off.

> **User:** Enable all notifications.
> **Bot:** All notification categories enabled.

Result: Every notification type is turned on.

> **User:** Disable podcast notifications.
> **Bot:** Podcast notifications disabled.

Result: Podcast-related notifications are turned off.

Edge cases:
- **Notification permission not granted:**
  > **User:** Turn on new episode notifications.
  > **Bot:** I need notification permission first. Want me to open the system settings so you can enable it?
- **Already in the requested state:**
  > **User:** Turn on new episode notifications.
  > **Bot:** New episode notifications are already enabled.

---

### Per-podcast notifications

> **User:** Turn on notifications for Lex Fridman.
> **Bot:** Notifications enabled for Lex Fridman.

Result: A notification appears when new episodes of this podcast are released.

> **User:** Notify me about new Conan episodes.
> **Bot:** Notifications enabled for Conan O'Brien Needs a Friend.

Result: Notifications are turned on for the specified podcast.

> **User:** Turn off notifications for The Vergecast.
> **Bot:** Notifications disabled for The Vergecast.

Result: Notifications are no longer sent for this podcast.

> **User:** Stop notifying me about new Reply All episodes.
> **Bot:** Notifications disabled for Reply All.

Result: Notifications for this podcast are turned off.

Edge cases:
- **Podcast not found:**
  > **User:** Turn on notifications for Serial.
  > **Bot:** I couldn't find a podcast called "Serial" in your library.
- **Notification permission not granted:**
  > **User:** Turn on notifications for Lex Fridman.
  > **Bot:** I need notification permission first. Want me to open system settings?
- **No podcast specified:**
  > **User:** Turn on notifications.
  > **Bot:** Which podcast should I enable notifications for?

---

## Privacy

### Toggle analytics

> **User:** Turn off usage analytics.
> **Bot:** Usage analytics disabled.

Result: The app stops collecting anonymous usage data.

> **User:** Enable analytics.
> **Bot:** Usage analytics enabled.

Result: Anonymous usage data collection is turned back on.

> **User:** Stop tracking my usage.
> **Bot:** Usage analytics disabled.

Result: Usage analytics are turned off.

> **User:** Turn on anonymous analytics.
> **Bot:** Usage analytics enabled.

Result: Usage analytics are turned on.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn off usage analytics.
  > **Bot:** Usage analytics are already disabled.

---

### Toggle listening stats

> **User:** Turn off listening stats.
> **Bot:** Listening stats disabled.

Result: The app stops tracking listening statistics.

> **User:** Enable listening statistics.
> **Bot:** Listening stats enabled.

Result: Listening statistics tracking is turned on.

> **User:** Stop tracking how much I listen.
> **Bot:** Listening stats disabled.

Result: Listening stats are turned off.

> **User:** Turn my listening stats back on.
> **Bot:** Listening stats enabled.

Result: Listening stats tracking resumes.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn off listening stats.
  > **Bot:** Listening stats are already disabled.

---

### Toggle crash reports

> **User:** Turn off crash reports.
> **Bot:** Crash reports disabled.

Result: The app stops sending automatic crash reports.

> **User:** Enable crash reporting.
> **Bot:** Crash reports enabled.

Result: Automatic crash reports are turned on.

> **User:** Stop sending crash data.
> **Bot:** Crash reports disabled.

Result: Crash reports are no longer sent.

> **User:** Turn crash reports back on.
> **Bot:** Crash reports enabled.

Result: Crash reporting resumes.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn off crash reports.
  > **Bot:** Crash reports are already disabled.

---

### Toggle link account

> **User:** Turn off account linking.
> **Bot:** Account linking disabled.

Result: The app stops linking your account data across services.

> **User:** Enable account linking.
> **Bot:** Account linking enabled.

Result: Account data linking is turned on.

> **User:** Link my account.
> **Bot:** Account linking enabled.

Result: Account linking is activated.

> **User:** Unlink my account.
> **Bot:** Account linking disabled.

Result: Account linking is deactivated.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn off account linking.
  > **Bot:** Account linking is already disabled.

---

## Advanced

### Sync on metered network

> **User:** Turn on background sync on mobile data.
> **Bot:** Sync on metered network enabled.

Result: The app syncs data in the background even on mobile data.

> **User:** Allow sync on mobile data.
> **Bot:** Sync on metered network enabled.

Result: Background sync works on metered connections.

> **User:** Turn off sync on metered network.
> **Bot:** Sync on metered network disabled.

Result: Background sync is restricted to WiFi.

> **User:** Only sync on WiFi.
> **Bot:** Sync on metered network disabled.

Result: Sync is disabled on mobile data.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on background sync on mobile data.
  > **Bot:** Sync on metered network is already enabled.

---

### Prioritize seek accuracy

> **User:** Turn on seek accuracy.
> **Bot:** Seek accuracy mode enabled.

Result: Seeking uses a more precise algorithm at the cost of slightly higher CPU usage.

> **User:** Enable accurate seeking.
> **Bot:** Seek accuracy mode enabled.

Result: Seek accuracy is prioritized.

> **User:** Turn off seek accuracy.
> **Bot:** Seek accuracy mode disabled.

Result: Seeking uses the standard, faster algorithm.

> **User:** Use fast seeking instead.
> **Bot:** Seek accuracy mode disabled.

Result: Seek accuracy mode is turned off.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on seek accuracy.
  > **Bot:** Seek accuracy is already enabled.

---

### Cache entire playing episode

> **User:** Turn on full-episode cache.
> **Bot:** Full-episode cache enabled.

Result: The entire playing episode is cached locally for smoother playback.

> **User:** Cache the whole episode.
> **Bot:** Full-episode cache enabled.

Result: The full episode is cached.

> **User:** Turn off episode caching.
> **Bot:** Full-episode cache disabled.

Result: The episode is streamed without full caching.

> **User:** Stop caching the whole episode.
> **Bot:** Full-episode cache disabled.

Result: Full-episode caching is turned off.

Edge cases:
- **Storage too low for caching:**
  > **User:** Turn on full-episode cache.
  > **Bot:** Not enough storage to cache the full episode. Free up some space and try again.
- **Already in the requested state:**
  > **User:** Turn on full-episode cache.
  > **Bot:** Full-episode cache is already enabled.

---

## Storage and Cleanup

### View storage usage

> **User:** Show storage settings.
> **Bot:** Opening storage settings.

Result: Navigates to the storage settings screen showing usage breakdown.

> **User:** How much storage is the app using?
> **Bot:** Opening storage settings.

Result: Opens the storage view with usage details.

> **User:** Show me my storage usage.
> **Bot:** Opening storage settings.

Result: Navigates to the storage screen.

> **User:** Open storage.
> **Bot:** Opening storage settings.

Result: Displays the storage settings.

---

### Manual cleanup

> **User:** Clean up played episodes.
> **Bot:** Deleting all played episodes.

Result: All episodes marked as played are removed from the device.

> **User:** Delete my downloaded episodes that I've listened to.
> **Bot:** Deleting all played episodes.

Result: Played downloaded episodes are removed.

> **User:** Clean up unplayed downloads.
> **Bot:** Deleting all unplayed downloads.

Result: All downloaded but unplayed episodes are removed.

> **User:** Delete all in-progress episodes.
> **Bot:** Deleting all in-progress episodes.

Result: Partially played downloaded episodes are removed.

> **User:** Clean up everything except starred.
> **Bot:** Deleting all episodes except starred.

Result: All downloads except starred episodes are removed.

Edge cases:
- **Nothing to clean up:**
  > **User:** Clean up played episodes.
  > **Bot:** You don't have any played downloads to clean up.
- **User tries to delete starred without confirming:**
  > **User:** Delete all my downloads.
  > **Bot:** That will delete 12 episodes, including 3 starred ones. Are you sure?
    > **User:** Yes.
    > **Bot:** Deleted 12 episodes.

---

## Other Settings

### Export OPML

> **User:** Export my podcasts.
> **Bot:** Exporting your subscriptions as OPML.

Result: An OPML file is generated containing all podcast subscriptions.

> **User:** Export OPML.
> **Bot:** Exporting your subscriptions.

Result: The OPML export process begins.

> **User:** Save my podcast list as a file.
> **Bot:** Exporting your subscriptions as OPML.

Result: An OPML file is created with all subscriptions.

> **User:** Download my subscriptions file.
> **Bot:** Exporting your subscriptions as OPML.

Result: The OPML file is generated and saved.

Edge cases:
- **OPML export failure:**
  > **User:** Export my podcasts.
  > **Bot:** Couldn't export right now. Try again in a moment.
- **No podcasts to export:**
  > **User:** Export OPML.
  > **Bot:** You don't have any podcast subscriptions to export.

---

### View help

> **User:** Open help.
> **Bot:** Opening help.

Result: Navigates to the help and support screen.

> **User:** I need help with something.
> **Bot:** Opening help.

Result: Opens the help center.

> **User:** Show support.
> **Bot:** Opening help.

Result: Navigates to the help screen.

> **User:** Where can I get support?
> **Bot:** Opening help.

Result: Opens the help center.

---

### View about / licenses

> **User:** Show about.
> **Bot:** Opening about screen.

Result: Navigates to the about screen showing version and legal info.

> **User:** Open the licenses.
> **Bot:** Opening licenses.

Result: Displays the open-source licenses screen.

> **User:** Show app info.
> **Bot:** Opening about screen.

Result: Opens the about screen.

> **User:** View open source licenses.
> **Bot:** Opening licenses.

Result: Navigates to the licenses view.

---

### View / export logs

> **User:** Show the logs.
> **Bot:** Opening logs.

Result: Navigates to the logs screen showing recent app logs.

> **User:** Export my logs.
> **Bot:** Exporting logs.

Result: A log file is generated and shared.

> **User:** Open log viewer.
> **Bot:** Opening logs.

Result: Opens the log viewer screen.

> **User:** Send me the app logs.
> **Bot:** Exporting logs.

Result: Logs are exported for sharing.

Edge cases:
- **Log export failure:**
  > **User:** Export my logs.
  > **Bot:** Couldn't export logs right now. Try again in a moment.

---

### View service status

> **User:** Check service status.
> **Bot:** Opening service status.

Result: Navigates to the service status page showing server health.

> **User:** Is the service down?
> **Bot:** Opening service status.

Result: Opens the status screen for the user to check.

> **User:** Show server status.
> **Bot:** Opening service status.

Result: Displays the service status page.

> **User:** What's the system status?
> **Bot:** Opening service status.

Result: Navigates to the service status screen.

---

### Toggle beta features

> **User:** Turn on beta features.
> **Bot:** Beta features enabled.

Result: Experimental feature flags are activated.

> **User:** Enable beta mode.
> **Bot:** Beta features enabled.

Result: Beta features are turned on.

> **User:** Turn off beta features.
> **Bot:** Beta features disabled.

Result: Experimental features are deactivated.

> **User:** Disable the beta.
> **Bot:** Beta features disabled.

Result: Beta features are turned off.

Edge cases:
- **Already in the requested state:**
  > **User:** Turn on beta features.
  > **Bot:** Beta features are already enabled.

---

### Developer options

> **User:** Open developer settings.
> **Bot:** Opening developer options.

Result: Navigates to the developer settings screen.

> **User:** Show developer options.
> **Bot:** Opening developer options.

Result: Opens the developer options.

> **User:** Go to dev settings.
> **Bot:** Opening developer options.

Result: Navigates to the developer settings.

> **User:** Open debug settings.
> **Bot:** Opening developer options.

Result: Displays the developer options screen.
