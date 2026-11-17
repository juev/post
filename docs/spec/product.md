# Post: product specification

Status: draft, nothing implemented. The other files:

- [screens.md](screens.md): screens and their content;
- [networks.md](networks.md): network limits, sign-in, publishing;
- [media.md](media.md): how attachments are adapted to each network;
- [formatting.md](formatting.md): Markdown and its conversion per server;
- [scheduling.md](scheduling.md): the queue and the schedule;
- [distribution.md](distribution.md): releases.

Sources: the user's requirements and decisions of 2026-11-15; the Mastodon, AT Protocol, Obtainium and Android documentation cited in the other files.

## Purpose and scope

Post is an Android app that publishes one message to several Bluesky and Mastodon accounts at once. A message has text and optional attachments. It goes out right away or later: at a time chosen for it, or in the next free slot of a weekly schedule.

The app accepts content from Android's Share menu. Sharing offers two actions: send now or send later.

Post is an Android app only, with no web part. The composer is its start screen.

Out of scope: reading timelines, notifications, replies to other people's posts, quoting other people's posts, direct messages, other networks. The data model must allow more network types later (see R3).

Android 13 (API 33) and newer. Kotlin, Jetpack Compose, Room, WorkManager, as in Brook. Application ID `org.evsyukov.post`. Distribution only through Obtainium from GitHub Releases.

## Terms

- **Network**: a network type, Bluesky or Mastodon.
- **Account**: one signed-in profile in one network: a Bluesky handle on its PDS, or a Mastodon user on an instance. The user may have several accounts in one network.
- **Draft**: an unsent message being edited in the composer.
- **Post**: a message saved for sending. It holds the text, attachments, options and a list of target accounts.
- **Delivery**: sending one post to one target account. A post has one delivery per target account, each with its own state.
- **Queue**: posts that wait for sending: scheduled or failed ones.
- **Slot**: a weekly time from the schedule settings, such as Monday 09:00.

## Requirements

### Accounts

- R1. The user adds any number of accounts in each network and removes them. A removed account leaves its pending deliveries marked failed with the reason "account removed".
- R2. Each account shows its avatar, display name, handle and network. The user sets a short label for it, shown in the target picker.
- R3. Accounts belong to a network type through a single interface (publish, upload media, read limits, verify credentials). Adding a third network does not change the queue, composer or share flow.
- R4. Sign-in follows the network's method from networks.md: OAuth for Mastodon and an app password for Bluesky; Bluesky OAuth is not planned for now (networks.md, N14). Tokens are stored encrypted with a key in Android Keystore. They are never logged and never sent to any host other than the account's server.
- R5. When a server rejects the credentials, the account is marked "needs sign-in", its deliveries stop with that reason, and a notification asks to sign in again. Signing in again resumes them. Signing an account in again must bring back the same account: for Mastodon the same account id on the same server, for Bluesky the same DID (on any PDS). Another account is rejected with a message naming both, and adding it goes through the ordinary sign-in. Mastodon revokes the token of the other account; Bluesky rejects it before the password is sent.

### Composer

- R6. The composer has a text field, attachment buttons, a target picker, per-network options and two actions: Send and Send later.
- R7. The target picker lists every account as a chip, grouped by network. The default targets (R16) are preselected. Send is disabled without a target.
- R8. A character counter is shown for each selected network, counted by that network's rules and limit (networks.md). If the text exceeds the limit of an account, it is split into a thread for that account (R12), and the counter shows the number of parts. Send is disabled only when the text cannot be split (threads.md, T9).
- R9. Attachments: images, video, GIF, and for Mastodon audio. Sources: the Android photo picker, the file picker (`ACTION_OPEN_DOCUMENT`), the Share menu. Every attachment is copied into app storage when added, so later sending does not depend on the source app's URI grant.
- R10. Each attachment has alt text. A setting "Require alt text" (off by default) blocks sending without it.
- R11. The user attaches files once for all targets. The app checks every attachment against each selected account: count, type, size, resolution, duration, and allowed combinations. It converts images and transcodes video by itself when that keeps the content. When something must be dropped or moved, the composer shows the conflict for that network and offers fixes; sending waits for a fix. Rules are in [media.md](media.md).
- R12. Thread: a text longer than an account's limit is split into a thread for that account automatically, at paragraph and sentence boundaries, with balanced parts and no short leftover at the end. The user sees the split in a preview and can add manual breaks. Rules are in [threads.md](threads.md).
- R13. Per-network options:
  - Mastodon: visibility (public, unlisted, followers only, direct), content warning text, sensitive media flag, language, poll (options and duration; not combined with media).
  - Mastodon 4.5+: who may quote this post (`quote_approval_policy`: anyone, followers, nobody).
  - Bluesky: language(s), content labels for media (adult content values from networks.md), who can reply (threadgate), whether others may quote this post (postgate).
  - Common: language is entered once and passed to both networks.
- R14. Links: the app does not fetch link previews in the first version for Mastodon (the server builds them). For Bluesky, the app builds a link card from the first link when the post has no media: it fetches the page's title, description and image itself. The user can remove the card.
- R15. Leaving the composer with unsaved changes asks whether to keep the draft. Drafts are saved locally and listed on the Drafts tab of Posts.

### Defaults

- R16. Settings hold default targets: a set of accounts preselected in a new draft and used by "send now" from Share without opening the composer (R20).
- R17. Settings hold default options per account: visibility, language, content warning, reply settings for Bluesky, sensitive flag. A new draft takes them; the composer can change them for one post.
- R18. Settings hold the default action of the Share menu: open the composer, send now, or send later.

### Share menu

- R19. The app registers `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for `text/plain`, `image/*`, `video/*` and `audio/*`. Shared text, the subject (`EXTRA_SUBJECT`) and files fill a draft. Files are copied into app storage before the share activity finishes.
- R20. The share sheet is a compact screen over the sending app. It shows the draft text (editable), the attachments, the targets (default targets selected) and three actions: Send now, Send later, Open in composer. The action from R18 is highlighted.
- R21. Send now saves the post locally and starts delivery; the sheet closes and a toast confirms "Sending to N accounts". Send later puts the post into the queue with the next free slot (or a chosen time, R24) and closes.
- R22. If the shared content breaks a limit of a default target, Send now and Send later are disabled and the sheet says which account and limit; Open in composer stays available.
- R23. The app publishes a sharing shortcut so it appears in the Direct Share row.

### Queue and scheduling

Detailed rules are in [scheduling.md](scheduling.md).

- R24. Send later offers: "Next free slot" (default), a date and time picker, and the slots of the next days to choose from.
- R25. The Queue tab of Posts lists scheduled posts by time and failed deliveries. The user can open a queued post, edit it, change its time, send it now or delete it, and drag slot-based posts to change their order (scheduling.md, S8–S10).
- R26. Settings hold the weekly schedule: for each weekday, a list of times. The schedule uses the device time zone at the moment of sending.
- R26a. A post that missed its time by more than a threshold (30 minutes by default), for example because the phone was off, is never sent on its own. The app holds it and asks with a notification: send now, move to the next free slots, or review one by one. Rules are in scheduling.md, Q4–Q7.

### Sending and history

- R27. A post is saved to the local database before any network request. Crashes, process death and reboots do not lose posts.
- R28. Deliveries to different accounts are independent: a failure in one account does not stop the others. A post is sent when every delivery has succeeded.
- R29. Temporary errors (no network, timeout, HTTP 5xx, 429) are retried with backoff. Errors that retrying cannot fix (rejected credentials, validation error, a limit change that makes the post invalid) stop that delivery, mark it failed with the server's message and show a notification.
- R30. A repeated send must not create a duplicate post (networks.md, Idempotency). A thread already partly published resumes from the first unpublished part.
- R31. The Sent tab of Posts keeps a short history: text, send time and a link to each published copy, without media. Records are kept for 30 days by default; the setting offers off, 7, 30 and 90 days. History lets the user find a published copy and start a new post from an old one. Attachment copies are deleted as soon as all deliveries of a post succeed.
- R32. Notifications: progress of a long upload, failure of a delivery, success summary (setting, off by default). On Android 13+ the permission is requested the first time the user sends.

### General

- R33. Material 3 with dynamic color and a dark theme. Every control has a screen reader label.
- R34. The app sends no analytics. It talks only to:
  - the account servers (Mastodon instances, Bluesky PDSes);
  - for avatars (R2): the image addresses the account servers return, often a separate media host such as `cdn.bsky.app`;
  - for Bluesky sign-in and mentions: `public.api.bsky.app` to resolve handles and read the signed-in account's public profile, `plc.directory` or the `did:web` domain for DID documents, and the handle's domain for `/.well-known/atproto-did`;
  - for Bluesky video: `video.bsky.app`;
  - for Bluesky link cards: the linked page and its preview image.
- R35. Interface languages: English and Russian.

### Editing and deleting published posts

- R37. A post in the Sent tab can be edited or deleted while its history record exists. With history off, or after the record expires, the app has no ids to act on and offers only to open the published copy in the browser.
- R38. The history record keeps what this needs: per account and per thread part, the server id (Mastodon status id; Bluesky AT URI and CID), the source text of the draft, the options, and for Mastodon the media ids with their alt text. Media files are not kept. A delivery of an edit or a deletion that succeeds writes its account's copy into the record at once, even when another account's delivery fails. A delivery applied in part (some parts posted, edited or deleted before it failed) is written into the record when the user deletes the edit or deletion from the Queue; until then the record stays as it was, because a retry compares its plan with it (#4).
- R39. Delete asks which accounts to delete from (all by default) and removes every part of the thread there, last part first. A part that is already gone counts as deleted. The record leaves history when all deletions succeed.
- R40. Edit opens the composer with the source text and options. The targets are the accounts where the post was published; clearing one leaves its copy unchanged. Network rules are in networks.md, N28–N33: Mastodon edits in place, Bluesky offers "delete and post again".
- R41. Edits and deletions are saved locally first and sent like posts: with retries, idempotency and a place in the Failed group of the Queue tab (R27–R30).

### Formatting

- R36. The composer edits Markdown with live highlighting and a formatting toolbar above the keyboard. Each account gets Markdown or HTML when its server accepts them, and plain text with the markup removed otherwise. Rules are in [formatting.md](formatting.md).

## Decided later

- Bluesky OAuth: not planned for now; the app uses app passwords (networks.md, N14).
- A separate application ID for QA builds: decided during implementation.
- Bluesky link cards (R14): built by the app; to be reviewed after testing.
