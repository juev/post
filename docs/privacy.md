# Post privacy note

Post is an Android app that publishes posts to your Bluesky and Mastodon accounts. It has no server of its own: everything it knows stays on your device, and it sends your posts straight to the servers of your accounts. Questions: [denis@evsyukov.org](mailto:denis@evsyukov.org).

## Data on the device

Post keeps in its private app storage:

- **Accounts**: server address, handle, display name, avatar address, your label for the account, its default options, and the limits and formats read from its server.
- **Tokens**: the Mastodon access token and the Bluesky session tokens. They are encrypted with a key kept in Android Keystore; the database holds only the encrypted form. The Bluesky app password is used once to sign in and is not stored. Tokens are never logged and never sent to any host other than the account's own server.
- **Drafts and the queue**: text, options, target accounts, send times and the state of each delivery.
- **Attachments**: files you attach or share are copied into app storage, together with their alt text and converted copies. The copies are deleted as soon as the post has been delivered to every target account.
- **History of sent posts**: text, send time, options, and for each account the ids and links of the published copies, so you can open, edit or delete them. Media files are not kept. Records are deleted after 30 days by default; in Settings you can choose 7 or 90 days, or turn history off.
- **Settings and the weekly schedule.**

Removing the app deletes all of it.

## What Post does not do

- No analytics, crash reporting, advertising or tracking of any kind.
- No backup: app data is excluded from Android cloud backup and from device-to-device transfer. After moving to a new phone, you sign in to your accounts again.
- No account or service run by the developer. Nothing goes to the developer.

## Hosts Post contacts

Post makes network requests only to these hosts, and only for the reasons given:

- **The servers of your accounts** (Mastodon instances, Bluesky PDSes): sign-in, reading limits and formats, uploading media, publishing, editing and deleting posts.
- **For Bluesky sign-in and mentions**:
  - `public.api.bsky.app`, to resolve a handle to its DID;
  - `plc.directory`, or the domain of a `did:web` identity, for the DID document that names the account's server;
  - the domain of the handle itself, for `/.well-known/atproto-did`, when the handle is not resolved otherwise.
- **For Bluesky video**: `video.bsky.app`, which receives and processes videos before they are attached to a post.
- **For Bluesky link cards**: the page of the first link in a post without media, and its preview image, to get the title, description and thumbnail of the card. You can remove the card before sending.

Mastodon builds link previews on its own server, so Post fetches no pages for Mastodon posts.

What each server does with your posts is governed by that server's own policy.

This note will be updated when the app's data handling changes.
