#!/usr/bin/env python3
"""Fails if the manual's prose uses a term the glossary asks us to avoid.

    python3 scripts/check-terminology.py

The manual speaks the vocabulary of *Statistical Rethinking* while the API keeps the names of
Stan, and `docs/manual/glossary.md` is the bridge. This script is what keeps the prose on the
first side of that bridge: it reads `docs/manual/terminology.csv` and reports every avoided term
with what to write instead.

What it deliberately does **not** look at:

- fenced code blocks, and inline `code` spans — without that exclusion it would flag
  `LogDensity.normal`, which is the very name the glossary tells us to keep;
- comments and link targets, for the same reason;
- `docs/manual/appendix-a-*.md`, whose job is to speak Stan;
- the **table rows** of `docs/manual/glossary.md`, which is the list of avoided terms itself and
  would otherwise flag every one of them. Its prose stays under the rule: the page that states the
  convention is not exempt from following it, only from quoting it.

A line ending in `<!-- term-ok -->` is allowed its flagged term: the escape exists for the places
where the word means something else — a draw from a random number generator is not a sample from
a posterior — and it is meant to be rare enough to read as a list.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ESCAPE = "<!-- term-ok -->"
FENCE = re.compile(r"^\s*```")
INLINE_CODE = re.compile(r"`[^`]*`")
LINK_TARGET = re.compile(r"\]\([^)]*\)")
HTML_COMMENT = re.compile(r"<!--.*?-->")
EXEMPT = ("appendix-a-",)
GLOSSARY = "glossary.md"


def terms(path: Path) -> list[tuple[str, str, str]]:
    rows = []
    with path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(line for line in handle if not line.startswith("#")):
            avoid = (row.get("avoid") or "").strip()
            prefer = (row.get("prefer") or "").strip()
            if avoid:
                rows.append((avoid, prefer, (row.get("note") or "").strip()))
    return rows


def prose(line: str) -> str:
    """The line with everything that is not prose removed."""
    without = HTML_COMMENT.sub(" ", line)
    without = INLINE_CODE.sub(" ", without)
    return LINK_TARGET.sub(" ", without)


def findings(path: Path, rows: list[tuple[str, str, str]]) -> list[str]:
    found = []
    inside_code = False
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if FENCE.match(raw):
            inside_code = not inside_code
            continue
        if inside_code or ESCAPE in raw:
            continue
        if path.name == GLOSSARY and raw.lstrip().startswith("|"):
            continue
        text = prose(raw)
        lowered = text.lower()
        for avoid, prefer, note in rows:
            # Whole words only: "normal" must not fire on "abnormally".
            if re.search(rf"(?<![\w-]){re.escape(avoid.lower())}(?![\w-])", lowered):
                hint = f" ({note})" if note else ""
                found.append(f"{path}:{number}: '{avoid}' -> write '{prefer}' instead{hint}")
    return found


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    table = root / "docs" / "manual" / "terminology.csv"
    if not table.is_file():
        print(f"{table} is missing", file=sys.stderr)
        return 2
    rows = terms(table)
    if not rows:
        print(f"{table} lists no term to avoid", file=sys.stderr)
        return 2

    pages = sorted(
        p
        for p in (root / "docs" / "manual").rglob("*.md")
        if not p.name.startswith(EXEMPT)
    )
    found = [f for page in pages for f in findings(page, rows)]

    if found:
        print(f"{len(found)} term(s) to replace in the manual:", file=sys.stderr)
        for f in found:
            print(f"  {f}", file=sys.stderr)
        print(
            f"\nThe glossary is docs/manual/glossary.md. Where the flagged word means something\n"
            f"else, end the line with {ESCAPE} and say why.",
            file=sys.stderr,
        )
        return 1

    print(f"{len(pages)} manual page(s) checked against {len(rows)} terms: none to replace")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
