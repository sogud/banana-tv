# Python source runtime

An Android Chaquopy/CPython 3.11 library using source-script's persistent worker transport. It packages the local `banana_python_host.py` host and a small `base.spider` compatibility class, not third-party source adapters.

## Build

Use the JDK/Android setup and Node dependency installation in the [module guide](../docs/source-runtime-rewrite.md). Chaquopy needs a compatible host Python 3.11 installation.

```sh
./gradlew --no-daemon :source-python:compileDebugJavaWithJavac
```

The module declares ARM64 and ARMv7 Android ABIs. Chaquopy installs the pinned dependencies from `requirements.txt`; this may access package indexes even when Gradle itself uses `--offline`. Preserve the included [Chaquopy license notice](src/main/assets/python/CHAQUOPY-LICENSE.txt) and other dependency terms in distributions.

## Integration

Depend on `:source-python`, which exposes `:source-script` transitively. Construct a `ScriptWorkerClient.Source` using `Profile.PYTHON`, a Python entry/module map, a stable source ID and initialization arguments. Use the same background-thread open/call/close lifecycle as the [JavaScript worker client](../source-script/README.md).

The manifest declares four non-exported Python worker services in separate processes. Python sessions are terminated on close so imported module globals are not reused for another source. The services share the app's UID: this is not a sandbox for hostile Python code.

The default Banana TV app does not link this library and has no `independentSources` build variant in this publication. There is no standalone Python instrumentation runner here. Compilation alone does not establish source compatibility, playback or physical-device acceptance.
