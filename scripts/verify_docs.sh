#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
python3 -c 'import glob, os, re; bad=[]; [bad.extend([f"{f}: {link}" for link in re.findall(r"\\]\\(([^)]+)\\)", open(f, encoding="utf-8").read()) if not re.match(r"^(https?://|mailto:|#|file:)", link) and (link.split("#", 1)[0] and not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(f), link.split("#", 1)[0]))))]) for f in glob.glob("**/*.md", recursive=True)]; print("Markdown links valid" if not bad else "Broken local Markdown links:\n" + "\n".join(bad)); raise SystemExit(bool(bad))'
