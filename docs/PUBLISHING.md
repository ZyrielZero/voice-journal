# Publishing Voice Journal

What has to happen to get a release onto F-Droid and Google Play, in order. The store
listing itself (title, descriptions, changelog, icon, feature graphic, screenshots) lives
in [`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US) and is shared
by both stores.

## 1. Cut the release

F-Droid reads the listing from the tagged source, so the fastlane metadata must be in the
commit that gets tagged.

1. Commit on `release-1.1`, merge into `main`, wait for CI to go green.
2. `git tag v1.1.0 && git push origin v1.1.0`. The Release workflow builds the APK, checks
   it is signed with the pinned certificate, and attaches `voice-journal-1.1.0.apk` to a
   GitHub release.
3. Note the full commit hash of the tag (`git rev-parse v1.1.0^{commit}`); the F-Droid
   recipe needs it.

## 2. F-Droid

No fee, no testers. The recipe draft is
[`docs/fdroid/dev.zyriel.voicejournal.yml`](fdroid/dev.zyriel.voicejournal.yml).

1. Fork <https://gitlab.com/fdroid/fdroiddata> and copy the draft to
   `metadata/dev.zyriel.voicejournal.yml`. Replace the `commit:` placeholder with the hash
   from step 1.3.
2. In the fork, run `fdroid lint dev.zyriel.voicejournal` and
   `fdroid rewritemeta dev.zyriel.voicejournal`. The fork's own CI then does a full build;
   that is the first real test of the recipe (see "Not yet verified" below).
3. Open a merge request using the "App inclusion" template and answer its checklist.

What the reviewers will ask about, and the answers:

- **Models fetched at build time.** `scripts/fetch-models.sh` downloads four files from
  Hugging Face at pinned commits and checks each against `scripts/models.sha256`. All are
  MIT-licensed (whisper.cpp `base.en`, silero VAD, gte-base and its vocabulary). They are
  listed under `scanignore` because the scanner flags `.bin` and `.onnx` files.
- **ONNX Runtime** comes from Maven Central as a prebuilt AAR (MIT). Its manifest asks for
  network access and registers a telemetry initializer; both are removed, and the build
  fails if they come back (`verifyNoNetwork`).
- **No anti-features.** No network, no tracking, no non-free dependencies.
- **Reproducible build.** The recipe has `Binaries:` and `AllowedAPKSigningKeys:`, so
  F-Droid builds from source, compares with the GitHub release APK, and publishes the APK
  signed with this project's key. F-Droid, GitHub and Obtainium installs then update each
  other. If their build does not match, delete those two lines: F-Droid will sign with its
  own key and the app is still published, but F-Droid installs cannot cross-update with
  GitHub ones.
  Checked so far: two builds of the same tree in different directories on one machine
  give byte-identical unsigned APKs. Not yet checked: a GitHub Actions build against an
  F-Droid build, which also differ in OS image and SDK location.

Not yet verified: the recipe passes `fdroid lint` and `rewritemeta`, but it has not been
through a real F-Droid build. The `sudo` block (JDK 21) and the `sdkmanager` line (CMake)
are the parts most likely to need adjusting to the current build server image.

## 3. Google Play

One-time setup: a developer account ($25, identity verification), and for a paid app a
payments profile. A personal account created after 13 November 2023 must run a closed
test with 12 testers opted in for 14 consecutive days before it can apply for production.

1. **Create the app** (name "Voice Journal", app, paid or free: this cannot be changed
   from free to paid later).
2. **Signing.** When creating the first release, choose to use your own key for Play App
   Signing and upload the existing release key. Otherwise Play signs with a key it
   generates, and Play installs can never update to or from the GitHub and F-Droid builds.
3. **Build the bundle** with the release keystore in place (`keystore.properties`):
   `./gradlew bundleRelease`, then upload `app/build/outputs/bundle/release/app-release.aab`.
   The bundle is about 172 MB. Play's limit is 200 MB of compressed download for the base
   module and the Console shows the measured figure after upload; if it is over, the models
   have to move into an install-time asset pack.
4. **Store listing.** Paste from `fastlane/metadata/android/en-US`: `title.txt`,
   `short_description.txt`, `full_description.txt`, `images/icon.png` (512 x 512),
   `images/featureGraphic.png` (1024 x 500), `images/phoneScreenshots/*` (1080 x 2160).
   Category: Productivity.
5. **App content** answers:
   - Privacy policy: the GitHub Pages copy of [`docs/index.html`](index.html).
   - Ads: none.
   - App access: all functionality is available without special access (no login; the app
     lock uses the device's own screen lock).
   - Data safety: no data collected, no data shared. Audio is processed on the device and
     never transmitted, which Play does not count as collection.
   - Content rating: utility/productivity, no user-to-user content.
   - Target audience: 18 and over is the simplest truthful choice for a journal.
   - Foreground service declarations, one per type, each with a short screen recording:
     - `microphone`: the user taps Record; recording continues with the screen off, with
       an ongoing notification and a Stop action.
     - `mediaProcessing`: on launch, a recording that was interrupted (crash, call, full
       disk) is transcribed on the device.
     - `dataSync`: the same recovery on Android 14 and older, where the `mediaProcessing`
       type does not exist. Nothing is synchronised. Expect questions on this one.
6. **Closed test.** Create a closed testing track, add a Google Group or email list as
   testers, publish the release to it, and share the opt-in link. Recruit 15 to 20 so a
   dropout does not matter. Sources: tester-swap communities (r/AndroidClosedTesting),
   privacy and FOSS communities, existing Obtainium users, friends.
7. **Apply for production** after 14 days. The form asks how testers were recruited, what
   feedback came in and what changed because of it; keep notes during the test.

## 4. Android developer verification

Google's developer verification programme covers apps installed outside Play as well.
A verified Play Console account can register a package name and signing key; whether
F-Droid and GitHub installs need that depends on the rules in force when you publish.
Check them before relying on F-Droid alone.

## The signing key

The release key lives outside the repo in `~/voice-journal-signing` (keystore and
`keystore.properties`), with a copy of the properties file, git-ignored, in the repo root.
The same key is in the repository's Actions secrets. Keep a backup off this computer: the
1.0.0 key was lost, which is why 1.1.0 cannot update a 1.0.0 install. If it ever has to be
replaced again, `scripts/new-release-key.sh` creates a new one, and the fingerprint it
prints has to be pinned in `release.yml`, the README and the F-Droid recipe.

## Updating later

- Each release: add `changelogs/<versionCode>.txt` (500 characters at most), bump
  `versionCode` and `versionName`, tag. F-Droid picks up new `v*` tags by itself.
- Screenshots were taken from the debug build with "Hide in recents" switched off and
  cropped to 1080 x 2160 (Play rejects images taller than 2:1).
