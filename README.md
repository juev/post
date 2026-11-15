# Post

<img src="docs/icon.png" width="96" align="right" alt="The icon of Post: a paper plane with two diverging trails">

[![CI](https://github.com/juev/post/actions/workflows/ci.yml/badge.svg)](https://github.com/juev/post/actions/workflows/ci.yml)

Post is an Android app that publishes one message to several Bluesky and Mastodon accounts at once. You write the text in Markdown, attach images, video or audio once, and the app adapts the post to each account: it converts the formatting to what the server accepts, splits long text into a thread, and resizes or transcodes media to fit the network's limits. A post goes out right away, at a time you pick, or in the next free slot of a weekly schedule. Post has no server of its own: it talks to your accounts' servers directly.

[![Get it on Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/juev/post)

## Features

- Several Bluesky and Mastodon accounts. Mastodon here means any server with the Mastodon client API: glitch-soc, GoToSocial, Pleroma, Akkoma, Hometown and others. Limits and accepted formats are read from each server.
- Targets per post: every account is a chip in the composer, and the default targets from Settings are preselected. Each account has default options (visibility, language, content warning, reply settings, sensitive flag) that one post can change.
- Network options: Mastodon visibility, content warning, polls and quote policy; Bluesky languages, content labels, who can reply and whether others may quote.
- Markdown with live highlighting and a formatting toolbar above the keyboard. Each account gets Markdown or HTML when its server accepts them, and plain text with the markup removed otherwise. On Bluesky, links become link facets.
- Automatic threads: text over an account's limit is split at paragraph and sentence boundaries into balanced parts, with no short leftover at the end. A preview shows the parts for each network, and you can add manual breaks.
- Media adaptation: images are converted and downscaled to fit, location data is removed by default, and video is transcoded on the device when a network does not accept it as it is. When something cannot fit (too many files, audio for Bluesky, video mixed with images), the composer shows the conflict for that network and offers fixes, such as moving files to a reply.
- Send now or later: a chosen date and time, or the next free slot of a weekly schedule. The queue lists scheduled and failed posts; drag slot-based posts to change their order.
- Late posts are held: a post that missed its time by more than a threshold (30 minutes by default), for example because the phone was off, is never sent on its own. A notification asks whether to send it now, move it to the next free slots, or review it.
- Sending from other apps through the Share menu and the Direct Share row: send now, send later, or open the text in the composer.
- Reliable sending: a post is saved on the device before any request, failed requests are retried, and a repeated send does not create a duplicate.
- A short history of sent posts (30 days by default) with links to each published copy. From the history you can edit a published post (Mastodon edits in place; Bluesky offers to delete and post again) or delete it from some or all accounts.
- English and Russian interface.

## Requirements

Android 13 (API 33) or later.

## Install and update

Post is distributed only through GitHub Releases of this repository. It is not on Google Play or F-Droid.

### With Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs the app from GitHub Releases and checks for updates.

1. Open the "Get it on Obtainium" link above on the phone, or choose "Add app" in Obtainium and enter `https://github.com/juev/post` as the source URL.
2. Release candidates, such as `v0.1.0-rc.1`, are published as prereleases. To get them, turn on "Include prereleases" in the app's settings in Obtainium. Without it, Obtainium installs only final releases.
3. To have Obtainium refuse an APK signed by anyone else, paste the certificate hash below into "Expected signing certificate hashes".

Every release has exactly one APK, `post-<version>.apk`, so Obtainium needs no APK filter.

### By hand

Download `post-<version>.apk` of the latest release from [Releases](https://github.com/juev/post/releases) and open it on the phone. Android asks to allow installs from the browser or file manager you use. To update, install the APK of a newer release over the old one.

### Signing certificate

Every release APK is signed with the same key. The SHA-256 fingerprint of its certificate:

```
0D:06:40:FD:9B:05:E2:B4:82:B9:26:0A:D2:D2:6D:1A:C7:54:1F:8D:D5:D9:89:45:23:9B:DA:59:CB:24:FF:57
```

To check a downloaded APK yourself, run `apksigner verify --print-certs post-<version>.apk` from the Android SDK build tools: the line `Signer #1 certificate SHA-256 digest` shows the same value in lowercase without colons.

## Signing in

- **Mastodon.** Enter the address of your server. Post opens its sign-in page in the browser, and you allow access there. Post never sees your password: the server gives it a token with access limited to posting and reading your profile.
- **Bluesky.** Enter your handle and an app password. Create the app password at [bsky.app/settings/app-passwords](https://bsky.app/settings/app-passwords). An app password is separate from your main password: you can revoke it at any time without changing the main one, and Post never learns the main one. Post keeps only the session tokens it gets with the app password, not the password itself. Sign-in with Bluesky OAuth is planned for a later version.

Tokens are stored on the device, encrypted with a key in Android Keystore.

## Permissions

<!-- To re-check against the final app/src/main/AndroidManifest.xml before the release. -->

- **Internet**: to publish to your accounts' servers.
- **Notifications**: progress of long uploads, failed deliveries, posts that missed their time and, if you turn it on, a summary of successful sends. Android 13 and later ask for it the first time you send.
- **Exact alarms** (`USE_EXACT_ALARM`): to send a scheduled post at its minute even when the phone is idle. Android's ordinary background jobs can be delayed by many minutes in Doze.
- **Run at startup**: alarms are lost on reboot, so the app sets the next one again after the phone starts and checks for posts that missed their time.
- **Foreground service**: long video uploads and transcoding run with a notification so Android does not stop them halfway.

## Privacy

Post sends no analytics and has no server of its own. It contacts only your accounts' servers and the few Bluesky services and pages that sign-in, mentions, video and link cards need. Accounts, drafts, the queue and the history stay on the device and are excluded from Android backup. Details are in [docs/privacy.md](docs/privacy.md).

## Building from source

You need JDK 21 and the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```sh
./gradlew assembleDebug                            # debug APK in app/build/outputs/apk/debug/
./gradlew :core:test testDebugUnitTest lintDebug   # JVM and Robolectric tests, lint
./gradlew connectedDebugAndroidTest                # tests on a running emulator or device
```

`./gradlew assembleRelease` builds the release APK with R8. It is signed only when these environment variables are set:

| Variable | Value |
|---|---|
| `POST_KEYSTORE` | path to the keystore file |
| `POST_KEYSTORE_PASSWORD` | keystore password |
| `POST_KEY_ALIAS` | alias of the release key |
| `POST_KEY_PASSWORD` | password of the key |

Without them the release APK is unsigned.

### Versions and releases

The version and its code are in `gradle.properties` (`post.version`, `post.versionCode`). The code is derived from the version as `((major * 100 + minor) * 100 + patch) * 100 + rc`, where `rc` is the release candidate number and 99 for a final release: `0.1.0-rc.1` is 10001 and `0.1.0` is 10099. The build fails when the written code differs from this.

To release, raise both values, merge, and push the tag `v<version>`, for example `v0.1.0-rc.1`. The [release workflow](.github/workflows/release.yml) then checks that the tag matches the version and that the code is greater than that of the previous release tag, builds and signs the APK, checks its certificate, and publishes `post-<version>.apk` as a GitHub release. Tags with a suffix, such as `-rc.1`, become prereleases.

## License

[MIT](LICENSE).
