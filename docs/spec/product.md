# Post: product specification

Status: draft, nothing implemented. Network limits are in [networks.md](networks.md), the queue and schedule in [scheduling.md](scheduling.md), releases in [distribution.md](distribution.md).

Sources: the user's requirements and decisions of 2026-11-15; the Mastodon, AT Protocol, Obtainium and Android documentation cited in the other files.

## Purpose and scope

Post is an Android app that publishes one message to several Bluesky and Mastodon accounts at once. A message has text and optional attachments. It goes out right away or later: at a time chosen for it, or in the next free slot of a weekly schedule.

The app accepts content from Android's Share menu. Sharing offers two actions: send now or send later.

Out of scope: reading timelines, notifications, replies to other people's posts, direct messages, editing or deleting published posts, other networks. The data model must allow more network types later (see R3).

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
- R4. Sign-in follows the network's method from networks.md (Mastodon OAuth, Bluesky OAuth or app password). Tokens are stored encrypted with a key in Android Keystore. They are never logged and never sent to any host other than the account's server.
- R5. When a server rejects the credentials, the account is marked "needs sign-in", its deliveries stop with that reason, and a notification asks to sign in again. Signing in again resumes them.

### Composer

- R6. The composer has a text field, attachment buttons, a target picker, per-network options and two actions: Send and Send later.
- R7. The target picker lists every account as a chip, grouped by network. The default targets (R16) are preselected. Send is disabled without a target.
- R8. A character counter is shown for each selected network, counted by that network's rules and limit (networks.md). If the text exceeds the limit of any selected account, the counter of that network turns red and Send is disabled. The composer offers to split the text into a thread for that network (R12).
- R9. Attachments: images, video, GIF, and for Mastodon audio. Sources: the Android photo picker, the file picker (`ACTION_OPEN_DOCUMENT`), the Share menu. Every attachment is copied into app storage when added, so later sending does not depend on the source app's URI grant.
- R10. Each attachment has alt text. A setting "Require alt text" (off by default) blocks sending without it.
- R11. Before sending, the app checks every attachment against each selected account: count, type, size, resolution, duration, and allowed combinations. Images that exceed a size limit are downscaled and recompressed automatically. Video is not transcoded in the first version: a video that does not fit is reported with the network and the limit it breaks, and that account cannot be selected until the video is removed.
- R12. Thread: the user can split a message into several parts with an explicit separator. Each part is checked against the limits on its own. A thread is published as a chain of replies to the previous part, in each target account. Attachments belong to the part they were added to.
- R13. Per-network options:
  - Mastodon: visibility (public, unlisted, followers only, direct), content warning text, sensitive media flag, language, poll (options and duration; not combined with media).
  - Bluesky: language(s), content labels for media (adult content values from networks.md), who can reply (threadgate), quotes allowed (postgate).
  - Common: language is entered once and passed to both networks.
- R14. Links: the app does not fetch link previews in the first version for Mastodon (the server builds them). For Bluesky, the app builds a link card from the first link when the post has no media: it fetches the page's title, description and image itself. The user can remove the card.
- R15. Leaving the composer with unsaved changes asks whether to keep the draft. Drafts are saved locally and listed on the Drafts screen.

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
- R25. The Queue screen lists scheduled posts by time and failed deliveries. The user can open a queued post, edit it, change its time, send it now or delete it.
- R26. Settings hold the weekly schedule: for each weekday, a list of times. The schedule uses the device time zone at the moment of sending.

### Sending and history

- R27. A post is saved to the local database before any network request. Crashes, process death and reboots do not lose posts.
- R28. Deliveries to different accounts are independent: a failure in one account does not stop the others. A post is sent when every delivery has succeeded.
- R29. Temporary errors (no network, timeout, HTTP 5xx, 429) are retried with backoff. Errors that retrying cannot fix (rejected credentials, validation error, a limit change that makes the post invalid) stop that delivery, mark it failed with the server's message and show a notification.
- R30. A repeated send must not create a duplicate post (networks.md, Idempotency). A thread already partly published resumes from the first unpublished part.
- R31. The History screen lists sent posts with links to each published copy. History is kept for 30 days by default (setting); attachment copies are deleted as soon as all deliveries of a post succeed.
- R32. Notifications: progress of a long upload, failure of a delivery, success summary (setting, off by default). On Android 13+ the permission is requested the first time the user sends.

### General

- R33. Material 3 with dynamic color and a dark theme. Every control has a screen reader label.
- R34. The app sends no analytics and talks only to the account servers and, for Bluesky link cards, the linked page.
- R35. Interface languages: English and Russian.

## Open questions

- Bluesky sign-in: OAuth only, app password only, or both, and where the OAuth client metadata is hosted (networks.md, N14).
- Late queued posts: whether to skip posts more than N hours late (scheduling.md, Q4).
- Video transcoding to fit limits: not in the first version (R11); revisit after use.
- Quotes (Mastodon 4.5+, Bluesky): not in the first version.
