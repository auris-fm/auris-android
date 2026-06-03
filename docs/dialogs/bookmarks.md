# Bookmarks

Bookmarks are ideal for voice — users save moments hands-free while listening and recall them later by name.

---

## Bookmark Actions

### Create Bookmark

Confirmation: implicit

> **User:** Bookmark this.
> **Bot:** [confirm] Bookmarked at 14:22.
Result: Bookmark created at current position.

> **User:** Bookmark this as "the funny bit".
> **Bot:** [confirm] Bookmarked as "the funny bit" at 14:22.
Result: Named bookmark created.

> **User:** Save my spot.
> **Bot:** [confirm] Bookmarked at 14:22.
Result: Bookmark created.

> **User:** Mark this moment.
> **Bot:** [confirm] Bookmarked at 14:22.
Result: Bookmark created.

#### Edge cases
- **Nothing playing:**
  > **Bot:** [error]

### Edit Bookmark Title

Confirmation: implicit

> **User:** Rename this bookmark "the interview".
> **Bot:** [confirm] Renamed.

> **User:** Call this bookmark "the key quote".
> **Bot:** [confirm] Renamed.

> **User:** Rename the bookmark.
> **Bot:** Which one? You have 3 bookmarks for this episode: "the funny bit", "chapter 5 recap", "ending".
  > **User:** The second one.
  > **Bot:** What should I call it?
  > **User:** "The turning point".
  > **Bot:** [confirm] Renamed.

#### Edge cases
- **No bookmarks for episode:**
  > **Bot:** [error]

### Play Bookmark

Confirmation: implicit

> **User:** Play my bookmark "the funny bit".
Result: Seeks to bookmark position.

> **User:** Jump to "the interview".
Result: Seeks to bookmark.

> **User:** Go to bookmark 3.
Result: Seeks to bookmark by number.

#### Edge cases
- **Bookmark not found:**
  > **Bot:** No bookmark called "outro". Did you mean "the ending"?
- **No bookmarks:**
  > **Bot:** [error]
- **Ambiguous name:**
  > **Bot:** I found 2 bookmarks matching "chapter": "chapter 3 start" and "chapter 5 recap". Which one?

### Delete Bookmark

Confirmation: implicit

> **User:** Delete bookmark "the funny bit".
> **Bot:** [confirm]

> **User:** Remove that bookmark.
> **Bot:** [confirm]

#### Edge cases
- **Bookmark not found:**
  > **Bot:** No bookmark with that name. Your bookmarks are: [list].

### Delete All Bookmarks for Episode

Confirmation: explicit

> **User:** Delete all my bookmarks for this episode.
> **Bot:** Delete 4 bookmarks for this episode?
> **User:** Yes.
> **Bot:** Deleted 4 bookmarks.

#### Edge cases
- **No bookmarks:**
  > **Bot:** [error]

---

## Bookmark Queries

### List Bookmarks

Confirmation: implicit (read-only)

> **User:** What bookmarks do I have for this episode?
> **Bot:** 3 bookmarks: "the funny bit" at 14:22, "the interview" at 25:00, "chapter 5 recap" at 32:10.

#### Edge cases
- **No bookmarks:**
  > **Bot:** [error]

### Bookmark Count

Confirmation: implicit (read-only)

> **User:** How many bookmarks do I have?
> **Bot:** 3 bookmarks for this episode, 12 total across all episodes.

### Nearby Bookmarks

Confirmation: implicit (read-only)

> **User:** Any bookmarks near here?
> **Bot:** You have "the interview" about 30 seconds ahead.

> **User:** Are there any bookmarks close by?
> **Bot:** "The funny bit" is about 2 minutes behind you.

#### Edge cases
- **No nearby bookmarks:**
  > **Bot:** No bookmarks nearby. The closest one is "the funny bit" about 8 minutes behind.
- **No bookmarks at all:**
  > **Bot:** [error]
