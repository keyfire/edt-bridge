"""Export the packaged offline catalog from compiled Java descriptors, without starting EDT."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "python/src/edt_bridge_mcp"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk-home", type=Path)
    parser.add_argument("--pool", type=Path, default=Path.home() / ".p2/pool/plugins")
    parser.add_argument("--classes", type=Path, default=ROOT / "build/bin")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    jdk = args.jdk_home or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    suffix = ".exe" if os.name == "nt" else ""
    def executable(name):
        return str(jdk / "bin" / (name + suffix)) if jdk else shutil.which(name)
    if not executable("java") or not executable("javac"):
        parser.error("Java and javac are required; pass --jdk-home")
    with tempfile.TemporaryDirectory(prefix="edt-bridge-catalog-") as temporary:
        folder = Path(temporary)
        subprocess.run([executable("javac"), "--release", "17", "-encoding", "UTF-8", "-d", str(folder),
                        str(ROOT / "scripts/ExportToolCatalog.java")], check=True,
                       stdin=subprocess.DEVNULL)
        classpath = os.pathsep.join((str(folder), str(args.classes), str(args.pool / "*")))
        output = folder / "tools.json"
        subprocess.run([executable("java"), "-Dlog4j.defaultInitOverride=true", "-cp", classpath,
                        "ExportToolCatalog", str(output)], check=True, stdin=subprocess.DEVNULL)
        catalog = json.loads(output.read_text(encoding="utf-8"))
    version = re.search(r'__version__ = "([^"]+)"',
                        (PACKAGE / "__init__.py").read_text(encoding="utf-8")).group(1)
    manifest = (ROOT / "io.github.keyfire.edtbridge/META-INF/MANIFEST.MF").read_text(encoding="utf-8")
    if not re.search(r"Bundle-Version: " + re.escape(version) + r"\.qualifier", manifest):
        parser.error("the wrapper and plugin manifest versions differ")
    catalog["version"] = version
    text = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
    target = PACKAGE / "tool_catalog.json"
    if args.check:
        if not target.is_file() or target.read_text(encoding="utf-8") != text:
            print("The packaged tool catalog differs from the compiled descriptors; regenerate it.")
            return 1
    else:
        target.write_text(text, encoding="utf-8", newline="")
    print(f"{len(catalog['tools'])} bridge tools, version {version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
