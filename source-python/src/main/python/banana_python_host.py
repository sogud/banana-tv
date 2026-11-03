"""Source loading and callback adaptation; Python execution is provided by Chaquopy."""
import base64
import importlib.abc
import importlib.util
import inspect
import json
import sys
import uuid


class _Quiet:
    def write(self, text):
        return len(text)

    def flush(self):
        pass


# This interpreter lives only in a private source worker, never the Home UI process.
sys.stdout = _Quiet()
sys.stderr = _Quiet()

def _binary(value):
    if isinstance(value, (bytes, bytearray, memoryview)):
        return {"__banana_bytes": base64.b64encode(value).decode("ascii")}
    raise TypeError("Source returned an unsupported value")


class Session(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    def __init__(self, entry, wire, storage):
        self.sources = json.loads(wire)
        self.loaded = set()
        self.source = None
        self.name = "banana_source_" + uuid.uuid4().hex
        self.module_sources = {}
        for filename, code in self.sources.items():
            if not filename.endswith(".py") or ".." in filename.split("/"):
                raise ValueError("Invalid Python module name")
            name = filename[:-3].replace("/", ".")
            if name == "base" or name.startswith("base."):
                raise ValueError("Reserved Python module name")
            self.module_sources[self.name if filename == entry else name] = code
        sys.meta_path.insert(0, self)
        try:
            module = __import__(self.name)
            self.source = module.Spider()
            self.source._banana_storage = storage
            self.source._banana_proxy = ""
        except BaseException:
            self._unload()
            raise

    def find_spec(self, fullname, path=None, target=None):
        if fullname in self.module_sources:
            return importlib.util.spec_from_loader(fullname, self)
        return None

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        self.loaded.add(module.__name__)
        module.__file__ = "<banana>/" + module.__name__.replace(".", "/") + ".py"
        exec(compile(self.module_sources[module.__name__], module.__file__, "exec"), module.__dict__)

    def call(self, operation, wire):
        args = json.loads(wire)
        names = {"INIT": "init", "HOME": "homeContent", "HOME_VIDEO": "homeVideoContent",
                 "CATEGORY": "categoryContent", "SEARCH": "searchContent", "DETAIL": "detailContent",
                 "PLAY": "playerContent", "PROXY": "localProxy"}
        if operation == "INIT":
            if len(args) > 1:
                self.source._banana_proxy = args[1]
            args = args[:1]
        name = names[operation]
        if operation == "SEARCH" and hasattr(self.source, "searchContentPage"):
            name = "searchContentPage"
        function = getattr(self.source, name, None)
        if function is None:
            return None
        if operation == "SEARCH":
            try:
                inspect.signature(function).bind(*args)
            except TypeError:
                args = args[:2]
        value = function(*args)
        if value is None:
            return None
        if isinstance(value, str):
            return value
        return json.dumps(value, ensure_ascii=False, default=_binary)

    def _unload(self):
        if self in sys.meta_path:
            sys.meta_path.remove(self)
        for name in self.loaded:
            sys.modules.pop(name, None)
        self.loaded.clear()

    def close(self):
        try:
            if self.source is not None:
                self.source.destroy()
        finally:
            self.source = None
            self._unload()
