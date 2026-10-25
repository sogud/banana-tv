# CatVod core

Source: [FongMi/TV](https://github.com/FongMi/TV/tree/7efe49031334048b043e698b4d9e195764fb1f20/catvod), commit `7efe49031334048b043e698b4d9e195764fb1f20`. GPL-3.0; see [LICENSE](LICENSE). `upstream.json` records original file hashes.

Banana TV adaptations:

- Android 9 minimum, SDK 35, Java 17 and OkHttp 4.12.0. Remove unused SMB/Sardine, Brotli and charset-detector dependencies.
- Rewrite the preferences type switch for Java 17.
- Keep platform TLS certificate and hostname validation. Remove the upstream trust-all implementation.
- Use OkHttp's standard redirect/proxy handling instead of the newer per-request redirect proxy API. The upstream `ProxyRedirectInterceptor` is excluded; remove the unavailable WebSocket close-timeout builder call.
- Verify runtime APKs against the upstream publisher's SHA-256 certificate, rather than Banana TV's unrelated signing certificate. Retain compatibility, ABI and payload validation.

`app/.../media/MediaEngine.java` implements the host and source lifecycle. No FongMi UI, updater, network remote, stock-app hooks, source list or account configuration is included. Optional QuickJS/Python components remain separate, publisher-verified runtime APKs. Their implementation and native libraries are not copied into this repository.
