# Post: Bluesky and Mastodon

Status: draft. This file states what each network accepts and how Post publishes to it. [product.md](product.md) refers here for limits, sign-in and idempotency.

Sources, read 2026-11-15:

- Mastodon: docs.joinmastodon.org (repository `mastodon/documentation`, `main`) and the source of `mastodon/mastodon` `main`, which is newer than 4.5.
- Bluesky: lexicons of `bluesky-social/bsky` at `416b0ad1d9` (the `app.bsky.*` lexicons moved there from `bluesky-social/atproto`), `bluesky-social/atproto` at `7dd086d4de`, `bluesky-social/social-app` at `9747031e9b`, docs.bsky.app and atproto.com.

Values marked "not verified" were not found in a primary source.

## Limits at a glance

| | Mastodon (vanilla defaults) | Bluesky |
|---|---|---|
| Text | 500 characters, per instance | 300 graphemes and 3000 bytes |
| How text is counted | grapheme clusters of content warning + text; each URL counts 23; `@user@domain` counts as `@user` | graphemes of the text; links count in full unless shortened in the text |
| Attachments | 4 | 4 images, or up to 10 in a gallery, or 1 video |
| Images | JPEG, PNG, GIF, WebP; 16 MB; up to 7680×4320, a GIF up to 1280×720 | `image/*`, 2 MB each |
| Video | MP4, WebM, QuickTime, Ogg; 99 MB; up to 3840×2160, 120 fps | MP4 after server transcoding; 300 MB in the lexicon; social-app caps it at 10 minutes |
| Mixing media | one video or one audio file alone; images and GIFs together | images or one video, never both |
| Audio | yes, 99 MB | no |
| Alt text | 10 000 characters | images: required field; video: optional; social-app limits alt text to 2000 |
| Media without text | yes | yes |
| Polls | 4 options × 50 characters, 5 minutes to 1 month; not with media | no |
| Content warning | `spoiler_text` + `sensitive` | self-labels `sexual`, `nudity`, `porn`, `graphic-media` |
| Visibility | public, unlisted, private (followers), direct | always public |
| Reply control | quote policy only (4.5+) | threadgate (mentioned, followers, following, lists, nobody), postgate (no quotes) |
| Languages | one | up to 3 |
| Server-side scheduling | yes (not used by Post) | no |
| Post rate limit | 300 statuses per 3 hours (source code, not in docs) | 5000 write points per hour, a post costs 3 |

## Mastodon

### Limits are read from the server

Instances and forks change the limits: glitch-soc and Hometown read the text limit from `MAX_TOOT_CHARS`. Post therefore never hard-codes them.

- N1. On sign-in and then once a day, Post reads `GET /api/v2/instance` and stores `configuration.statuses` (`max_characters`, `max_media_attachments`, `characters_reserved_per_url`), `configuration.media_attachments` (supported MIME types, size and matrix limits, `description_limit`) and `configuration.polls`. Servers older than 4.0 lack this block; Post then uses the vanilla defaults from the table.
- N2. Feature detection uses `api_versions.mastodon` (4.3+), not the version string. Quote policy needs API version 7 (Mastodon 4.5).
- N3. The character counter follows `StatusLengthValidator`: grapheme clusters of `spoiler_text` joined with the text, each URL as `characters_reserved_per_url`, each mention without its domain.

### Sign-in

- N4. Post registers itself on the instance with `POST /api/v1/apps` once per instance, with the redirect URI `org.evsyukov.post://oauth/mastodon` and scopes `write:statuses write:media read:statuses profile` (`read:accounts` instead of `profile` before 4.3). `read:statuses` lets Post find its own status after a lost answer (N12).
- N5. Authorization uses the code flow in a Custom Tab, with PKCE S256 on 4.3+. Tokens do not expire and there is no refresh token. Removing an account calls `POST /oauth/revoke`.
- N6. `GET /api/v1/accounts/verify_credentials` gives the account's name, handle and avatar.

### Publishing

- N6a. The Mastodon network type covers every server with the Mastodon client API: glitch-soc, Hometown, GoToSocial, Pleroma, Akkoma and others. Their differences in limits and formats are read from the server (N1, formatting.md F1).
- N6b. A GIF larger than 1280×720 pixels (921 600 in total) is rejected with 422, whatever the configured image limits say (`GIF_MATRIX_LIMIT` in `app/models/concerns/attachmentable.rb`, `main` read 2026-11-15). `/api/v2/instance` does not report this limit, so Post applies it to every Mastodon account.
- N7. Media go to `POST /api/v2/media` with `description` and `focus`. Images return 200. Video and audio return 202 and are polled with `GET /api/v1/media/:id` until 200 (ready) or 422 (failed). A status with unprocessed media is rejected, so Post waits for all of them.
- N8. Media upload is limited to 30 requests per 30 minutes. Uploaded media that are not attached to a status within a day are deleted by the server, so Post uploads media at send time, not at queue time.
- N9. The status goes to `POST /api/v1/statuses` with `status`, `media_ids[]`, `spoiler_text`, `sensitive`, `visibility`, `language`, `poll[...]`, `in_reply_to_id` for thread parts, and `Idempotency-Key`.
- N10. A thread is a chain of statuses, each with `in_reply_to_id` of the previous part and the same visibility, passed explicitly.

### Idempotency

Mastodon keeps `Idempotency-Key` for one hour per account; a repeat within the hour returns the status already created. Not every server does: in the E2E run of 2026-11-16, GoToSocial 0.22.3 created two statuses for two requests with the same key.

- N11. Each delivery part has a stable key stored with it, used for every retry.
- N12. If the outcome of a request is unknown, Post looks for the status in the account's latest statuses (`GET /api/v1/accounts/:id/statuses`) by text, reply target and time before sending it again, at any age of the last attempt. If the lookup itself fails, Post repeats the request with the same key. (The match is a heuristic: two identical texts posted to the same place within minutes can be taken for each other.)

## Bluesky

### Sign-in

The AT Protocol offers OAuth and app passwords. Bluesky asks new projects to use OAuth; app passwords still work and are called legacy, with no published shutdown date (not verified).

OAuth for a native app requires:

- a public client whose `client_id` is an HTTPS URL of a hosted JSON metadata document (`application_type: native`, `dpop_bound_access_tokens: true`);
- PKCE S256, PAR and DPoP with a server nonce;
- a redirect URI that is either the reversed `client_id` hostname as a custom scheme (`com.example.app:/callback`) or an HTTPS App Link on the same origin;
- refresh tokens, which live up to two weeks for such a client; access tokens live under 30 minutes.

Scope `atproto transition:generic` gives app-password-level access. Video upload also needs `com.atproto.server.getServiceAuth`, which a granular scope must allow through `rpc:`.

- N13. Sign-in starts from a handle. Post resolves the handle to a DID (DNS TXT `_atproto.<handle>` or `https://<handle>/.well-known/atproto-did`), checks the DID document points back to the handle, and takes the PDS from the `#atproto_pds` service.
- N14. Decision of 2026-11-15: the first version signs in with the handle and an app password (`com.atproto.server.createSession`), so Post needs no hosted file. The sign-in code keeps the method behind one interface, so OAuth can be added later without changing accounts or sending. OAuth needs a static JSON file at an HTTPS address; where to host it is decided when OAuth is added.
- N15. Post stores the access and refresh tokens from `createSession`, not the app password, and refreshes them with `com.atproto.server.refreshSession`. When the refresh is rejected, the account needs sign-in (R5 of product.md). `createSession` is limited to 30 calls per 5 minutes per account, so Post never signs in again on its own in a loop.

### Text and facets

- N16. The counter counts graphemes up to 300 and also checks 3000 UTF-8 bytes.
- N17. Post builds facets itself: links (`#link`), mentions (`#mention` with the DID resolved from the handle; an unresolved handle stays plain text) and hashtags (`#tag`, up to 64 graphemes). Offsets are UTF-8 byte offsets.
- N18. `langs` takes up to three languages from the post options.

### Media

- N19. Images are uploaded with `com.atproto.repo.uploadBlob` and embedded with `app.bsky.embed.images` (up to 4) or `app.bsky.embed.gallery` (5–10, the soft limit the lexicon asks clients to keep). Each image must fit 2 MB; Post downscales larger images to 4000 px on the long side and recompresses them, as social-app does. `aspectRatio` is always set.
- N20. Video goes through the video service, not `uploadBlob`, because the Bluesky PDS limits a blob to 50 MB:
  1. Check `app.bsky.video.getUploadLimits` (quota per day; a verified email is required).
  2. Get a service token with `com.atproto.server.getServiceAuth`.
  3. Upload with the multipart flow (`startUpload`, `uploadPart`, `finishUpload`); parts can be retried.
  4. Poll the job status until it returns a blob, then embed it with `app.bsky.embed.video`, alt text and aspect ratio.
- N21. A local GIF is uploaded as video with `presentation: gif`, as social-app does.
- N22. Content labels from the post options go into `labels` as `com.atproto.label.defs#selfLabels`.
- N23. A link card (`app.bsky.embed.external`) needs a title, description and optional thumbnail up to 1 MB. Post fetches the page itself (R14 of product.md).

### Publishing and idempotency

- N24. Decision of 2026-11-15: each thread part goes in its own `com.atproto.repo.applyWrites` call. The first part also carries the threadgate in the same call. When quoting is turned off, every part carries its own postgate in its call, because a postgate covers one post; social-app does the same (`src/lib/api/index.ts` at `9747031e9b`). Each record gets a TID generated by Post and stored with the delivery before the request. The URI and CID of a published part come from the `results` of the call (or from `com.atproto.repo.getRecord` when the results are missing) and become the `reply` references of the next part, so Post never computes CIDs itself. `createdAt` grows by one millisecond per thread part to keep the order. An interrupted thread is visible up to its last published part until the retry publishes the rest.
- N25. Before a retry repeats the writes of the first unpublished part with the same keys, Post reads that part's key with `com.atproto.repo.getRecord`. If the record exists, the part counts as published and its URI and CID come from that answer. The check is needed because the reference PDS answers a create at an existing key with HTTP 500 `InternalServerError`, the same answer as a server fault: the repository's MST throws a plain error (`packages/repo/src/mst/mst.ts` in `bluesky-social/atproto` `7dd086d4de`; read, not run).
- N26. Uploaded blobs not referenced by a record are deleted after a while (the spec asks servers to wait at least an hour). If a retry happens later, Post uploads the media again.
- N27. Bluesky has no scheduled posting, so queued posts are always sent by the device. `createdAt` is the actual send time.

## Editing and deleting published posts

Requirements R37–R41 of product.md refer here.

Mastodon (docs.joinmastodon.org, read 2026-11-15):

- N28. Edit uses `PUT /api/v1/statuses/:id` (Mastodon 3.5+). It changes `status`, `spoiler_text`, `sensitive`, `language`, `media_ids[]`, `media_attributes[]` (alt text and focus of attached media) and `poll`; 4.5 adds `quote_approval_policy`. Visibility and the reply target cannot change, so the composer locks them while editing. Changing poll options resets its votes; the composer warns about it.
- N29. Edit of a thread re-splits the new text (threads.md). Parts whose text and media did not change are not sent. Changed parts are edited in place. Extra parts are posted as replies to the last remaining part. Surplus parts are deleted, last first.
- N30. Media of an edited post: attachments kept from the published post are passed by their media ids; new ones are uploaded; removed ones are left out of `media_ids[]`.
- N31. Delete uses `DELETE /api/v1/statuses/:id`. Deletion is limited to 30 requests per 30 minutes, so a long thread may take longer; the queue waits on HTTP 429. A server without edit support (older than 3.5, or a fork answering 404 or 405 to `PUT`) gets only Delete and "delete and post again".
  - Decision of 2026-11-17 (#3): the account keeps `canEdit`. Sign-in sets it from the server version: false for vanilla Mastodon and Hometown older than 3.5 (`lib/mastodon/version.rb`; Hometown writes the Mastodon release first, `3.4.6+hometown-1.0.8`). Any other software is taken as able to edit, because its release numbers are its own: GoToSocial 0.x has edited statuses since 2024 (`PUT /api/v1/statuses/{id}`, `superseriousbusiness/gotosocial` #3628). The answer to `PUT` refines it: 405 means the server cannot edit; on 404 the app asks `GET /api/v1/statuses/:id`, where 200 means it cannot edit and 404 means the status was deleted elsewhere.
  - The edit composer offers "Leave unchanged" (the default) or "Delete and post again" for a Mastodon account whose server cannot edit, as for Bluesky (N32). The executor deletes the old thread first, last part first, and then posts the new one. A status takes a media attachment only while it belongs to no status (`PostStatusService#validate_media!`, `app/services/post_status_service.rb`, read 2026-11-17), so deleting first releases the kept files, which go out again by their media ids (N30). Mastodon's own delete and redraft does the same (`DELETE ... delete_media=false`, `app/javascript/mastodon/actions/statuses.js`). Deleting a status detaches its media without deleting the files since 3.0 (`RemovalWorker ... redraft: true`; `has_many :media_attachments, dependent: :nullify` since 2.9).
  - The detach runs in a background job, so the new status may be refused with 422 for the media it names; the app waits and tries again. A changed alt text of a kept file is set with `PUT /api/v1/media/:id` once the file is free. A thread whose old copy was deleted before the new one failed is gone; the edit stays in the Failed group, and a retry goes on with the new parts already posted.

Bluesky:

- N32. Bluesky has no edit in Post. The official app offers none. A post record can technically be replaced with `putRecord`, and the reference AppView then re-indexes it (`bluesky-social/atproto` `7dd086d4de`, `packages/bsky/src/data-plane/server/indexing/processor.ts`). But the new version gets a new CID, while replies, likes and reposts point to the old one. How the production AppView and apps show that is not verified. When a Bluesky account is among the targets of an edit, the composer offers for it: "Leave unchanged" (default) or "Delete and post again", with a warning that likes, reposts and replies are lost.
  - Decision of 2026-11-16 (#2): "Delete and post again" posts the new thread first and deletes the old one only after the new one is up. The new records embed the images and video of the old thread by the blob references history keeps (R38), so nothing is uploaded again. The reference PDS keeps a blob while any record of the repo refers to it, and checks MIME type and size only when a blob is first referenced (`bluesky-social/atproto` `8ed6d3d48b`, `packages/pds/src/actor-store/blob/transactor.ts`, read, not run). The cost is a few seconds with both threads online.
  - A part of the new thread embeds the files of the old part at the same place first, then its new attachments. The "Published media" list applies: a removed file is left out, an edited alt text is sent. Files of old parts past the end of the new thread are not posted again, as with Mastodon (N29).
  - Decision of 2026-11-17: when the server refuses the new thread for good, the parts of it already posted are deleted again, and the old thread stays as it was. After a temporary error (network, 5xx, 429) the retry goes on with the parts already posted. Until the old thread is deleted, the record names it; if the user deletes a waiting edit from the Queue, the parts of the new thread already posted stay online without a record.
  - A file recorded before history kept its blob reference cannot be embedded again. The composer warns about it for that account.
- N33. Delete uses `com.atproto.repo.deleteRecord` for every part and for the threadgate and postgate records of the thread, in one `applyWrites` call when the thread fits its 200-write limit.

## Mapping of common options

| Post option | Mastodon | Bluesky |
|---|---|---|
| Text | `status` | `text` + facets |
| Language | `language` | `langs[0]` |
| Content warning text | `spoiler_text` | not supported; the composer warns that it will be dropped |
| Sensitive media | `sensitive` | self-label chosen by the user |
| Alt text | `description` | `alt` |
| Thread part | `in_reply_to_id` | `reply{root, parent}` |
| Who can reply | not supported | threadgate |
| Who may quote this post | `quote_approval_policy` (4.5+) | postgate |
