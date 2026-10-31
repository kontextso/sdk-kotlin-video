# Releasing

- This document describes the process for cutting a new release of the **Kontext Kotlin Video SDK**.
- Replace version `1.0.0` with the proper one instead.

> We use versioning without a `v` prefix (e.g. `1.0.0`, not `v1.0.0`).

---

## 1. Create a release branch and test

1. Checkout branch `main` and pull the latest changes
2. Create a new branch `release/1.0.0`
3. Run the full CI sequence and make sure everything is green:
   ```bash
   ./gradlew spotlessCheck detekt assembleDebug :video:testDebug
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

## 4. Commit and open a pull request

```bash
git add CHANGELOG.md gradle/libs.versions.toml
git commit -m "Prepare release 1.0.0"
```

Create a PR to `main` named "Release version 1.0.0" with the changelog entry as the description, and merge it.

## 5. Create an annotated tag

Pushing the tag triggers the `publish_sdk.yml` workflow, which publishes to Maven Central.

```bash
git checkout main
git pull
git tag -a 1.0.0 -m "Release 1.0.0"
git push origin 1.0.0
```

## 6. Verify

1. Check that `so.kontext:video:1.0.0` is available on Maven Central.
2. Integrate the new version into the example app and confirm it builds and runs.
