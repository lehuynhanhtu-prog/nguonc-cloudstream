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


## MyStream v3 (independent extension)

MyStream is a separate CloudStream extension. NguonC v7 is not used by MyStream and its playback code remains independent.

### Features

- Home sections are generated from local feeds, plus a paginated Internet Archive feature-film catalog.
- Real pagination: the public movie catalog loads 30 entries per page and continues as the user scrolls.
- Search combines local MyStream feeds with the remote public movie index.
- Movie, TV series and Anime entries.
- Seasons and episodes with independent sources/subtitles per episode.
- Multiple playback sources per movie/episode.
- Direct MP4/video, HLS (`.m3u8`), DASH (`.mpd`) and CloudStream-supported embed/extractor links.
- Per-source `Referer` and custom HTTP headers.
- Remote JSON catalog caching with automatic refresh.
- Catalog validation in GitHub Actions before every build.

### Files

- Extension source: `MyStream/src/main/kotlin/com/mystream/`
- Editable catalog: `catalog/mystream.json`
- Published catalog: `https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/mystream.json`
- Published plugin: `https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/MyStream.cs3`
- Dynamic public catalog: Internet Archive `feature_films`, fetched at runtime with pagination; movie metadata is resolved on demand and playable MP4 files are selected from item metadata.

### Catalog format

Movie source example:

```json
{
  "id": "my-movie",
  "title": "My Movie",
  "type": "movie",
  "year": 2026,
  "updatedAt": "2026-11-04",
  "featured": true,
  "poster": "https://example.com/poster.jpg",
  "genres": ["Action"],
  "tags": ["HD"],
  "sources": [
    {
      "name": "HLS 1080p",
      "url": "https://example.com/master.m3u8",
      "type": "hls",
      "quality": "1080p",
      "referer": "https://example.com/"
    }
  ],
  "subtitles": [
    {
      "lang": "vi",
      "url": "https://example.com/subtitle.vtt"
    }
  ]
}
```

For series/anime, put playback sources in each `episodes[]` entry and set `season`, `episode`, and `name`. For supported embed hosts, use `"type": "embed"` or `"type": "extractor"`; MyStream delegates extraction to CloudStream's registered extractors.

The shipped catalog contains public test streams so installation and playback can be verified safely. Replace or extend these entries with media URLs you are authorized to use. Catalog-only changes are published without requiring a MyStream code change; bump the MyStream plugin version only when plugin behavior changes.

## Validation

NguonC and MyStream are built by GitHub Actions. Pull requests also run Android E2E playback tests in official CloudStream 4.8.0; MyStream's catalog is validated before compilation.

This extension is independent and is not an official NguonC or CloudStream release. Only access media you have rights to view.
