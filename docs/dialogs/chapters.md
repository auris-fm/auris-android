# Chapters

Chapter navigation is a natural voice interaction. Jumping to a chapter is self-confirming — the audio changes. Queries about chapter structure get spoken responses.

---

## Chapter Navigation

### Play Chapter

Confirmation: implicit (silent — audio jumps to chapter)

> **User:** Play chapter 3.
Result: Audio jumps to chapter 3.

> **User:** Skip to the interview.
Result: Audio jumps to matching chapter.

> **User:** Go to chapter "Q and A".
Result: Audio jumps to chapter 6.

#### Edge cases
- **No chapters:**
  > **Bot:** [error]
- **Chapter not found:**
  > **Bot:** No chapter called "outro". Did you mean *Outtakes* or *Final Thoughts*?
- **Number out of range:**
  > **Bot:** Only 6 chapters. Which one?

### Next Chapter

Confirmation: implicit (silent — audio jumps)

> **User:** Next chapter.
Result: Audio jumps to next chapter.

> **User:** Skip to the next chapter.
Result: Audio jumps to next chapter.

#### Edge cases
- **On last chapter:**
  > **Bot:** [error]

### Previous Chapter

Confirmation: implicit (silent — audio jumps)

> **User:** Previous chapter.
Result: Audio jumps to previous chapter.

> **User:** Go back a chapter.
Result: Audio jumps to previous chapter.

#### Edge cases
- **On first chapter:**
  > **Bot:** [error]

### Open Chapter Link

Confirmation: implicit (earcon — opens URL)

> **User:** Open the link in chapter 3.
> **Bot:** [confirm]
Result: Opens URL associated with chapter 3.

> **User:** Open the link in the resources chapter.
> **Bot:** [confirm]
Result: Opens URL.

#### Edge cases
- **No link:**
  > **Bot:** [error]
- **No chapters:**
  > **Bot:** [error]

---

## Chapter Queries

### List Chapters

> **User:** What chapters are in this episode?
> **Bot:** 6 chapters. 1: *Intro*. 2: *The Setup*. 3: *The Investigation*. 4: *The Interview*. 5: *The Verdict*. 6: *Outtakes*.

> **User:** List the chapters.
> **Bot:** 1: *Intro* at 0:00. 2: *The Setup* at 3:15. 3: *The Investigation* at 12:40. 4: *The Interview* at 25:00. 5: *The Verdict* at 38:20. 6: *Outtakes* at 45:00.

#### Edge cases
- **No chapters:**
  > **Bot:** No chapters in this episode.

### Current Chapter

> **User:** What chapter am I on?
> **Bot:** Chapter 3: *The Investigation*. 8 minutes remaining in this chapter.

> **User:** What's this chapter called?
> **Bot:** Chapter 3: *The Investigation*.

### Chapter Count

> **User:** How many chapters does this have?
> **Bot:** 6 chapters.

> **User:** Is this episode chaptered?
> **Bot:** Yes, 6 chapters. First one is *Intro*.

### Next Chapter Info

> **User:** What's next?
> **Bot:** Chapter 4: *The Interview*.

> **User:** What chapter is coming up?
> **Bot:** Chapter 4: *The Interview*.

#### Edge cases
- **On last chapter:**
  > **Bot:** This is the last chapter.
