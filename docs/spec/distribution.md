# Post: distribution

Post is distributed only through [Obtainium](https://github.com/ImranR98/Obtainium) from GitHub Releases of `github.com/juev/post`. It is not published on Google Play or F-Droid.

Facts about Obtainium are from its wiki (wiki.obtainium.imranr.dev) and source (`main` at `35c3e7f`), read 2026-11-15.

## How Obtainium sees a release

- By default Obtainium compares the version string of the latest release (its tag, or its name when there is no tag) with the `versionName` of the installed app. A switch compares `versionCode` instead.
- Prereleases are skipped unless the user turns on "include prereleases".
- Without "fallback to older releases", a latest release without a matching APK blocks updates.
- With several APKs in one release, Obtainium asks the user to pick one, unless an APK filter regex is set.
- Obtainium warns and skips the install when the APK is signed with a different certificate than the installed app.

## Rules

- D1. Every release, including release candidates and betas, raises `versionCode`. A final version gets a higher code than all of its candidates. The build derives `versionCode` from the version and fails on a tag that does not produce a new code.
- D2. The tag is `v<versionName>`, for example `v0.1.0` for `versionName` `0.1.0`. Release candidates are `v0.2.0-rc.1` and are published as GitHub prereleases.
- D3. Each release has exactly one universal APK, named `post-<versionName>.apk`, so Obtainium needs no filter.
- D4. The release APK is signed with one release key for the life of the app. The key and its passwords are kept outside the repository (Bitwarden, folder "Android signing", as for the other apps).
- D5. GitHub Actions builds and publishes the release from a pushed tag. The release notes list the changes.
- D6. The README has an "Add to Obtainium" badge with the link `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/juev/post`, and states the minimum Android version and the signing certificate SHA-256 so users can set "Expected signing certificate hashes".

## Open questions

- Whether the debug or QA build gets a separate application ID suffix (Brook uses `.qa`).
