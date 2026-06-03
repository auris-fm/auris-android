# Settings

Only settings that users might reasonably want to change by voice are included. Visual-only preferences (app icon, widget colors) and rare developer options are excluded.

---

## Appearance

### Change Theme

Confirmation: implicit

> **User:** Turn on dark mode.
> **Bot:** [toggle] Dark theme.

> **User:** Switch to light theme.
> **Bot:** [toggle] Light theme.

> **User:** Use the ink theme.
> **Bot:** [toggle] Ink theme.

> **User:** Use classic dark.
> **Bot:** [toggle] Classic dark.

#### Edge cases
- **Already in that theme:**
  > **Bot:** [error]
- **Invalid theme:**
  > **Bot:** Available themes are light, dark, classic dark, and ink. Which one?

### Follow System Theme

Confirmation: implicit

> **User:** Match my phone's theme.
> **Bot:** [toggle] System theme.

> **User:** Use system theme.
> **Bot:** [toggle] System theme.

---

## Auto Download

### Auto-Download Up Next

Confirmation: implicit

> **User:** Auto-download my Up Next queue.
> **Bot:** [toggle] On.

> **User:** Stop auto-downloading Up Next.
> **Bot:** [toggle] Off.

### Auto-Download New Episodes

Confirmation: implicit

> **User:** Auto-download new episodes.
> **Bot:** [toggle] On.

> **User:** Turn off auto-download for new episodes.
> **Bot:** [toggle] Off.

### Auto-Download on Follow

Confirmation: implicit

> **User:** Download when I follow a show.
> **Bot:** [toggle] On.

> **User:** Stop downloading when I subscribe.
> **Bot:** [toggle] Off.

### WiFi Only

Confirmation: implicit

> **User:** Only download on WiFi.
> **Bot:** [toggle] On.

> **User:** Allow downloads on mobile data.
> **Bot:** [toggle] Off.

### Charging Only

Confirmation: implicit

> **User:** Only download while charging.
> **Bot:** [toggle] On.

> **User:** Download even when not charging.
> **Bot:** [toggle] Off.

### Per-Podcast Auto-Download

Confirmation: implicit

> **User:** Auto-download Conan.
> **Bot:** [toggle] On for Conan.

> **User:** Stop auto-downloading Reply All.
> **Bot:** [toggle] Off for Reply All.

### Stop All Downloads

Confirmation: implicit

> **User:** Stop all downloads.
> **Bot:** [confirm]

> **User:** Cancel downloads.
> **Bot:** [confirm]

#### Edge cases
- **No downloads active:**
  > **Bot:** [error]

### Clear Download Errors

Confirmation: implicit

> **User:** Clear download errors.
> **Bot:** [confirm]

#### Edge cases
- **No errors:**
  > **Bot:** [error]

### Set Download Limit

Confirmation: implicit

> **User:** Keep only the 5 most recent episodes per podcast.
> **Bot:** [confirm] 5 episodes.

> **User:** Keep 10 episodes per show.
> **Bot:** [confirm] 10 episodes.

---

## Headphone Controls

### Set Next-Track Action

Confirmation: implicit

> **User:** When I press next on headphones, skip forward.
> **Bot:** [confirm]

> **User:** Set headphone next to add bookmark.
> **Bot:** [confirm]

> **User:** Make the next button skip back.
> **Bot:** [confirm]

### Set Previous-Track Action

Confirmation: implicit

> **User:** Set headphone previous to skip back.
> **Bot:** [confirm]

> **User:** Make the back button skip forward.
> **Bot:** [confirm]

### Toggle Confirmation Sound

Confirmation: implicit

> **User:** Play a sound when I bookmark with headphones.
> **Bot:** [toggle] On.

> **User:** Turn off the bookmark chime.
> **Bot:** [toggle] Off.

---

## Auto Add to Up Next

### Enable Auto-Add

Confirmation: implicit

> **User:** Auto-add Lex Fridman to my queue.
> **Bot:** [toggle] On for Lex Fridman.

> **User:** Stop auto-adding Conan.
> **Bot:** [toggle] Off for Conan.

### Set Position

Confirmation: implicit

> **User:** Add new episodes to the top.
> **Bot:** [confirm]

> **User:** Add new episodes to the bottom.
> **Bot:** [confirm]

### Set Limit

Confirmation: implicit

> **User:** Keep only 3 episodes from this podcast in my queue.
> **Bot:** [confirm] 3 episodes.

---

## Auto Archive

### Archive After Playing

Confirmation: implicit

> **User:** Archive episodes after I finish them.
> **Bot:** [confirm]

> **User:** Archive played immediately.
> **Bot:** [confirm]

> **User:** Wait 24 hours before archiving played episodes.
> **Bot:** [confirm]

### Archive Inactive

Confirmation: implicit

> **User:** Archive if I haven't listened in 2 weeks.
> **Bot:** [confirm]

> **User:** Never auto-archive inactive episodes.
> **Bot:** [confirm]

### Include Starred

Confirmation: implicit

> **User:** Include starred episodes in auto-archive.
> **Bot:** [toggle] On.

> **User:** Don't archive starred episodes.
> **Bot:** [toggle] Off.

---

## Notifications

### Toggle Notifications

Confirmation: implicit

> **User:** Turn on new episode notifications.
> **Bot:** [toggle] On.

> **User:** Turn off daily recommendations.
> **Bot:** [toggle] Off.

### Per-Podcast Notifications

Confirmation: implicit

> **User:** Notify me about new Conan episodes.
> **Bot:** [toggle] On for Conan.

> **User:** Stop notifying about Reply All.
> **Bot:** [toggle] Off for Reply All.

---

## Storage

### Manual Cleanup

Confirmation: explicit

> **User:** Clean up played episodes.
> **Bot:** Delete 14 played episodes? 1.2 GB freed.
> **User:** Yes.
> **Bot:** Cleaned up 14 episodes.

> **User:** Delete all played downloads.
> **Bot:** Delete 14 downloaded episodes? 1.2 GB freed.
> **User:** Yes.
> **Bot:** Cleaned up 14 episodes.

#### Edge cases
- **Nothing to clean up:**
  > **Bot:** [error]

### Export OPML

Confirmation: implicit

> **User:** Export my subscriptions.
> **Bot:** [confirm]

> **User:** Export OPML.
> **Bot:** [confirm]

#### Edge cases
- **Export failure:**
  > **Bot:** Couldn't export. Check your storage and try again.

---

## Settings Queries

### Query Auto-Archive

> **User:** What's my auto-archive setting?
> **Bot:** Archive after playing: after 24 hours. Inactive after: 2 weeks. Starred: excluded.

### Query Auto-Download

> **User:** Am I auto-downloading?
> **Bot:** Auto-download is on for new episodes and Up Next. WiFi-only: on. Charging-only: off.

### Query Storage

> **User:** How much storage is the app using?
> **Bot:** App is using 3.2 GB. Downloads: 2.8 GB. Cache: 400 MB.

> **User:** How much space do downloads take?
> **Bot:** 2.8 GB for 38 downloaded episodes.
