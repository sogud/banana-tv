# Source runtime libraries

Banana TV contains standalone source libraries for Java/DEX, JavaScript and Python. They are developer-facing components, not a switch to a different backend in the launcher.

The default `:app` still depends on `:media-core` and `:source-runtime`. It does not depend on `:source-jar`, `:source-script` or `:source-python`. Passing `-PindependentSources` does not select these libraries in the published app. The GPL-3.0 media core and its notices remain required for that app.

## Modules and dependencies

- [source-runtime](../source-runtime/README.md): bounded OkHttp requests and cancellation scopes; a plain Java library.
- [source-jar](../source-jar/README.md): Android DEX loading, a partial CatVod-compatible host API and explicit source lifetime. Depends on source-runtime and Gson.
- [source-script](../source-script/README.md): QuickJS sessions, HTTP/storage bridges and persistent worker processes. Depends on source-runtime and the QuickJS Android wrapper.
- [source-python](../source-python/README.md): Chaquopy/CPython sessions using source-script's worker transport. Depends on source-script, including its generated JavaScript assets.

The root Gradle settings include all four modules. No media subscription, third-party source adapter or executable source artifact is bundled with these libraries.

## Build and focused checks

Use JDK 17, Android SDK Platform 35 and Build Tools 34.0.0 as described in the [root build guide](../README.md#build). Set `JAVA_HOME` and `ANDROID_HOME`. The JavaScript bundle also needs Node.js and npm. Python builds need a host Python 3.11 installation supported by Chaquopy.

Install the locked JavaScript build dependencies once:

```sh
npm --prefix source-script/javascript ci
```

Compile the libraries and run the HTTP unit tests without assembling or installing the launcher:

```sh
./gradlew --no-daemon \
  :source-runtime:test \
  :source-jar:compileDebugJavaWithJavac \
  :source-script:compileDebugJavaWithJavac \
  :source-python:compileDebugJavaWithJavac
```

Gradle resolves plugins and Maven artifacts. The Python module additionally downloads the versions in `source-python/requirements.txt` through Chaquopy's pip integration. Gradle's `--offline` option does not guarantee that pip will avoid the network.

Existing host-tool checks require only Python:

```sh
python3 -m unittest discover -s tools -p 'test_source_abi_inventory.py'
python3 -m unittest discover -s tools -p 'test_compare_source_host_contract.py'
```

JAR and JavaScript Android instrumentation sources and their shared fixtures are included. These require a separately authorized emulator or device to run. The commands above compile library code; they do not establish Android runtime behavior, third-party adapter compatibility, playback or physical-TV acceptance. The Python library has no standalone instrumentation runner in this publication.

## Integration and safety

Applications can depend on the selected modules directly. Do not add source-jar alongside media-core in one application: both define classes in `com.github.catvod`. Choose one JAR host implementation and adapt the caller to its API.

For JavaScript and Python, use `ScriptWorkerClient` from a background thread and close each source session. Python requires source-python in the application and the `PYTHON` profile. Merely including source-script is not enough.

Worker services are non-exported and run in separate processes under the application UID. This provides process lifetime control, not a security sandbox. JAR sources run in the host process. Run only sources you trust and have permission to use. A matching artifact hash proves identity, not safety or redistribution rights.

Optional reference-fixture build flags point to local inputs excluded from Git. They are not required for normal compilation or synthetic tests, and no reference-source test results are claimed here. Do not publish adapters, credentials, subscriptions or downloaded media merely because a local test used them.

## Licenses

The repository's [GPL-3.0 license](../LICENSE) and [upstream notices](../UPSTREAM.md) continue to apply. Dependencies retain their own licenses. The JavaScript asset builder generates package notices and a manifest from the exact packages included in its bundle, rejecting missing or unreviewed license text. The Python module retains the [Chaquopy notice](../source-python/src/main/assets/python/CHAQUOPY-LICENSE.txt). Preserve dependency and media notices when distributing a derived app or library.
