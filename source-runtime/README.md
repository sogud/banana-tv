# Source HTTP transport

`SourceHttp` owns bounded download behavior; the caller supplies a reusable OkHttp client and a per-page `Scope`. Closing the scope cancels that page's in-flight calls without cancelling another page. Requests have call deadlines; oversized bodies fail without placing source URLs in exception messages. `download` rejects non-success status. `exchange` returns status, headers and a bounded body even for error pages so scripts can handle challenges themselves.

```sh
./gradlew :source-runtime:test
```

This module replaces native source downloads only. It does not replace the executable-source core. See [the runtime architecture and module builds](../docs/source-runtime-rewrite.md).
