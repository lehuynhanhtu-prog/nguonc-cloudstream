# NguonC CloudStream Extension

CloudStream Android extension for the NguonC film API.

## Installation

After the **Build and publish NguonC** workflow has completed successfully, add this repository URL in CloudStream's extension repository settings:

```
https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/repo.json
```

Select the NguonC extension to install. Future extension releases are independent of the CloudStream Android APK. CloudStream may require an extension refresh or update confirmation depending on its settings.

## Development and releases

- Source: `NguonC/src/main/kotlin/com/nguonc/`
- Build: `gradle make makePluginsJson`
- GitHub Actions: `.github/workflows/build.yml`
- Publishing: successful builds push `.cs3`, `plugins.json`, and `repo.json` to branch `builds`.
- Update `version` in `NguonC/build.gradle.kts` for a new extension release.

## Validation

The provider uses NguonC API endpoints for lists, search, film details, and episode streams. API availability, Gradle compatibility, and Android playback have **not yet been independently verified**. Check the Actions tab for build errors before using the repository URL.

This extension is independent and is not an official NguonC or CloudStream release. Only access media you have rights to view.
