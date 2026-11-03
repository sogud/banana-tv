# JAR source host

An Android library for loading a verified DEX-containing JAR with a bounded lifetime. It depends on source-runtime and Gson and exposes a partial `com.github.catvod` compatibility API.

`JarSession.open` takes ownership of the artifact input stream, requires its SHA-256 digest and source class name, and copies at most 32 MiB into the app's private code cache. The artifact is marked read-only before loading. The source receives explicit activity and host callbacks, network access and source-scoped local storage.

Use `call` and `close` on the thread that opened the session. `cancel` cancels its network operations; it cannot safely interrupt arbitrary Java code. Closing detaches the host, closes network resources and removes the private artifact.

## Build

```sh
./gradlew --no-daemon :source-jar:compileDebugJavaWithJavac
```

The default instrumentation runner is JarProbe. Its original DEX fixture is built from `src/fixture/java`; shared host-contract and playback fixtures are in `tools/fixtures`. `-PsourceHostContract` selects the standalone HostContractProbe runner.

`-PjarReferenceFixture` is an optional local-only test mode. It requires separately supplied assets under the ignored `data/runtime-rewrite/jar-reference-assets` directory. Those adapters are not distributed or required for the default synthetic tests.

## Limits

This is not a complete drop-in implementation of every CatVod extension. JAR code runs with the application's permissions in the host process. A verified hash is not a sandbox or a license grant. Only load trusted, authorized artifacts.

The default Banana TV app still uses media-core. Do not link both hosts into one app because their `com.github.catvod` classes overlap. See the [module and integration guide](../docs/source-runtime-rewrite.md).
