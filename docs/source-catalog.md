# Maintaining a source catalog

Banana TV can use a source configuration that you maintain on a CDN. The configuration lists provider APIs and optional category mappings. Film metadata and video still come from those providers; hosting this JSON does not accelerate their video streams.

The bundled catalog has one source of truth: [media-sources.json](../app/src/main/assets/media-sources.json). You can upload that exact file as a public JSON object. Keep account credentials and private subscriptions out of a public catalog.

## Private address and phone setup

The app ships without an owner-specific feed URL. Enter your address once in **影视 → 设置 → 来源配置 → 扫码配置来源**. Connect a phone to the same Wi-Fi, scan the TV QR code, paste the address and choose **保存到电视**. The TV downloads and validates the configuration; the phone shows success only after activation. Failed imports preserve the previous configuration. Manual TV entry remains available through **添加来源**.

The QR page uses a random session token, accepts local-network peers and same-origin requests, and expires after ten minutes. Closing the QR window or leaving the media screen stops it. It does not upload the configuration address to a Banana TV service. The address and cached configuration live in private app storage. Do not include personal addresses or access tokens in source, screenshots, release notes or default assets.

## Automatic updates

For an active custom configuration, automatic checking is enabled by default. Opening the media browser checks the saved URL when the last successful check is at least 24 hours old. A check has a three-second network timeout and finishes before provider browsing begins; it does not replace source objects during a search or playback. Failed, empty or malformed responses retain the last valid copy. After failure, another media-browser entry can retry after one hour. There is no always-running timer or background service.

**来源配置 → 自动更新** can disable checking. **更新配置** always requests the saved URL immediately. Built-in sources remain offline and change with the APK. Keep the URL and provider keys stable to retain source choices, selected source and playback history.

## Prepare and distribute

The simplest deployment is a small Cloudflare Worker serving one static JSON file. No film-index database or home computer is required. The preparation tool reads the bundled catalog by default:

```sh
python3 tools/prepare_source_catalog.py --output /path/to/private/catalog-revision
```

To combine reviewed local configurations, pass them before `--output`. The tool merges type 1/4 HTTPS JSON APIs, deduplicates identical APIs and reports skipped adapters in `report.json`. Conflicting provider keys are rejected; assign distinct stable keys before merging. Type 0 XML and type 3 executable adapters need separate compatibility tests and are not silently included. `ext`, headers, category mappings and same-provider mirrors are retained. Review input for credentials and redistribution rights before deployment. Structural validation is not a playback test.

The generated directory contains `wrangler.json`, `worker.js`, `report.json` and `public/`. By default, only `public/sources.json` and response-header rules are asset inputs. The Worker requires a random `SOURCE_TOKEN` secret for `/<SOURCE_TOKEN>/sources.json`; the ordinary `/sources.json` path returns 404. Keep the secret outside Git and enter the full private URL yourself on the phone. Anyone holding the full URL can read the catalog, so do not share it publicly.

For a single snapshot whose adapters have already passed playback checks, use explicit selection rather than the JSON-only merger:

```sh
python3 tools/prepare_source_catalog.py \
  --reviewed-snapshot /path/to/private/reviewed.json \
  --origin https://config.example/pinned/sources.json \
  --site tested-provider --site another-tested-provider \
  --output /path/to/private/catalog-revision
```

This mode preserves the snapshot's shared JAR, parsers, flags and site extensions, resolves relative references against `--origin`, and rejects missing selections. It does not establish compatibility or combine unrelated script hosts. Keep pinned dependency versions and JAR checksums. To distribute reviewed dependency files, add `--dependencies /path/to/reviewed/files`; JAR, JS, Python and JSON files are copied beneath `public/assets/` with their relative paths. Configure the snapshot to reference the corresponding versioned `assets/` URLs. The Worker protects those paths under the same private token; direct `/assets/` requests return 404. Check redistribution permission before copying upstream files. No video files are included.

To try that prepared revision without publishing, run `python3 tools/serve_media_preview.py --revision /path/to/private/catalog-revision --origin-file /path/to/private/source-url.txt`. It binds only to the computer's loopback interface. Import `http://10.0.2.2:18468/sources.json` on an Android emulator; dependency URLs are rewritten for that local preview. This address is emulator-only and must not be entered on a physical TV.

After reviewing `report.json` and the exact JSON, use an authenticated Wrangler CLI:

```sh
npx wrangler deploy --dry-run --config /path/to/private/catalog-revision/wrangler.json
npx wrangler deploy --config /path/to/private/catalog-revision/wrangler.json
npx wrangler secret put SOURCE_TOKEN --config /path/to/private/catalog-revision/wrangler.json
```

The Worker returns 404 until the secret is set. Use a long random URL-safe value, not an account password. [Worker static assets](https://developers.cloudflare.com/workers/static-assets/) distribute the file, and [custom headers](https://developers.cloudflare.com/workers/static-assets/headers/) give the JSON a five-minute client cache. `run_worker_first` is required so direct asset requests cannot bypass the private path. Keep the deployment directory and receipt outside the public repository. No owner address is generated into an APK.

When changing sources, prepare a new revision, review changes and deploy it to the same Worker name with the same secret. Preserve the previous revision for rollback. Add sources only after checking home, categories, search, details and actual playback. One working source never establishes that every adapter in another configuration works. A stable feed removes repeated address entry; it does not fix incompatible adapters or unreachable video CDNs. Test a hosting endpoint from the intended network before relying on it; a Cloudflare test URL is not a guarantee for mainland connectivity.

## Category mappings

The UI shares `movie`, `series`, `anime`, `variety`, `documentary` and `other` categories. Provider IDs remain provider-specific. Chinese category aliases are mapped automatically. Parent queries on the tested MacCMS providers do not include genre children, and comma-separated category IDs are not supported. The app queries each native category separately, rotating through genres and their pages before direct parent entries.

An optional `categories` object overrides this mapping for one provider:

```json
{
  "sites": [
    {
      "key": "example",
      "name": "Example provider",
      "type": 1,
      "api": "https://provider.example/api.php/provide/vod/",
      "categories": {
        "movie": ["provider-movie-id"],
        "series": ["provider-series-id"]
      }
    }
  ]
}
```

Replace the example API and IDs with the provider's actual values. An omitted key uses automatic mapping; an empty array deliberately excludes that provider from the category. Each provider's enable/disable choice is saved locally per configuration, independently of the shared catalog.

If you want to maintain the actual film library, that is a separate service: collect or author metadata, assign stable film IDs and provide catalog/search/detail endpoints. Static JSON snapshots can be distributed through a CDN, but search and freshness still need an indexing and update process. Hosting your own video also requires storage and delivery for the media you own or are authorized to distribute.
