# Discover / Browse

---

## Browse Featured / Trending

> **User:** Show me what's trending.
> **Bot:** Here's what's trending right now.

Result: Scrolls to and highlights the trending section of the discover feed.

> **User:** What's popular in podcasts?
> **Bot:** Here are the top podcasts right now.

Result: Navigates to the popular/trending section.

> **User:** Show featured podcasts.
> **Bot:** Here's what's featured this week.

Result: Scrolls to the featured section of the discover feed.

> **User:** What's new and noteworthy?
> **Bot:** Here are some new picks for you.

Result: Shows the "New & Noteworthy" section.

---

## Change Region

> **User:** Switch to the UK charts.
> **Bot:** Switched to United Kingdom.

Result: The discover region changes to the UK.

> **User:** Show me podcasts from Australia.
> **Bot:** Switched to Australia.

Result: The discover region changes to Australia.

> **User:** Change region to Canada.
> **Bot:** Switched to Canada.

Result: The discover region changes to Canada.

- **Region not available:**
  > **User:** Show me podcasts from Antarctica.
  > **Bot:** That region isn't available. Want to pick from the list?

---

## Load Category

> **User:** Show me comedy podcasts.
> **Bot:** Here are comedy podcasts.

Result: Opens the Comedy category.

> **User:** Browse true crime.
> **Bot:** Here's true crime.

Result: Opens the True Crime category.

> **User:** What's good in tech podcasts?
> **Bot:** Here are the top tech podcasts.

Result: Opens the Technology category.

> **User:** Show me science categories.
> **Bot:** Here are science podcasts.

Result: Opens the Science category.

- **Category empty:**
  > **Bot:** Nothing in that category right now. Try another one?

---

## Open Podcast Detail

> **User:** Open the page for Reply All.
> **Bot:** Here's Reply All.

Result: Navigates to the Reply All podcast detail screen.

> **User:** Show me Stuff You Should Know.
> **Bot:** Here's Stuff You Should Know.

Result: Opens the podcast detail view.

> **User:** Take me to the Planet Money podcast.
> **Bot:** Here's Planet Money.

Result: Navigates to the Planet Money detail screen.

- **Podcast not found in catalog:**
  > **User:** Open the page for Obscure Podcast XYZ.
  > **Bot:** I couldn't find that podcast. Want to search for it instead?

---

## Play Episode (from Discover)

> **User:** Play that episode.
> **Bot:** Playing "The Secret History of Your Password".

Result: The highlighted/tapped episode begins playing.

> **User:** Play the latest episode of Reply All.
> **Bot:** Playing the latest Reply All.

Result: The most recent episode of Reply All starts playing.

> **User:** Start this one.
> **Bot:** Playing.

Result: The selected episode begins playing.

---

## Subscribe to Podcast

> **User:** Subscribe to Reply All.
> **Bot:** Subscribed to Reply All.

Result: Reply All is added to the user's subscriptions.

> **User:** Follow Stuff You Should Know.
> **Bot:** Subscribed to Stuff You Should Know.

Result: The podcast is added to subscriptions.

> **User:** Add Planet Money to my podcasts.
> **Bot:** Subscribed to Planet Money.

Result: The podcast is added to subscriptions.

> **User:** I want to subscribe to that tech podcast.
> **Bot:** Which one? There are a few in the results.
  > **User:** The first one.
  > **Bot:** Subscribed to a6z Podcast.

Result: The specified podcast is subscribed.

- **Already subscribed:**
  > **User:** Subscribe to Reply All.
  > **Bot:** You're already subscribed to Reply All.
- **Failed subscription (network error):**
  > **Bot:** Couldn't subscribe right now. I'll retry when you're back online.

---

## Pull to Refresh

> **User:** Refresh the discover page.
> **Bot:** Refreshed.

Result: The discover feed reloads with the latest content.

> **User:** Reload this page.
> **Bot:** Refreshed.

Result: The current discover view is reloaded.

- **No network connection:**
  > **Bot:** Can't refresh right now—you're offline.

---

## Share Content

> **User:** Share this podcast.
> **Bot:** Here's the share link for Reply All.

Result: The system share sheet opens with a link to the podcast.

> **User:** Share this episode.
> **Bot:** Here's the share link for this episode.

Result: The system share sheet opens with a link to the episode.

> **User:** Send this to a friend.
> **Bot:** Here's the share link.

Result: The system share sheet opens.

- **Share failure:**
  > **Bot:** Couldn't generate a share link right now. Try again in a moment.

---

## No Network Connection (General)

- Most discover actions require network. When offline:
  > **User:** Show me what's trending.
  > **Bot:** You're offline. I can show cached content, but some things may be out of date.

- If no cached content is available:
  > **Bot:** You're offline and there's nothing cached yet. Try again when you're connected.
