# Native media center

The unreleased source integrates FongMi's CatVod core into Banana TV. Home → **影视** opens a native browser and Media3 player in the same application. The separate FongMi app is not launched or required.

**影视** is a full-size Home tile, added to the first Dock position on upgrade. It follows the configured tile size and focus animation. Long-press to move it; existing app order and later user moves are retained.

## Use

1. Open **影视**. Public builds start with an empty library. Add your own trusted configuration through **设置 → 来源配置** or its phone QR page. Private builds can explicitly supply a local catalog; see [catalog setup](source-catalog.md).
2. Browse one combined home and shared movie, series, anime, variety, documentary and other categories. A second row restores the providers' available subcategories, such as action, comedy and regional series, with equivalent names combined. **全部** returns to the broad category. A provider without the selected subcategory contributes no unrelated films. Both rows stay above the scrolling poster grid during loading, empty results and failures. Press Up from the first poster row to return to the selected subcategory; Left/Right and Confirm switch directly. **搜索** searches all enabled searchable providers.
3. Open a title and choose a source, line and episode. Each provider keeps its own IDs and episode lists; switching providers resets line/episode selection. Details also look for matching entries in other enabled searchable providers and append source choices without replacing the episode controls. **继续观看** restores actual saved progress.
4. **设置 → 内容来源** enables or disables providers for home, categories and search. These choices are saved per configuration. **设置 → 来源配置** imports a configuration, switches bundled/custom configurations, or refreshes the saved custom URL. Invalid updates preserve the last valid copy.

Media settings, source choices, search, address entry, QR setup and player line selection use Banana TV panels with consistent remote focus, typography and spacing. Default buttons and text fields share the application style. Android keyboards, package installation and external TV settings remain system interfaces.

**添加来源** still accepts trusted HTTP(S) JSON configuration URLs. Validated custom configuration is saved in private app storage. The browser opens its local configuration immediately and checks the saved URL in the background at most daily, with a three-second timeout. A valid update is staged for the next browser entry, so it cannot replace sources used by a live search or player. A failed check retains the cached copy and may retry on another entry after one hour. **设置 → 来源配置 → 自动更新** can disable checking, and **更新配置** requests it immediately. **扫码配置来源** opens a temporary local phone page for entering your private address. No owner-specific URL is bundled. Failed imports preserve the previous configuration. Imported sources can execute code with the app's access; no personal source, account or credential is bundled or published.

The public catalog input is `app/catalog/public.json` and must remain empty. The build generates `assets/media-sources.json`; an explicit `-PprivateMediaSources=/path/to/private/sources.json` may replace its contents for a private build. Configurations, mirrors and executable source files are not supplied by the public build.

For JSON sites, an optional `mirrors` array lists up to two equivalent APIs from the same provider. HTTP failures, invalid JSON and invalid response shapes trigger the next endpoint. Each attempt is limited to seven seconds, with a twenty-second total request budget. The working endpoint is reused for that loaded source; search parameters and headers are retained. When every endpoint fails, the app shows retry and settings controls. It never treats another provider's IDs as equivalent or disables HTTPS checks.

For MacCMS JSON sites, use the provider's direct-media endpoint when available. A general endpoint can return an HLS line in search results but prepend a web-player line in its detail response. A `/from/DIRECT_LINE/` endpoint keeps the same direct line in both responses; use the provider's actual line identifier. Web-player pages use the bounded resolver described below and may still fail. Type 1 home loads category metadata and the detailed poster list separately.

HTTP media is supported for legacy sources and the loopback source proxy. HTTPS certificate and hostname verification remain enabled. Existing weather/aerial requests still use their own HTTPS-only URLs.

## Playback controls

With controls hidden, Confirm pauses or resumes, and Left/Right seeks ten seconds while preserving pause state. Up, Down or Menu opens the controls; normal focus navigation then reaches **选集**, **线路**, previous/next episode and the timeline. Back first hides the controls; a second Back returns to the film. Episode and line panels support direction keys, Confirm and Back.

## Source selection and recovery

**设置 → 优先选择较快来源** is on by default. A new play request checks a small set of matched source/line candidates, sampling up to three candidates. Each transfer reads at most 64 KiB, following at most two HLS playlists before the media sample. Sampling has a four-second budget; source details and script resolution have their existing separate timeouts. The first check can therefore add startup time. Successful samples are reused for ten minutes.

Ranking combines sampled transfer time, first-frame time, buffering and recent failures. Observations stay in private local preferences, keyed by configuration revision, source/line and Android network identity. A network change starts separate observations. This ranks recent candidates; it cannot guarantee the fastest provider or equal picture quality.

A playback error or fifteen seconds of uninterrupted active buffering pauses playback and opens **更换来源**. Choosing a candidate explicitly confirms the switch; **暂不更换** and Back do not change the source. **重试** retries the current source. The panel shows the saved time, and a confirmed switch keeps that time and matches the episode by its label. The player shows the active provider beside the title. Manual line changes also retain progress when the same episode is identifiable.

Candidates are restricted to known, enabled sources in the film's aggregate group. Title, year and content type must still match when details are fetched. Numbered episode labels are normalized; ambiguous or mismatched episodes are omitted, and missing metadata is not guessed. Direct duplicate URLs within one JSON/XML source are omitted. Alternative discovery is bounded to the first three source references, and individual failed candidates are excluded for the current player session. History entries without grouped alternatives can still offer matching lines from their current source. Different edits or introductions can shift the real picture at the same timestamp.

No providers are selected in a public build. TVBox, FongMi and Banana TV are clients of overlapping configuration formats, not three independent video networks. A qist configuration can contain JSON/XML APIs and executable script sources. Using the same configuration does not guarantee identical results: runtime compatibility, enabled sources, category mapping and failed requests still affect what each client displays.

## Unified library and aggregation

Home, category and search queries share one loader, with at most three concurrent JSON provider requests. Results appear as each source returns, without rebuilding existing poster cards or moving their focus. Failed sources are named and can be retried; successful results remain usable. **加载更多** requests the next page from sources that have more content, retaining and deduplicating earlier results. Config entries with `searchable: 0` are excluded from search; they can still contribute to browsing. Search covers the active configuration, not saved inactive configurations.

Matching normalizes HTML, Unicode spacing and letter case. It requires the same title, four-digit year and compatible content type; trailers, commentary, seasons with different titles and different years remain separate. Missing year/type metadata stays source-specific. This conservative matching can leave duplicates when providers disagree. Shared categories map Chinese aliases to each provider's own IDs, including genre children as well as direct entries in parent categories. Optional per-provider `categories` mappings override that selection. A provider without a matching category contributes no entries to it. Loading more rotates through matching native categories and their pages, with genres first; an empty parent does not hide its children. See [maintaining a CDN source catalog](source-catalog.md).

Switching categories or starting a new query cancels queued work and active JSON HTTP calls. Returning from details retains the loaded results and the selected card. Script searches share the browser's serial source worker to avoid concurrent script-runtime calls. User selections run ahead of queued catalog work; a third-party call already running can still delay later operations.

Home, category and search first pages have a private persistent display cache. Reopening shows cached results while sources update in the background; a failed update retains those results and exposes retry. Successful updates replace that provider's cached entries instead of accumulating old posters. The cache is separated by configuration revision, provider and query, limited to 16 MiB and seven days. Resized posters have a separate 16 MiB disk cache. Browsing loads only visible posters, releases offscreen bitmaps and cancels obsolete image requests when the visible page changes. Details and episode addresses are requested again for playback; video files are not downloaded by this cache.

## Runtime components

XML (type 0) and JSON HTTP sites (types 1/4) need no runtime APK. Java spiders (type 3, `csp_`) use the declared JAR. JavaScript and Python spiders require compatible official [FongMi runtime components](https://github.com/FongMi/Release/releases/tag/module):

- QuickJS: `fongmi-quickjs-ng-0.17-v11`.
- Python: `catvod-python-plugin-v2`.

Choose the device's actual ABI. The host validates package identity, upstream signing certificate, compatibility ID, ABI and declared payload hashes. Missing components produce an actionable error. Banana TV does not silently install or update them. A source JAR's declared MD5 is checked before loading; it is an integrity check, not proof that third-party code is trustworthy.

## Scope

Implemented: local configuration cache, unified home and categories, persistent category navigation, source enable/disable settings, manual and daily remote-configuration refresh, QR phone source setup, incremental cross-site search and grouped results, nested provider folders, native details/lines/episodes, HTTP(S) MP4/HLS/DASH playback, ordinary web-player resolution, configured JSON/web parsers, next episode, line switching, real progress and resume. Normal builds run both media activities in a dedicated `:media` process, so a source terminating its own process does not terminate the Home process. These processes share the app UID and granted permissions. Network work runs off the UI thread; the source proxy binds only to loopback. No public HTTP remote endpoint is added.

Not implemented: a server-side film index, Node.js sites, encrypted configuration/depot formats, JAR-specific mixed parsers, live TV/EPG, subtitle-selection UI, DRM services or cloud history sync. Source-specific account/configuration screens and native helper programs are not integrated. Arbitrary third-party adapters require separate source/device tests. This is not complete parity with FongMi.

## Code

- `media-core/`: pinned upstream CatVod source and documented adaptations.
- `MediaEngine`: private config, source host, JAR integrity, site operations and loopback proxy.
- `MediaXml`, `MediaProxy`, `MediaWebResolver`: XML conversion, legacy proxy responses and bounded web-player resolution.
- `MediaModels`: CatVod JSON records and line/episode grouping.
- `MediaCatalog` / `MediaCategories`: shared home/category/search loading, provider category mapping, cancellation, paging and conservative cross-source grouping.
- `MediaCache` / `MediaImages`: bounded private display caches and poster downloading; `MediaActivity` owns visible-poster selection.
- `MediaActivity`: remote-friendly browser; `MediaPlayerActivity`: Media3 playback and real resume data.
- `MediaIntegrationProbe` / `tools/check_media.py`: isolated HTTP fixture and Android playback checks.

Configuration and history live in the app's private `files/media/` and `media` preferences. The public source excludes them. The original MIT notices remain in `LICENSE-MIT`; the integrated source is distributed under GPL-3.0 with FongMi attribution.

## Focused emulator check

Build an isolated package so the owner's installed Banana TV and preferences remain untouched:

```sh
./gradlew --no-daemon -PmediaFixture :app:assembleDebug :app:assembleDebugAndroidTest
adb -P ADB_SERVER_PORT -s EMULATOR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -P ADB_SERVER_PORT -s EMULATOR_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/check_media.py --adb /path/to/adb --server ADB_SERVER_PORT --serial EMULATOR_SERIAL
```

The flag is for Debug only: application ID `com.sogud.bananatv.mediatest`. The probe refuses the production package and the script refuses physical devices. It serves the bundled CC BY Spring excerpt over loopback, creates a temporary ADB reverse rule and removes that rule when finished. Add `--navigation-only` to run just the category, grouped-detail and import-error navigation checks. Test output requires `result=PASS`; a successful build or instrumentation exit code alone does not establish playback.

## Source compatibility

The original qist integration failed because its global JAR initializer combined source setup with application-name checks, UI hooks and process termination. Banana TV now provides a separate, source-built lifecycle bridge for the reviewed snapshots. The JAR remains unchanged and its declared MD5 is still checked, including cached files. The app retains its own package and signing identity. JavaScript can use JAR helper classes without executing the JAR's application initializer.

A maintained configuration can explicitly select `"jarHost": "catvod-v1"` at the root or on an individual site. This supplies the context, foreground Activity and scheduling methods used by these adapters without starting their application hooks. Omit the field for automatic handling of the reviewed snapshots, or use `"native"` to retain a JAR's own initializer. Unknown profile names reject the import and preserve the previous configuration. A profile describes a host ABI; it does not guarantee compatibility with future adapter changes or provide their account-management UI. No third-party JAR is bundled in the APK.

Legacy proxies support the `/proxy?do=ck` discovery convention on loopback ports 9978–9999, static JAR proxy methods, request Range headers, binary bodies and Content-Range. Content-Length and Transfer-Encoding from an adapter are removed before the HTTP server frames its response. XML feeds map categories, pagination, CDATA, lines and episodes into the same internal records as JSON; DTDs are rejected.

Web resolution runs only when a source requests parsing. It recognizes `parse`, `jx`, configured VIP flags and `playUrl`, captures an ordinary web player's media request, then hands its URL and relevant request headers to Media3. File/content access and JavaScript bridges are disabled; normal TLS validation remains enabled. Configured type 1 JSON parsers are attempted before type 0 web parsers, with bounded attempts and timeouts. Source credentials are not forwarded as parser request headers. Interactive challenges, logins, DRM and arbitrary JAR mixed-parser code remain outside this path. A webpage that loads successfully is not a playback pass.

Python's optional `homeVideoContent` may return `None`; that no longer discards valid categories. Nested `vod_tag: "folder"` entries open their provider's native category IDs and retain parent navigation. Multi-quality URL arrays/objects and extensionless HLS/DASH manifests are normalized before playback.

`tools/check_source_compatibility.py` records home, category, search, detail, resolution and actual playback separately for a private, pinned configuration. Each provider runs in a fresh disposable emulator process. Playback success requires advancing progress after seeking and decoded audio/video buffers; audio-only items are labeled separately. Timeouts and adapter crashes remain explicit failures. Logs, source addresses and captures belong in private output directories, not this repository. An unavailable site or an account that was not configured cannot be counted as working merely because its runtime loaded.

Fixture builds intentionally run media code in the instrumentation process for internal assertions. Fixture playback does not establish physical-TV compatibility.

## Opt-in physical-TV playback check

`-PliveTvProbe :app:assembleReleaseAndroidTest` builds a separate, matching-signed test APK for the normal app's `:media` process. Install it only on the explicitly selected TV, then run:

```sh
adb -P ADB_SERVER_PORT -s TV_SERIAL shell am instrument -w -e live_tv true com.sogud.bananatv.test/com.sogud.bananatv.media.LiveTvPlaybackProbe
```

The probe uses existing enabled providers; it never imports fixture sources. Optional `first`, `second`, `title` and `year` arguments select two configured providers and an exact shared title/year. It measures launch-to-decoded playback and samples one minute of progress/buffering, then injects a refused loopback connection into that player to check the failure prompt, cancellation and confirmed same-episode resume through the other real provider. This interrupts playback and updates ordinary history/quality observations, but leaves source and system settings unchanged. The injected failure's ranking penalty is removed afterward. Keep raw output private. Require `result=PASS`, remove `com.sogud.bananatv.test` afterward, and separately confirm the actual picture/sound. This controlled failure test does not establish long-term provider availability.
