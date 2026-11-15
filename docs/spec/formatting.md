# Post: formatted text

Status: draft. Requirement R36 of [product.md](product.md) refers here.

Decisions of 2026-11-15: the composer edits Markdown with live highlighting, not a WYSIWYG editor. Each account gets the richest form its server accepts; other accounts get plain text with the markup removed. Markdown links in Bluesky posts follow a setting.

Sources, read 2026-11-15 at the heads of their main branches: `mastodon/mastodon` (`296ca287`), `glitch-soc/mastodon` (`29468cc4`), Pleroma (`cf6b7096`, git.pleroma.social), Akkoma (`3e9ad30e`, akkoma.dev), GoToSocial (`257aad74`, Codeberg), Hometown (`dc620a4b`), Sharkey (`e5558a33`), Iceshrimp.NET (`52ef47bd`), the NodeInfo 2.1 schema, `bluesky-social/bsky` lexicons (`416b0ad1`), docs.bsky.app (`bsky-docs` `fd3d7f8b`) and `social-app` (`9747031e`). No live server was queried.

## What servers accept

The Mastodon network type in Post covers every server that speaks the Mastodon client API.

| Server | Formats it accepts (`content_type`) | Where it says so |
|---|---|---|
| Mastodon | plain text only; Markdown is shown literally | nowhere |
| glitch-soc | `text/plain`, `text/markdown`, `text/html` | `configuration.statuses.supported_mime_types` in `/api/v2/instance` |
| GoToSocial | `text/plain`, `text/markdown` | `configuration.statuses.supported_mime_types` |
| Pleroma, Akkoma | by default `text/plain`, `text/markdown`, `text/html`, `text/bbcode`, `text/x.misskeymarkdown`; the admin can change the list | `pleroma.metadata.post_formats` in `/api/v1/instance`; `metadata.postFormats` in nodeinfo |
| Hometown, Sharkey, Iceshrimp.NET | no `content_type` | — |
| Bluesky | plain text with facets: links, mentions, hashtags | — |

Notes:

- glitch-soc reports `software.name` `mastodon` in nodeinfo, so the server name cannot tell it apart; the instance configuration can.
- Pleroma and Akkoma silently treat an unknown `content_type` as plain text. Post therefore sends only a type the server lists.
- glitch-soc's Markdown renders `_x_` as underline, not italic.
- Since Mastodon 4.2, remote HTML keeps `b strong i em u del s code pre blockquote ul ol li a p br` (4.3 added `s` and ruby). Formatting made on glitch-soc, GoToSocial or Pleroma therefore reaches readers on vanilla Mastodon. Headings become bold paragraphs.
- Bluesky has no bold or italic. A link facet may cover any text, not only the URL. The official app then shows a warning before opening such a link when the text is not the link's domain.

## Detecting support

- F1. With the other limits (networks.md, N1), Post reads the accepted formats of each Mastodon-API account in this order:
  1. `configuration.statuses.supported_mime_types` in `/api/v2/instance`;
  2. `pleroma.metadata.post_formats` in `/api/v1/instance`;
  3. `metadata.postFormats` in nodeinfo.
  If none is present, the account accepts plain text only.
- F2. Account details show the detected formats.

## Editor

- F3. The draft text is Markdown. The supported subset: bold `**x**`, italic `*x*`, strikethrough `~~x~~`, inline code, code blocks, block quotes, bulleted and numbered lists, links `[text](url)`. Other Markdown, such as headings, tables or images, stays plain text and is sent as typed.
- F4. The editor highlights the markup as the user types: bold text looks bold, and the syntax characters are dimmed but stay visible and editable.
- F5. The formatting buttons are part of the toolbar above the keyboard (screens.md): bold, italic, strikethrough, code, quote, list, link. A button wraps the selection, or inserts an empty pair and puts the cursor inside. The link button asks for the address; a URL in the clipboard is offered.
- F6. The toolbar shows the formatting buttons always. When no selected account keeps formatting, a hint says that the formatting will be removed on publishing.
- F7. Preview, in the ⋯ menu and in the status line sheet, shows the text exactly as each selected network will receive it.

## Converting per account

- F8. When the account accepts `text/markdown`, Post sends the Markdown with `content_type: text/markdown`, rewritten for the server's dialect: italic always as `*x*`, never `_x_`.
- F9. Otherwise, when it accepts `text/html`, Post converts the Markdown to HTML with only the tags from F3 and sends `content_type: text/html`.
- F10. Otherwise Post sends plain text:
  - bold, italic, strikethrough and code markers are removed, the text stays;
  - a block quote keeps its `> ` prefix;
  - list items become `• ` or `1. ` lines;
  - a link becomes `text (url)`, or only the URL when the text is the URL.
- F11. Bluesky gets the plain text of F10 with these differences:
  - Links follow the setting "Bluesky: Markdown links". By default the link text becomes a link facet to the URL, and the URL itself is not in the text. The other option is the text followed by the shortened address with a link facet on it.
  - Bare URLs, mentions and hashtags become facets (networks.md, N17).
- F12. Every counter and limit check runs on the converted text of its network, not on the draft. For Markdown servers the counter counts the Markdown source. (Not verified: that glitch-soc, GoToSocial and Pleroma apply the length limit to the source rather than the rendered text. Counting the source is the safer side, because Markdown source is longer than its text.)

## Verification scenarios

- `**Hello** _there_ [site](https://example.com)` to vanilla Mastodon: `Hello there site (https://example.com)`; the counter counts the URL as 23 characters.
- The same text to GoToSocial: sent as `text/markdown` with `*there*`.
- The same text to Bluesky with the default setting: `Hello there site`, with a link facet on `site`.
- A Pleroma server whose admin left only `text/plain`: plain text, the toolbar hint about removed formatting shows when it is the only target.
