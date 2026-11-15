# Post: screens

Status: draft. Layout and content are approximate; exact visuals are decided during implementation. Requirements are in [product.md](product.md).

Post is an Android app only. It has no web part.

## Navigation

The composer is the start screen. Its top bar leads to Posts (queue, drafts, history), Schedule and Settings. There is no bottom navigation bar: the composer keeps the whole screen for text, and the other screens open on top of it with a back arrow.

```
Composer ──┬── Posts (Queue · Drafts · Sent) ── Post details
           ├── Schedule
           └── Settings ──┬── Accounts ── Add account / Account details
                          ├── Defaults
                          ├── Sending
                          └── About
Share sheet (from other apps) ── Composer
```

## Composer (start screen)

```
┌──────────────────────────────────────────┐
│ Post                      [Posts 3] [⚙] │  top bar: queue badge, schedule and settings in ⋮
├──────────────────────────────────────────┤
│ (M) @me@mastodon.social ✓  (B) me.bsky ✓ │  targets: account chips, scroll sideways
│ (M) @work@fosstodon ·                    │
├──────────────────────────────────────────┤
│ Text of the post…                        │  text field, grows with content
│                                          │
│ ── part 2 ──────────────────────── [×]   │  thread parts, when added
│ …                                        │
├──────────────────────────────────────────┤
│ [img][img][vid]                          │  attachment strip: alt badge, conflict badge
│ Mastodon 212/500 · Bluesky 212/300 ⚠     │  per-network status line
├──────────────────────────────────────────┤
│ [📎][B][I][🔗][CW][🌐][⋯]  [Later][Send] │  toolbar above the keyboard
└──────────────────────────────────────────┘
```

- Targets. One chip per account: avatar, network mark, label. A tap selects or clears it; a long press opens that account's options for this post (visibility, reply settings, language). The default targets (R16) are selected in a new draft.
- Text. One field per thread part. "Add part" in the ⋯ menu, or the separator from R12, adds a part below.
- Attachment strip. Thumbnails of the attachments of the current part. A tap opens the attachment sheet: preview, alt text, focus point, remove. A badge marks missing alt text, and a warning marks a conflict ([media.md](media.md)).
- Status line. One counter per selected network, counted by its rules. A tap opens the details sheet: what is converted, what conflicts, which fixes are available.
- Toolbar. It sits above the keyboard and stays visible when the keyboard is closed. From the left: attach (photo picker, files), formatting buttons (see Formatting below), content warning, language, the ⋯ menu (poll, add part, per-network options, save draft, clear). On the right: Send later and Send. Send is disabled when no target is selected or a conflict has no fix; a tap on it in that state opens the details sheet.
- Leaving with unsaved text asks whether to keep the draft (R15).

### Send later sheet

A bottom sheet from Send later:

- Next free slot: "Tue 18 Nov, 09:00" (default, the first row).
- The next free slots of the coming days, five rows.
- Pick date and time.
- Without a schedule, only the picker is shown, with a link to Schedule.

### Formatting

The text is Markdown with live highlighting ([formatting.md](formatting.md)). The formatting buttons sit in the toolbar above the keyboard, after the attach button: bold, italic, strikethrough, code, quote, list, link. When the toolbar is too narrow, it scrolls sideways; attach, Send later and Send stay fixed. Preview in the ⋯ menu shows the text as each network will receive it.

## Share sheet

A bottom sheet over the app that shared the content (R19–R22):

```
┌──────────────────────────────────────────┐
│ Share to                                 │
│ (M) @me ✓  (B) me.bsky ✓  (M) @work ·    │  targets, default ones selected
│ Shared text, editable, 4 lines max…      │
│ [img][img]                               │
│ Mastodon 120/500 · Bluesky 120/300       │
│ [Open in composer] [Send later] [Send]   │  the default action is the filled button
└──────────────────────────────────────────┘
```

Send later here uses the next free slot at once; a long press opens the Send later sheet. Conflicts without a default fix disable both send actions and show a line that names them (media.md, A12–A13).

## Posts

One screen with three tabs.

- Queue. Groups from the top: Late (posts on hold after missing their time, with Send now, Reschedule and per-post actions; scheduling.md, Q7), Failed (failed deliveries), then waiting posts ordered by time. Each row: send time (slot or explicit), the first line of text, attachment count, target avatars with a state dot each (waiting, sending, sent, failed). Swipe or menu: Send now, Change time, Edit, Delete, Move up, Move down.
  - Slot-based waiting posts have a drag handle. A long press on the handle starts dragging; on drop the posts take the slot times in the new order and an Undo bar appears (scheduling.md, S8–S10). Posts with an explicit time show a pin instead of the handle.
- Drafts. Saved drafts, newest first. A tap opens the draft in the composer.
- Sent. History (R31): text, time, and per account a link to the published copy. A menu offers Open, Copy link, New post from this. Media are not kept. Records older than the retention period are deleted.

### Post details

Opens from any row of Queue or Sent. Shows the full text by thread part, attachments (while kept), and for each account: state, time, server error text for a failure, link for a success. Actions: Retry (for failed deliveries), Send now, Change time, Edit (only before the first delivery starts), Delete.

## Schedule

The weekly schedule of slots (R26, [scheduling.md](scheduling.md)).

```
┌──────────────────────────────────────────┐
│ ← Schedule                               │
│ Mon  09:00  13:00  18:00          [+]    │
│ Tue  09:00  13:00  18:00          [+]    │
│ …                                        │
│ Sun  —                            [+]    │
├──────────────────────────────────────────┤
│ Coming slots                             │
│ Today 13:00   "Release notes for…"       │
│ Today 18:00   free                       │
│ Tue  09:00    "Photo walk"               │
│ [Redistribute queue]                     │
└──────────────────────────────────────────┘
```

- Each weekday row lists its times as chips. A tap on a chip edits or deletes the time; [+] adds one. The day menu offers "Copy to weekdays" and "Copy to all days".
- Coming slots shows the next seven days: each slot with the queued post that holds it, or "free". A tap on a post opens Post details.
- Redistribute reassigns slot-based posts to the current schedule (S6).
- The time zone in use is shown at the bottom.

## Settings

A list of sections.

- Accounts. Accounts grouped by network, with avatar, handle and label; a state mark when sign-in is needed. "Add account" first asks for the network.
  - Add Mastodon account: the instance address, then sign-in in a Custom Tab (networks.md, N4–N5).
  - Add Bluesky account: the handle and an app password, with a link to the Bluesky page that creates app passwords. OAuth is planned for a later version.
  - Account details: label, default options of this account (visibility, language, content warning, reply settings, sensitive flag), the limits read from its server and when they were read, Sign in again, Remove.
- Defaults. Default targets, the default Share action (composer, send now, send later), "Require alt text", default fixes for media conflicts (media.md, A12), "Bluesky: Markdown links" (formatting.md, F11).
- Sending. Notifications (success summary on or off, link to system settings), history retention (off, 7, 30, 90 days), late threshold (15 minutes, 30 minutes, 1 hour, 3 hours), video transcoding quality, keep image metadata.
- Schedule. Opens the Schedule screen.
- About. Version, links to the source repository and the privacy note.
