# Search

---

## Open Search

> **User:** Search.
> **Bot:** What are you looking for?

Result: The search screen opens with the search bar focused.

> **User:** Open search.
> **Bot:** Go ahead, I'm listening.

Result: The search screen opens with the search bar focused.

> **User:** I want to find a podcast.
> **Bot:** What are you looking for?

Result: The search screen opens, ready for input.

---

## Type Query / Search by Voice

> **User:** Search for Reply All.
> **Bot:** Here are the results for "Reply All".

Result: Search results are displayed for the query "Reply All".

> **User:** Find me podcasts about astrophysics.
> **Bot:** Here's what I found for "astrophysics".

Result: Search results are shown for "astrophysics".

> **User:** Look up episode 200 of Planet Money.
> **Bot:** Here are the results for "Planet Money episode 200".

Result: Search results display matching episodes.

> **User:** Search for true crime podcasts.
> **Bot:** Here are the results for "true crime".

Result: Search results for "true crime" are shown.

- **No results found:**
  > **User:** Search for qwertyxyz podcast.
  > **Bot:** No results for "qwertyxyz". Want to try different words?

- **Empty query:**
  > **User:** Search for... uh...
  > **Bot:** No problem—just tell me what to search for when you're ready.

- **Network error during search:**
  > **User:** Find podcasts about cooking.
  > **Bot:** Something went wrong with the search. You might be offline.

---

## Tap / Select Suggestion

> **User:** Show me search suggestions.
> **Bot:** Here are some suggestions—what looks good?

Result: Search suggestions are displayed.

> **User:** Pick the first suggestion.
> **Bot:** Searching for "Reply All".

Result: The first suggestion is selected and its results are shown.

> **User:** I'll take the second one.
> **Bot:** Searching for "Stuff You Should Know".

Result: The second suggestion is selected and results load.

---

## View All Suggestions

> **User:** Show all suggestions.
> **Bot:** Here are all the suggestions.

Result: The full suggestion list is expanded.

> **User:** See more suggestions.
> **Bot:** Here's the full list.

Result: All suggestion groups are expanded.

---

## Filter Results

> **User:** Show only podcasts in the results.
> **Bot:** Here are the matching podcasts.

Result: The results filter switches to the Podcasts tab.

> **User:** Filter to episodes only.
> **Bot:** Here are the matching episodes.

Result: The results filter switches to the Episodes tab.

> **User:** Show me the top results.
> **Bot:** Here are the top results.

Result: The filter switches to Top Results.

> **User:** Just show me episodes about climate change.
> **Bot:** Here are matching episodes.

Result: The results filter is set to Episodes with the query applied.

---

## Subscribe from Results

> **User:** Subscribe to the first result.
> **Bot:** Subscribed to Reply All.

Result: The first podcast in the results is added to subscriptions.

> **User:** Follow that true crime podcast in the results.
> **Bot:** Subscribed to Crime Junkie.

Result: The specified podcast is subscribed.

> **User:** Add this one to my podcasts.
> **Bot:** Subscribed.

Result: The selected podcast from results is added to subscriptions.

- **Already subscribed:**
  > **User:** Subscribe to Reply All from the results.
  > **Bot:** You're already subscribed to Reply All.

---

## Tap Search Result (Navigate)

> **User:** Open the first result.
> **Bot:** Here's Reply All.

Result: Navigates to the detail page for the first search result.

> **User:** Show me the second episode.
> **Bot:** Here's "The Secret to Picking Locks".

Result: Opens the episode detail for the second result.

> **User:** Open that folder result.
> **Bot:** Here's the Tech News folder.

Result: Navigates to the folder matching the result.

---

## Search History — Re-run

> **User:** Show my search history.
> **Bot:** Here are your recent searches.

Result: The search history list is displayed.

> **User:** Search for Reply All again.
> **Bot:** Here are the results for "Reply All".

Result: The previous "Reply All" search is re-run and results are shown.

> **User:** Re-run my last search.
> **Bot:** Here are the results for "true crime".

Result: The most recent search query is executed again.

---

## Search History — Remove Entry

> **User:** Remove the last search from my history.
> **Bot:** Removed.

Result: The most recent search history entry is deleted.

> **User:** Delete the "Reply All" search from history.
> **Bot:** Removed "Reply All" from history.

Result: That specific history entry is removed.

---

## Search History — Clear All

> **User:** Clear my search history.
> **Bot:** Cleared all search history.

Result: All search history entries are removed.

> **User:** Delete all my past searches.
> **Bot:** History cleared.

Result: All search history is wiped.

- **No search history:**
  > **User:** Show my search history.
  > **Bot:** You don't have any search history yet.

---

## Dismiss Search

> **User:** Close search.
> **Bot:** Closed.

Result: The search screen is dismissed and the previous view is shown.

> **User:** Go back.
> **Bot:** Going back.

Result: The search screen is dismissed.

> **User:** Never mind.
> **Bot:** No problem.

Result: The search screen is dismissed.
