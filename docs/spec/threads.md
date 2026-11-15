# Post: splitting long text into threads

Status: draft. Requirement R12 of [product.md](product.md) refers here.

Decision of 2026-11-15: the user writes one text. When it does not fit the limit of an account, Post splits it into a thread for that account by itself. The split follows the text's structure, so no part ends in the middle of a sentence when a better break exists, and no part is a short leftover.

## Where splitting happens

- T1. The draft is one text. Splitting runs separately for every target account, on the text that account will receive (after the conversion of [formatting.md](formatting.md)) and with that account's counting rules and limit ([networks.md](networks.md)). A Mastodon account with a 5000-character limit may get one post while a Bluesky account gets four.
- T2. When the converted text fits the limit, the account gets one post and no splitting happens.
- T3. Accounts of one network with the same limit and the same formats get the same split. The composer therefore shows one split per distinct limit, usually one per network.

## Algorithm

- T4. Candidate breaks. Post finds every position where the text may be split and gives each a rank:
  1. a blank line between paragraphs;
  2. a line break (list items, quote lines);
  3. the end of a sentence;
  4. a clause boundary: after `;`, `:`, `,` or a dash between words;
  5. a space between words.
  Sentences and words are found with `android.icu.text.BreakIterator` for the post's language, which knows abbreviations such as "e.g." and "т. е." in common cases.
- T5. Forbidden breaks. A break is never placed inside a URL, a mention, a hashtag, an emoji or another grapheme cluster, inline code, or a Markdown link. A code block is split only between its lines, and each part closes and reopens the fence. A bold, italic or strikethrough span crossed by a break is closed at the end of one part and reopened at the start of the next.
- T6. The number of parts is the smallest number that fits. Post first finds it greedily: each part takes as much text as fits, ending at the best-ranked candidate break.
- T7. With the number of parts fixed, Post then chooses the breaks again to balance the parts. The cost of a split adds:
  - a penalty for each break by its rank: a paragraph break costs nothing, a word break costs most;
  - a penalty for uneven parts: the square of the difference between each part's length and the average length.
  The split with the lowest cost wins; dynamic programming over the candidate breaks finds it. A good break in an uneven place may lose to a slightly worse break that evens the parts.
- T8. The tail rule. The last part must hold at least a third of the average part length, unless the whole text is shorter than that. If no split of N parts meets the rule, Post tries N + 1 parts. The rule keeps a few words or the end of a sentence from becoming a part of their own.
- T9. When a single sentence is longer than the limit, Post breaks it at a clause boundary, and only then between words. A single word longer than the limit (for example, a very long URL in Bluesky) cannot be split: the composer shows a conflict for that network.
- T10. Whitespace at a break is dropped: no part starts or ends with spaces or blank lines.

## Numbering

- T11. A setting "Number thread parts" adds ` 1/3`, ` 2/3`, … at the end of each part. Off by default. The space for the numbers is reserved before splitting; since the count can change the width (`9/9` to `1/10`), Post repeats the split until the count is stable.

## User control

- T12. The status line of the composer shows the number of parts per network, for example "Mastodon 1 · Bluesky 4 parts". A tap opens the preview: the parts of each network as they will be published, with their counters.
- T13. "Break here" in the toolbar ⋯ menu inserts a manual break at the cursor. The editor shows it as a thin line across the text; in the text it is stored as a marker that is never published. A manual break always splits the text for every network, and the automatic split then runs inside each piece. Deleting the line removes the break.
- T14. Attachments belong to the first part, unless a media fix moved them to an added reply ([media.md](media.md), A11). With a manual break, attachments added below the break belong to the piece they were added in.

## Publishing

- T15. Each part is published as a reply to the previous part of the same account, as networks.md describes (N10 for Mastodon, N24 for Bluesky). A thread already partly published resumes from the first unpublished part (R30).
- T16. Limits on thread length: Bluesky's `applyWrites` takes up to 200 writes, and Mastodon allows 300 statuses per 3 hours. Post warns from 10 parts and refuses more than 50 for one account.
- T17. A text without a split, but longer than the shortest limit, is never cut silently: either it is split, or the composer shows a conflict.

## Verification scenarios

- A 1200-character Russian text of four paragraphs to Bluesky (300) and Mastodon (500): Mastodon gets 3 parts, Bluesky 5; every break falls on a paragraph or sentence end; no part is under a third of the average.
- A 310-grapheme text of one sentence to Bluesky: 2 parts of roughly equal length, split at a comma; not 300 + 10.
- A text with a 400-character URL to Bluesky: conflict, no split inside the URL.
- `**bold text that crosses the break**`: the first part ends with `**`, the next starts with `**`.
- A manual break after the first paragraph of a 200-character text: both networks get 2 parts.
- Number parts on, a text that needs exactly 9 parts without numbers and 10 with them: the split ends with 10 parts numbered `1/10` … `10/10`, each within the limit.
