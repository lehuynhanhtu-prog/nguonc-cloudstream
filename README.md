# NguonC CloudStream Extension

CloudStream Android extension for the NguonC film API.

## Installation

After the **Build and publish NguonC** workflow has completed successfully, add this repository URL in CloudStream's extension repository settings:

```
https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/repo.json
```

Select the NguonC extension to install. Future extension releases are independent of the CloudStream Android APK. CloudStream may require an extension refresh or update confirmation depending on its settings.

## Development and releases

Pull requests are compiled by GitHub Actions before changes are merged to `main`.

- Source: `NguonC/src/main/kotlin/com/nguonc/`
- Build: `gradle make makePluginsJson`
- GitHub Actions: `.github/workflows/build.yml`
- Publishing: successful builds push `.cs3`, `plugins.json`, and `repo.json` to branch `builds`.
- Update `version` in `NguonC/build.gradle.kts` for a new extension release.


## MyStream (independent extension)

MyStream is a separate CloudStream extension in the same repository. It does not modify the NguonC provider or its playback code.

- Extension source: `MyStream/src/main/kotlin/com/mystream/`
- Editable catalog: `catalog/mystream.json`
- Published catalog: `https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/mystream.json`
- Supported direct media: MP4 and HLS (`.m3u8`)
- Supports movies, episodic series, poster/plot/year/tags, multiple sources, per-source headers/referer, and subtitles.

A public sample item is included only to verify installation and playback. To add your own media, edit `catalog/mystream.json`; the extension fetches the catalog remotely so ordinary catalog changes do not require a MyStream version bump.

## Validation

The provider uses NguonC API endpoints for lists, search, film details, and episode streams. API availability, Gradle compatibility, and Android playback have **not yet been independently verified**. Check the Actions tab for build errors before using the repository URL.

This extension is independent and is not an official NguonC or CloudStream release. Only access media you have rights to view.
