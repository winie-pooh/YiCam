#!/usr/bin/env python3
"""
Responsible for: checking that docs/FILES.md lists every .kt/.xml source file and that file headers match the index.
Not responsible for: building the project or checking code quality.

Run from the project root (the folder that contains app/):  python tools/check_files_index.py
Exit code 0 = index is correct, 1 = problems were printed.
"""
import fnmatch
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "docs" / "FILES.md"
SOURCE_DIRS = ["app/src/main", "app/src/test", "app/src/androidTest"]
EXTENSIONS = {".kt", ".xml"}


def read_index():
    """Returns (rows, ignore_patterns). rows: {path: (responsible_text, header_column)}."""
    rows, ignore, in_ignore = {}, [], False
    for line in INDEX.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("```generated-ignore"):
            in_ignore = True
            continue
        if in_ignore:
            if stripped.startswith("```"):
                in_ignore = False
            elif stripped:
                ignore.append(stripped)
            continue
        if stripped.startswith("| `"):
            cells = [c.strip() for c in stripped.strip("|").split("|")]
            if len(cells) >= 4:
                rows[cells[0].strip("`")] = (cells[1], cells[3])
    return rows, ignore


def kotlin_header(path):
    """The text of the leading /** ... */ comment as one line, or None."""
    text = path.read_text(encoding="utf-8")
    match = re.match(r"\s*/\*\*(.*?)\*/", text, re.S)
    if not match:
        return None
    lines = [re.sub(r"^\s*\*\s?", "", ln).strip() for ln in match.group(1).strip().splitlines()]
    return " ".join(ln for ln in lines if ln)


def xml_header(path):
    head = "\n".join(path.read_text(encoding="utf-8").splitlines()[:6])
    match = re.search(r"<!--(.*?)-->", head, re.S)
    return " ".join(match.group(1).split()) if match else None


def main():
    problems = []
    rows, ignore = read_index()

    on_disk = set()
    for d in SOURCE_DIRS:
        base = ROOT / d
        if base.exists():
            for p in base.rglob("*"):
                if p.is_file() and p.suffix in EXTENSIONS:
                    on_disk.add(p.relative_to(ROOT).as_posix())

    for rel in sorted(on_disk):
        if rel not in rows and not any(fnmatch.fnmatch(rel, pat) for pat in ignore):
            problems.append(f"NOT IN INDEX: {rel}")

    for rel, (text, header_col) in sorted(rows.items()):
        path = ROOT / rel
        if not path.exists():
            problems.append(f"IN INDEX BUT MISSING ON DISK: {rel}")
            continue
        if header_col.strip().lower() != "yes":
            continue
        header = kotlin_header(path) if path.suffix == ".kt" else xml_header(path)
        if header is None:
            problems.append(f"NO HEADER COMMENT: {rel}")
        elif " ".join(header.split()) != " ".join(text.split()):
            problems.append(f"HEADER DIFFERS FROM INDEX: {rel}\n    file : {header}\n    index: {text}")

    if problems:
        print("\n".join(problems))
        print(f"\n{len(problems)} problem(s). Fix the file header, docs/FILES.md and CHANGELOG.md together (rule 2).")
        return 1
    print(f"OK: {len(rows)} indexed files, {len(on_disk)} source files checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
