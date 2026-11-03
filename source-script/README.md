# JavaScript source runtime

An Android QuickJS host with explicit source sessions, bounded HTTP, source-scoped storage and persistent worker processes. It depends on source-runtime and `wang.harlon.quickjs:wrapper-android:3.2.3`.

## Build

Install the locked build dependencies before the first build:

```sh
npm --prefix source-script/javascript ci
./gradlew --no-daemon :source-script:compileDebugJavaWithJavac
```

The Gradle pre-build task bundles the JavaScript helpers. It emits `banana-libraries.js`, `banana-library-notices.txt` and `banana-library-manifest.json` into the generated assets directory. Only packages actually included in the bundle enter its license manifest. Missing or unreviewed license text fails the build. Keep all three generated assets together when distributing the library.

## Sessions and workers

`ScriptSession` loads the caller's module map and entry module. It supports the operations declared in `ScriptSession.Operation`, preserves session state, and returns bounded JSON/string results. Imports must resolve within the supplied module map. Direct sessions belong to their creating thread.

For application integration, use `ScriptWorkerClient.Source(entry, modules, sourceId, profile, initialization)` and `ScriptWorkerClient.open(context, source, timeoutMillis)`. Call `open`, `call` and `close` off the Android main thread. `cancel` is nonblocking. Timeouts must be 1–120000 milliseconds.

Profiles are `NONE`, `LIBRARIES`, `DRPY` and `PYTHON`. The Python profile additionally requires source-python. The DRPY profile provides host helpers; it does not bundle a third-party rule adapter.

The client has four JavaScript worker slots and a separate four-slot Python pool. Calls are serialized per session, use generation identifiers, and retain source-specific storage. A failed worker can be replaced for a later call; a worker whose exit cannot be confirmed is quarantined rather than reused.

Worker services are non-exported but share the application's UID and permissions. They are a process boundary, not a security sandbox. Use trusted source code and avoid storing credentials in public fixtures.

## Checks and limitations

Existing instrumentation includes synthetic session, HTTP, library, cancellation and persistent-worker probes. Running them needs an explicitly selected Android emulator or device; a library compilation does not prove runtime behavior.

`-PscriptReferenceFixture` optionally exposes separately supplied assets from the ignored `data/runtime-rewrite/script-reference-assets` directory to instrumentation. Reference and live-rule probes are opt-in and their external inputs are not distributed. They are not prerequisites for normal compilation or synthetic tests.

These libraries are not connected to the default Banana TV app. See the [module and integration guide](../docs/source-runtime-rewrite.md).
