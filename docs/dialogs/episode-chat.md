# Episode Chat (AI Assistant)

Voice dialog reference for all actions in the Episode Chat (AI Assistant) category.

---

## Open chat

> **User:** Open episode chat.
> **Bot:** Opening episode chat.

Result: Opens the AI chat panel for the current episode.

> **User:** Let me ask a question about this episode.
> **Bot:** Opening episode chat.

Result: Opens the episode chat view.

> **User:** Chat about this episode.
> **Bot:** Opening chat.

Result: Opens the AI chat for the current episode.

> **User:** Talk to me about this episode.
> **Bot:** Opening episode chat.

Result: Opens the episode chat panel.

Edge cases:
- **Not a Plus subscriber:**
  > **User:** Open episode chat.
  > **Bot:** Episode chat is a Plus feature. Want to start a free trial?
- **Chat unavailable for this episode:**
  > **User:** Chat about this episode.
  > **Bot:** Chat isn't available for this episode yet.
- **Not on an episode detail screen:**
  > **User:** Open episode chat.
  > **Bot:** Open an episode first, then you can start a chat.

---

## Send message

> **User:** What did they say about climate policy?
> **Bot:** They discussed three main climate proposals, starting around the 12-minute mark. The host pushed back on the feasibility of the carbon tax plan.

Result: Sends the question to the AI and displays the response in the chat.

> **User:** Summarize the key points.
> **Bot:** The main topics are the housing crisis, the new transit plan, and the upcoming election. The guest argues that housing reform is the most urgent priority.

Result: Sends a summary request and displays the AI's response.

> **User:** Who is the guest on this episode?
> **Bot:** The guest is Dr. Sarah Chen, an urban planning researcher at MIT.

Result: Sends the question and shows the answer in chat.

> **User:** Does the host agree with the guest's argument?
> **Bot:** The host seems skeptical at first but comes around by the end of the discussion, calling it "the most convincing case I've heard."

Result: Sends the question and displays the AI's analysis.

Edge cases:
- **Network error sending message:**
  > **User:** What is this episode about?
  > **Bot:** Couldn't send your message. Check your connection and try again.
- **AI response timeout:**
  > **User:** Summarize the episode.
  > **Bot:** Taking longer than expected. Your message will appear when the response is ready.
- **Empty message:**
  > **User:** (sends empty message)
  > **Bot:** Type a question about the episode to get started.

---

## Play / stop quote

> **User:** Play that quote.
> **Bot:** Playing from 12:34.

Result: Seeks to the quoted timestamp and starts playback. Previous position is saved for restoration.

> **User:** Play the clip about climate policy.
> **Bot:** Playing from 15:22.

Result: Seeks to the relevant timestamp in the AI response and plays.

> **User:** Stop the quote.
> **Bot:** Stopped. Back to where you were at 8:45.

Result: Stops quote playback and restores the previous playback position.

> **User:** Go back to where I was.
> **Bot:** Jumped back to 8:45.

Result: Restores the saved playback position from before the quote started.

Edge cases:
- **No quote to play:**
  > **User:** Play that quote.
  > **Bot:** Tap a timestamp in the chat response to play a quote.
- **No previous position to restore:**
  > **User:** Stop the quote.
  > **Bot:** Stopped playback.
- **Quote timestamp is past the end of the episode:**
  > **User:** Play that quote.
  > **Bot:** That timestamp is past the end of the episode.

---

## Retry failed message

> **User:** Retry the last message.
> **Bot:** Retrying.

Result: Resends the failed message to the AI.

> **User:** Try sending that again.
> **Bot:** Retrying.

Result: Re-sends the failed message and displays the response.

> **User:** Resend my question.
> **Bot:** Retrying.

Result: Attempts to resend the most recent failed message.

Edge cases:
- **No failed messages to retry:**
  > **User:** Retry the last message.
  > **Bot:** No failed messages to retry.
- **Retry also fails:**
  > **User:** Retry the last message.
  > **Bot:** Still couldn't send. Check your connection and try again.

---

## Clear chat

> **User:** Clear the chat.
> **Bot:** Chat cleared.

Result: Deletes all messages in the current episode chat session.

> **User:** Start over with the chat.
> **Bot:** Chat cleared.

Result: Clears all messages and resets the chat view.

> **User:** Delete the chat history.
> **Bot:** Chat cleared.

Result: Removes all chat messages for the current episode.

> **User:** Wipe this conversation.
> **Bot:** Chat cleared.

Result: Clears the chat session.

Edge cases:
- **Chat is already empty:**
  > **User:** Clear the chat.
  > **Bot:** The chat is already empty.

---

## Close chat

> **User:** Close the chat.
> **Bot:** Closing chat.

Result: Dismisses the episode chat panel and returns to the episode detail view.

> **User:** Go back to the episode.
> **Bot:** Closing chat.

Result: Closes the chat and returns to the episode detail screen.

> **User:** Never mind, close this.
> **Bot:** Closing chat.

Result: Dismisses the chat panel.

Edge cases:
- **No chat open:**
  > **User:** Close the chat.
  > **Bot:** No chat is open right now.

---

## Open paywall (non-Plus)

> **User:** I want to use episode chat.
> **Bot:** Episode chat is a Plus feature. Here's what you get with Plus.

Result: Displays the paywall dialog showing Plus benefits and subscription options.

> **User:** Try episode chat.
> **Bot:** Episode chat is a Plus feature. Here's what you get with Plus.

Result: Shows the paywall dialog instead of the chat interface.

Edge cases:
- **Already a Plus subscriber:**
  > **User:** Try episode chat.
  > **Bot:** Opening episode chat.
  Result: Opens the chat directly without showing the paywall.

---

## Subscribe from paywall

> **User:** Start the free trial.
> **Bot:** Starting your free trial.

Result: Initiates the Plus subscription free trial flow.

> **User:** Subscribe to Plus.
> **Bot:** Starting your subscription.

Result: Begins the Plus subscription purchase flow.

> **User:** I'll take the free trial.
> **Bot:** Starting your free trial.

Result: Launches the free trial signup flow.

Edge cases:
- **Trial already used:**
  > **User:** Start the free trial.
  > **Bot:** You've already used your free trial. Subscribe to Plus to continue.
- **Payment failure:**
  > **User:** Subscribe to Plus.
  > **Bot:** Payment didn't go through. Try a different payment method.

---

## Dismiss paywall

> **User:** Close this, I don't want to subscribe.
> **Bot:** Closing.

Result: Dismisses the paywall dialog and returns to the previous screen.

> **User:** Not now.
> **Bot:** Closing.

Result: Closes the paywall without subscribing.

> **User:** No thanks.
> **Bot:** Closing.

Result: Dismisses the paywall dialog.

Edge cases:
- **No paywall open:**
  > **User:** Close the paywall.
  > **Bot:** No dialog is open right now.
