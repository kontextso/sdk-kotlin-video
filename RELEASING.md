# Releasing

- This document describes the process for cutting a new release of the **Kontext Kotlin Video SDK**.
- Replace version `1.0.0` with the proper one instead.

> We use versioning without a `v` prefix (e.g. `1.0.0`, not `v1.0.0`).

---

## 1. Test on `main`

The release is cut from `main`: the release's changelog can come in a release PR, and the tag goes on `main`.

1. Checkout branch `main` and pull the latest changes; CI on its last commit is green (the checks below, the
   emulator tests and the minified release check).
2. Run CI's checks locally and make sure everything is green (the release workflow runs them before it publishes):
   ```bash
   ./gradlew spotlessCheck detekt :video:lintRelease :video-media3:lintRelease :video-ttsdk:lintRelease :video-txplayer:lintRelease :video-compose:lintRelease assembleDebug apiCheck :video:testDebugUnitTest :video-media3:testDebugUnitTest :video-ttsdk:testDebugUnitTest :video-txplayer:testDebugUnitTest :video-compose:testDebugUnitTest
   ```
3. Run the example against the ad server on a device: each of the five videos shows its ad, and the status line
   shows the impression, then the one end.

## 2. Update the changelog

Edit `CHANGELOG.md` to include the new release notes at the top.

```markdown
## 1.0.0
* Add new feature.
* Fix some bug.
```

If the release contains breaking changes, add a `### Breaking` section before the bullet points.

## 3. Update the SDK version

In `gradle/libs.versions.toml`:

```toml
[versions]
sdk-kotlin-video = "1.0.0"
```

## 4. Commit to `main`

```bash
git add CHANGELOG.md gradle/libs.versions.toml
git commit -m "Prepare release 1.0.0"
git push origin main
```

Wait for CI on that commit to pass before tagging.

## 5. Create an annotated tag

Pushing the tag triggers the `publish_sdk.yml` workflow. Its `verify` job fails when the tag differs from
`sdk-kotlin-video` in `gradle/libs.versions.toml`, then runs CI's checks (above) and the unit tests.
The `publish` job runs only after them, in the `maven-central` environment, and publishes and releases to Maven
Central at once, which cannot be undone: nobody approves it in the run (GitHub does not offer required reviewers
on this repository's plan), so the checks above are the last gate before the tag is pushed.

The environment must exist in the repository settings (Settings → Environments → `maven-central`, limited to
tags `*.*.*`); the workflow does not create it. The Maven Central and signing secrets
(`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`) can live there; the
workflow passes them to Gradle as `ORG_GRADLE_PROJECT_*` environment variables.

```bash
git checkout main
git pull
git tag -a 1.0.0 -m "Release 1.0.0"
git push origin 1.0.0
```

## 6. Verify

1. After **Publish SDK**, the **Release consumer** workflow waits for `so.kontext:video:1.0.0` and
   `so.kontext:video-media3:1.0.0` on Maven Central, then builds `extras/release-check` from them, minified by
   R8. It must be green; rerun it by hand (Actions → Release consumer → Run workflow) with the version.
2. Integrate the new version into the example app and confirm it builds and runs.
