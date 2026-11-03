# Release check

A small app that uses the SDK the way a publisher's app does: it gets `so.kontext:video` and
`so.kontext:video-media3` as published artifacts, not from this repo's source, and builds a release with R8
minification. It catches what the SDK's own build cannot see: a public API the integration guide uses that the
release lost, an artifact that does not resolve, or shrinking rules that strip code the SDK needs at runtime.

It is never shipped. It has one screen with a player that loads and shows an overlay; its log tag is
`ReleaseCheck`.

## When it runs

- **Every push and pull request** (`ci.yml`, job "Release check (R8)"): against the artifacts published to
  mavenLocal from that commit.
- **After a release** (`release-consumer.yml`): against the version on Maven Central, once it is there.

## Run it locally

```bash
./gradlew :video:publishToMavenLocal :video-media3:publishToMavenLocal
./gradlew -p extras/release-check assembleRelease          # or installRelease, on a running emulator

# A released version, from Maven Central
./gradlew -p extras/release-check assembleRelease -PsdkFromMavenCentral -PsdkVersion=0.1.0
```
