# Post: attachments across networks

Status: draft. Requirements R9–R11 of [product.md](product.md) refer here. Network limits come from [networks.md](networks.md).

Decisions of 2026-11-15: the user attaches files once. The app adapts each file to every target account by itself when the change keeps the content: resizing, recompressing, converting the format, transcoding video. When the content cannot fit without losing something (a file, a part of a thread, an account), the composer shows the conflict for that network and offers fixes. Sending waits until each conflict has a fix. Settings can hold a default fix for each kind of conflict.

## Model

- A1. An attachment is one original file copied into app storage when it is added (R9), with its alt text and, for images, a focus point.
- A2. For each target account the app builds a plan: which attachments go to which thread part, and which variant of each file is uploaded. A variant is the original or a converted copy.
- A3. The plan is rebuilt whenever attachments, targets or account limits change. The composer shows its result per network: nothing when every file goes as it is, a note when files are converted, a conflict when a choice is needed.
- A4. Variants are prepared when the post is saved for sending or queued, not at send time, so a failed conversion shows up while the user is still in the composer. They are deleted together with the original after all deliveries succeed.

## Adaptation without a choice

- A5. Images. HEIC, HEIF, AVIF and other types the server does not accept are converted to JPEG (PNG when the image has transparency). An image above the size or resolution limit is downscaled and recompressed until it fits: for Bluesky to 4000 px on the long side and 2 MB, for Mastodon to the instance's `image_matrix_limit` and `image_size_limit`.
- A6. Location data and other EXIF metadata are removed from images before upload. The orientation is applied to the pixels first. (Proposal: a setting to keep metadata, off by default.)
- A7. Video. A video in a format the network does not accept, or above its size limit, is transcoded on the device to MP4 (H.264 video, AAC audio) with Media3 Transformer. The target bitrate is chosen from the duration and the size limit; the resolution goes down to 1080p and then 720p when the bitrate would be too low. Transcoding runs in a foreground worker with a progress notification and can be cancelled.
- A8. A GIF goes to Mastodon as a GIF (the server converts it). It goes to Bluesky as a video with `presentation: gif` (networks.md, N21).
- A9. Alt text is shared by all networks. If it exceeds a network's limit (Mastodon's `description_limit`; 2000 characters in the official Bluesky app), the composer marks it, and the user shortens it.

## Conflicts that need a choice

| Conflict | Example | Fixes offered |
|---|---|---|
| More files than the network allows | 6 images for Mastodon (limit 4) | Move the extra images to a reply in a thread; remove files for this network; remove the account from targets |
| Type not supported | audio for Bluesky | Send the post without the file to this network; remove the account |
| Mixed types | a video together with images (not allowed in either network) | Move the video to a reply; remove the video for this network; remove the images for this network |
| Video longer than allowed | 14 minutes for Bluesky (10 minute cap in the official app) | Remove the video for this network; remove the account. The app does not trim video. |
| Video quota exhausted | Bluesky daily video limit reached (`getUploadLimits`) | Send later (the post moves to the next free slot after the quota resets); send without the video; remove the account |
| Poll with media | Mastodon does not allow both | Remove the poll or the media; move the media to a reply |
| Transcoding cannot fit the limit | a 40-minute video for 99 MB | Remove the video for this network; remove the account |

- A10. A fix applies to one network and to every account of that network in the targets, because accounts of one network share these rules. Limits that differ between Mastodon instances are checked per account: the strictest account of the network decides whether a conflict exists.
- A11. "Move to a reply" adds thread parts after the part the files belong to, with no text, and keeps the order of files. For Bluesky, 5 to 10 images do not conflict: they go in one gallery (networks.md, N19).
- A12. Settings hold a default fix for "more files than allowed" (ask, or move to a reply) and "type not supported" (ask, or send without the file). With a default set, the Share sheet's Send now and Send later stay available; the sheet lists what will change.
- A13. The Share sheet never resolves a conflict without a default fix or the user's choice. Without a default it shows the conflict and offers Open in composer.

## Verification scenarios

- Six images to one Mastodon and one Bluesky account: Bluesky gets a gallery of six; Mastodon asks; with "move to a reply" it gets four images and a reply with two.
- A 20 MB HEIC photo: Mastodon gets a JPEG under 16 MB, Bluesky a JPEG under 2 MB and 4000 px; neither keeps GPS data.
- A 300 MB MOV to Mastodon: transcoded to MP4 under 99 MB; progress shows in a notification; cancelling keeps the draft.
- An MP3 to Mastodon and Bluesky: Mastodon gets audio; Bluesky shows a conflict until the user picks "send without the file" or removes the account.
