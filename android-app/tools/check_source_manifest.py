#!/usr/bin/env python3
"""Check or regenerate the tracked-source SHA256 manifest before sealing a release tag.

Development commits do not have to keep SHA256.json current. Publish requires
an exact match; a tagged source tree is immutable, so updates must happen
in a reviewed commit before creating the tag.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
mode = p.add_mutually_exclusive_group(required=True)
mode.add_argument("--check", action="store_true")
mode.add_argument("--update", action="store_true")
args = p.parse_args()

paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).split(b"\0")
files = {}
for raw in paths:
    if not raw:
        continue
    name = raw.decode("utf-8")
    if name == "SHA256.json":
        continue
    target = ROOT / name
    if not target.is_file() or target.is_symlink():
        raise SystemExit(f"Unsupported tracked source entry: {name}")
    files[name] = hashlib.sha256(target.read_bytes()).hexdigest()

manifest = ROOT / "SHA256.json"
if args.update:
    manifest.write_text(json.dumps(files, sort_keys=True, indent=2) + "\n")
    print(f"Updated SHA256.json for {len(files)} tracked paths (excluding itself)")
else:
    previous = json.loads(manifest.read_text())
    if files != previous:
        missing = sorted(set(files) - set(previous))
        stale = sorted(x for x in files.keys() & previous.keys() if files[x] != previous[x])
        obsolete = sorted(set(previous) - set(files))
        print("Manifest does not describe this Git tree:")
        print(f" missing={missing[:20]} stale={stale[:20]} obsolete={obsolete[:20]}")
        raise SystemExit(1)
    print(f"PASS: SHA256.json matches all {len(files)} tracked source files")
