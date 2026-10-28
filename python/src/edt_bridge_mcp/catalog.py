"""Versioned Java tool descriptors shipped with the wrapper for offline discovery."""
from importlib.resources import files
import json

from . import __version__


def bridge_tools() -> list[dict]:
    catalog = json.loads(files(__package__).joinpath("tool_catalog.json").read_text(encoding="utf-8"))
    if catalog["version"] != __version__:
        raise RuntimeError("the packaged bridge tool catalog does not match the wrapper version; "
                           "reinstall edt-bridge-mcp")
    return catalog["tools"]
