# Miscellaneous

Voice dialog reference for all actions in the Miscellaneous category.

---

## Referrals

### Send guest pass

> **User:** Share my referral link.
> **Bot:** Opening share for your guest pass.

Result: Opens the share sheet with the referral link.

> **User:** Send a guest pass.
> **Bot:** Opening share for your guest pass.

Result: Opens the share dialog with the referral link.

> **User:** Invite a friend.
> **Bot:** Opening share for your guest pass.

Result: Opens the share sheet with the referral invitation.

> **User:** Share my referral.
> **Bot:** Opening share.

Result: Opens the share dialog with the referral link.

Edge cases:
- **No referral link available:**
  > **User:** Share my referral link.
  > **Bot:** You don't have a referral link yet. It may not be available in your region.
- **Sharing failure:**
  > **User:** Send a guest pass.
  > **Bot:** Couldn't share right now. Try again in a moment.

---

### Claim guest pass

> **User:** Claim this guest pass.
> **Bot:** Activating your guest pass.

Result: Starts the guest pass activation and billing flow.

> **User:** Activate the referral.
> **Bot:** Activating your guest pass.

Result: Initiates the referral redemption process.

> **User:** Redeem my guest pass.
> **Bot:** Activating your guest pass.

Result: Starts the activation flow for the guest pass.

Edge cases:
- **Referral already claimed:**
  > **User:** Claim this guest pass.
  > **Bot:** This guest pass has already been claimed.
- **Invalid referral link:**
  > **User:** Activate the referral.
  > **Bot:** This referral link isn't valid.
- **Referral expired:**
  > **User:** Redeem my guest pass.
  > **Bot:** This guest pass has expired.
- **Own referral (self-referral):**
  > **User:** Claim this guest pass.
  > **Bot:** You can't claim your own guest pass.

---

## End of Year

### View stories

> **User:** Show my End of Year.
> **Bot:** Opening your year in review.

Result: Opens the End of Year stories viewer from the profile card.

> **User:** Show my year in review.
> **Bot:** Opening your year in review.

Result: Displays the End of Year stories.

> **User:** Play my end of year stories.
> **Bot:** Opening your year in review.

Result: Opens the End of Year stories carousel.

> **User:** How did I listen this year?
> **Bot:** Opening your year in review.

Result: Opens the End of Year summary stories.

Edge cases:
- **End of Year not available:**
  > **User:** Show my End of Year.
  > **Bot:** Your year in review isn't available yet. Check back closer to the end of the year.
- **Not enough listening history:**
  > **User:** Show my year in review.
  > **Bot:** You haven't listened enough this year to generate a review. Keep listening!

---

### Next / previous story

> **User:** Next story.
> **Bot:** Showing next.

Result: Advances to the next story in the carousel.

> **User:** Go to the next one.
> **Bot:** Showing next.

Result: Swipes to the next story.

> **User:** Previous story.
> **Bot:** Going back.

Result: Returns to the previous story.

> **User:** Go back.
> **Bot:** Going back.

Result: Displays the previous story.

Edge cases:
- **Already on the last story:**
  > **User:** Next story.
  > **Bot:** That was the last story. Want to replay?
- **Already on the first story:**
  > **User:** Previous story.
  > **Bot:** This is the first story.

---

### Share story

> **User:** Share this story.
> **Bot:** Opening share for this story.

Result: Opens the share sheet with the current story image or card.

> **User:** Share my listening stats.
> **Bot:** Opening share for this story.

Result: Opens the share dialog for the current story.

> **User:** Send this story to someone.
> **Bot:** Opening share.

Result: Opens the share sheet with the story card.

Edge cases:
- **Sharing failure:**
  > **User:** Share this story.
  > **Bot:** Couldn't share right now. Try again in a moment.

---

### Replay stories

> **User:** Replay the stories.
> **Bot:** Replaying from the beginning.

Result: Restarts the End of Year story carousel from the first story.

> **User:** Start over.
> **Bot:** Replaying from the beginning.

Result: Resets and replays all stories.

> **User:** Watch them again.
> **Bot:** Replaying from the beginning.

Result: Restarts the story carousel.

Edge cases:
- **Stories not loaded:**
  > **User:** Replay the stories.
  > **Bot:** Your stories haven't loaded yet. Try again in a moment.

---

## Widgets

### Play / Pause

> **User:** Play from the widget.
> **Bot:** Playing.

Result: Taps play on the home screen widget and resumes playback.

> **User:** Pause from the widget.
> **Bot:** Paused.

Result: Taps pause on the widget and pauses playback.

> **User:** Toggle playback on the widget.
> **Bot:** Playing.

Result: Taps the play/pause button on the widget.

Edge cases:
- **Nothing to play:**
  > **User:** Play from the widget.
  > **Bot:** Nothing to play. Open the app and pick an episode first.
- **Widget not configured:**
  > **User:** Play from the widget.
  > **Bot:** No widget is set up. Add the Pocket Casts widget to your home screen first.

---

### Skip forward (widget)

> **User:** Skip forward on the widget.
> **Bot:** Skipped ahead 30 seconds.

Result: Taps skip-forward on the widget.

> **User:** Fast forward from the widget.
> **Bot:** Skipped ahead 30 seconds.

Result: Triggers skip-forward from the home screen widget.

Edge cases:
- **Nothing playing:**
  > **User:** Skip forward on the widget.
  > **Bot:** Nothing is playing right now.

---

### Skip backward (widget)

> **User:** Skip back on the widget.
> **Bot:** Went back 15 seconds.

Result: Taps skip-backward on the widget.

> **User:** Rewind from the widget.
> **Bot:** Went back 15 seconds.

Result: Triggers skip-backward from the home screen widget.

Edge cases:
- **Nothing playing:**
  > **User:** Skip back on the widget.
  > **Bot:** Nothing is playing right now.

---

### Open app (widget)

> **User:** Open the app from the widget.
> **Bot:** Opening Pocket Casts.

Result: Taps the widget artwork or background and opens the app.

> **User:** Open Pocket Casts.
> **Bot:** Opening.

Result: Launches the app from the widget.

Edge cases:
- **App not installed:**
  > **User:** Open Pocket Casts.
  > **Bot:** The app isn't installed. Install it from the Play Store.

---

## Chromecast / Casting

### Open cast picker

> **User:** Open the cast picker.
> **Bot:** Looking for devices.

Result: Opens the media-route dialog showing available cast devices.

> **User:** Show me cast devices.
> **Bot:** Looking for devices.

Result: Displays the cast device picker.

> **User:** Cast picker.
> **Bot:** Looking for devices.

Result: Opens the media-route dialog.

Edge cases:
- **No cast devices found:**
  > **User:** Open the cast picker.
  > **Bot:** No devices found. Make sure your device is on the same Wi-Fi network.

---

### Connect to device

> **User:** Connect to the Living Room speaker.
> **Bot:** Connecting to Living Room speaker.

Result: Initiates a connection to the named Chromecast or cast device.

> **User:** Cast to the bedroom TV.
> **Bot:** Connecting to bedroom TV.

Result: Starts casting to the specified device.

> **User:** Play on my Chromecast.
> **Bot:** Connecting to your Chromecast.

Result: Initiates connection to the Chromecast device.

> **User:** Cast this.
> **Bot:** Which device? I found: Living Room speaker, Bedroom TV.
  > **User:** Living Room speaker.
  > **Bot:** Connecting to Living Room speaker.

Result: Lists available devices and connects to the chosen one.

Edge cases:
- **Device not found:**
  > **User:** Connect to the kitchen speaker.
  > **Bot:** I couldn't find "kitchen speaker." Available devices: Living Room speaker, Bedroom TV.
- **Cast connection failure:**
  > **User:** Connect to the Living Room speaker.
  > **Bot:** Couldn't connect to Living Room speaker. Make sure it's powered on and on the same Wi-Fi network.
- **Already connected to that device:**
  > **User:** Cast to the Living Room speaker.
  > **Bot:** Already connected to Living Room speaker.

---

### Disconnect

> **User:** Disconnect from the speaker.
> **Bot:** Disconnected from Living Room speaker.

Result: Ends the cast session and stops casting.

> **User:** Stop casting.
> **Bot:** Disconnected.

Result: Disconnects from the active cast device.

> **User:** Disconnect.
> **Bot:** Disconnected from Living Room speaker.

Result: Ends the current cast session.

Edge cases:
- **Not currently casting:**
  > **User:** Stop casting.
  > **Bot:** You're not connected to any device.

---

## Tasker Plugin (Automation)

### Add to Up Next

> **User:** Add the latest episode of "The Daily" to Up Next.
> **Bot:** Added the latest episode of "The Daily" to Up Next.

Result: Adds the specified episode to the Up Next queue via Tasker.

> **User:** Queue the newest episode from my favorites.
> **Bot:** Added the latest episode of each favorite to Up Next.

Result: Adds the latest episodes from favorited podcasts to the queue.

> **User:** Add this episode to the queue.
> **Bot:** Added to Up Next.

Result: Adds the current episode to Up Next.

Edge cases:
- **Podcast not found:**
  > **User:** Add the latest episode of "Morning Show" to Up Next.
  > **Bot:** I couldn't find a podcast called "Morning Show."
- **No new episodes available:**
  > **User:** Queue the latest episode of "The Daily."
  > **Bot:** No new episodes available for "The Daily."
- **Tasker not installed:**
  > **User:** Add to Up Next via Tasker.
  > **Bot:** Tasker isn't installed. Install Tasker to use automation features.

---

### Control Playback

> **User:** Play via Tasker.
> **Bot:** Playing.

Result: Triggers playback via Tasker automation.

> **User:** Pause via Tasker.
> **Bot:** Paused.

Result: Pauses playback via Tasker.

> **User:** Skip forward via Tasker.
> **Bot:** Skipped ahead 30 seconds.

Result: Skips forward via Tasker automation.

> **User:** Stop playback via Tasker.
> **Bot:** Stopped.

Result: Stops playback via Tasker.

Edge cases:
- **Nothing playing (pause/stop/skip):**
  > **User:** Pause via Tasker.
  > **Bot:** Nothing is playing right now.
- **Tasker not installed:**
  > **User:** Play via Tasker.
  > **Bot:** Tasker isn't installed. Install Tasker to use automation features.

---

### Play Playlist

> **User:** Play my "Morning Commute" playlist via Tasker.
> **Bot:** Playing "Morning Commute" playlist.

Result: Starts playing the named playlist via Tasker automation.

> **User:** Start the "Workout" playlist.
> **Bot:** Playing "Workout" playlist.

Result: Starts the specified playlist.

> **User:** Play my favorites playlist.
> **Bot:** Playing your favorites playlist.

Result: Starts the favorites playlist.

Edge cases:
- **Playlist not found:**
  > **User:** Play my "News" playlist.
  > **Bot:** I couldn't find a playlist called "News."
- **Playlist is empty:**
  > **User:** Play my "Weekend" playlist.
  > **Bot:** Your "Weekend" playlist is empty. Add episodes to it first.
- **Tasker not installed:**
  > **User:** Play playlist via Tasker.
  > **Bot:** Tasker isn't installed. Install Tasker to use automation features.

---

### Query Filter Episodes

> **User:** What episodes match my "New This Week" filter?
> **Bot:** Your "New This Week" filter has 12 episodes.

Result: Returns the list of episodes matching the specified filter.

> **User:** Show me episodes from my "Unplayed" filter.
> **Bot:** Your "Unplayed" filter has 5 episodes.

Result: Returns episodes matching the unplayed filter.

> **User:** How many episodes match "Tech News"?
> **Bot:** Your "Tech News" filter has 8 episodes.

Result: Returns the count and list of matching episodes.

Edge cases:
- **Filter not found:**
  > **User:** What episodes match my "Science" filter?
  > **Bot:** I couldn't find a filter called "Science."
- **No matching episodes:**
  > **User:** Show me episodes from my "Completed" filter.
  > **Bot:** No episodes match your "Completed" filter right now.
- **No filters created:**
  > **User:** What episodes match my filters?
  > **Bot:** You haven't created any filters yet.

---

### Query Filters

> **User:** List my filters.
> **Bot:** You have 3 filters: New This Week, Unplayed, Tech News.

Result: Returns a list of all user-created filters.

> **User:** What filters do I have?
> **Bot:** You have 3 filters: New This Week, Unplayed, Tech News.

Result: Lists all filter names.

> **User:** Show my filters.
> **Bot:** You have 3 filters: New This Week, Unplayed, Tech News.

Result: Returns the list of filters.

Edge cases:
- **No filters created:**
  > **User:** List my filters.
  > **Bot:** You don't have any filters yet. Create one in the app.

---

### Query Podcast Episodes

> **User:** What are the latest episodes of "The Daily"?
> **Bot:** The latest 3 episodes of "The Daily" are: "Monday Briefing," "The Climate Debate," "Weekend Recap."

Result: Returns recent episodes for the named podcast.

> **User:** Show me episodes from "Serial."
> **Bot:** The latest 3 episodes of "Serial" are: "Episode 1," "Episode 2," "Episode 3."

Result: Returns the episode list for the podcast.

> **User:** How many episodes does "99% Invisible" have?
> **Bot:** "99% Invisible" has 500 episodes.

Result: Returns the episode count for the podcast.

Edge cases:
- **Podcast not found:**
  > **User:** What are the latest episodes of "Morning Show"?
  > **Bot:** I couldn't find a podcast called "Morning Show."
- **No episodes available:**
  > **User:** Show me episodes from "The Daily."
  > **Bot:** No episodes are available for "The Daily" right now.

---

### Query Podcasts

> **User:** List my podcasts.
> **Bot:** You're subscribed to 15 podcasts, including "The Daily," "Serial," "99% Invisible."

Result: Returns a list of all subscribed podcasts.

> **User:** What podcasts am I subscribed to?
> **Bot:** You have 15 podcasts in your library.

Result: Returns the count and names of subscribed podcasts.

> **User:** Show my podcasts.
> **Bot:** You have 15 podcasts in your library, including "The Daily," "Serial," "99% Invisible."

Result: Lists the user's podcasts.

Edge cases:
- **No subscriptions:**
  > **User:** List my podcasts.
  > **Bot:** You haven't subscribed to any podcasts yet. Browse or search to find some.

---

### Query Up Next

> **User:** What's in my Up Next queue?
> **Bot:** Up Next has 4 episodes, starting with "The Daily: Monday Briefing."

Result: Returns the current Up Next queue contents.

> **User:** Show my queue.
> **Bot:** Up Next has 4 episodes, starting with "The Daily: Monday Briefing."

Result: Lists the episodes in the Up Next queue.

> **User:** What's coming up next?
> > **Bot:** Up next is "Serial: Episode 2," then "99% Invisible: The Shipping Container."

Result: Returns the next episodes in the queue.

Edge cases:
- **Queue is empty:**
  > **User:** What's in my Up Next queue?
  > **Bot:** Your Up Next queue is empty. Add an episode to get started.
- **No matching results for queries:**
  > **User:** Show my queue.
  > **Bot:** Your Up Next queue is empty. Add an episode to get started.
