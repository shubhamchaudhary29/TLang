#!/usr/bin/env python3
"""Validate portable local Markdown links and reject checkout-specific paths."""
import pathlib
import re
import sys

ROOT = pathlib.Path.cwd().resolve()
LINK = re.compile(r"\]\(([^)]+)\)")
FORBIDDEN = re.compile(r"file://|/(?:home|Users)/[^\s)`]+|\b[A-Za-z]:[\\/]")

def validate(path: pathlib.Path) -> list[str]:
    errors = []
    text = path.read_text(encoding="utf-8")
    if FORBIDDEN.search(text):
        errors.append(f"{path}: contains a machine-specific path or file URL")
    for target in LINK.findall(text):
        target = target.split("#", 1)[0]
        if not target or re.match(r"^(https?://|mailto:)", target):
            continue
        if target.startswith("/") or target.startswith("TLang/"):
            errors.append(f"{path}: non-portable link {target}")
            continue
        resolved = (path.parent / target).resolve()
        if ROOT not in resolved.parents and resolved != ROOT:
            errors.append(f"{path}: link escapes repository: {target}")
        elif not resolved.exists():
            errors.append(f"{path}: missing local link: {target}")
    return errors

files = [pathlib.Path(value) for value in sys.argv[1:]] if len(sys.argv) > 1 else ROOT.glob("**/*.md")
problems = [error for file in files for error in validate(file)]
if problems:
    print("Documentation validation failed:\n" + "\n".join(problems), file=sys.stderr)
    raise SystemExit(1)
print("Markdown links and portable paths valid")
