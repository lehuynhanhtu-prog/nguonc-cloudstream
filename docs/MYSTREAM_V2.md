# MyStream v2

MyStream v2 is an independent CloudStream extension for direct media catalogs that you control.

## Supported media

- HLS (`.m3u8`)
- MPEG-DASH (`.mpd`)
- Direct video files such as MP4
- Multiple servers per movie or episode
- Per-source `Referer` and custom HTTP headers
- External subtitles
- Movie, series and anime catalogs
- Multiple seasons and episodes
- Multiple JSON feeds loaded through `mystream-feeds.json`

## Home screen

The extension automatically creates rows for:

- Nổi bật
- Mới cập nhật
- Custom `section` values
- Phim lẻ
- Phim bộ
- Anime
- Up to 12 genres detected from the catalog

## Files

- `catalog/mystream.json`: primary catalog
- `catalog/mystream-feeds.json`: feed index
- `catalog/mystream.template.json`: disabled examples for movie and series schema

Catalog-only changes do not require a MyStream version bump. After the GitHub Actions publish job completes, CloudStream will fetch the new JSON catalog automatically (the provider keeps only a short in-memory cache).

## Adding another feed

Add an entry to `mystream-feeds.json`:

```json
{
  "name": "Kho phụ",
  "url": "https://example.com/my-catalog.json",
  "enabled": true
}
```

Relative URLs are resolved from the repository `builds` root. Items with the same `id` are merged in feed order; a later feed replaces an earlier item with the same id.

Use only direct media or catalog URLs you are authorized to access.
