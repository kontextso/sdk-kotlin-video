# Releasing

- This document describes the process for cutting a new release of the **Kontext Kotlin Video SDK**.
- Replace version `1.0.0` with the proper one instead.

> We use versioning without a `v` prefix (e.g. `1.0.0`, not `v1.0.0`).

---

## 1. Test on `main`

The release is cut from `main`, as every change is (AGENTS.md: no feature branches).

1. Checkout branch `main` and pull the latest changes; CI on its last commit is green (the checks below, the
   emulator tests and the minified publisher app).
2. Run CI's checks locally and make sure everything is green (the release workflow runs them before it publishes):
   ```bash
   ./gradlew spotlessCheck detekt :video:lintRelease :video-media3:lintRelease assembleDebug apiCheck :video:testDebugUnitTest :video-media3:testDebugUnitTest
   ```
4. Run the example against the ad server on a device: each of the four videos shows its ad, and the status line
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
The `publish` job runs only after them, in the `maven-central` environment: a required reviewer approves it in
the workflow run, then it publishes and releases to Maven Central, which cannot be undone.

The environment must exist in the repository settings (Settings → Environments → `maven-central`, with
required reviewers); the workflow does not create its protection. The Maven Central and signing secrets
(`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`) can live there; the
workflow passes them to Gradle as `ORG_GRADLE_PROJECT_*` environment variables.

```bash
git checkout main
git pull
git tag -a 1.0.0 -m "Release 1.0.0"
git push origin 1.0.0
```

## 6. Verify

1. Check that `so.kontext:video:1.0.0` is available on Maven Central.
2. Integrate the new version into the example app and confirm it builds and runs.
