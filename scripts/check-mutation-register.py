#!/usr/bin/env python3
"""Fails if the encapsulated local mutation in the code and the register in docs/design.md disagree.

    python3 scripts/check-mutation-register.py

The one allowed exception to referential transparency in this library is mutation that is created
inside a pure function and never escapes it. Every such site carries
`// scalafix:ok DisableSyntax.var` (or `.while`) with a sentence of justification, **and** a row in
the register of `docs/design.md`. Neither half is worth much alone: the annotation silences the
lint, and only the register makes someone argue the case in prose. This script makes the two halves
agree, so that a new site cannot be waved through by an annotation nobody reads.

It compares the number of annotated lines per file and rule. Adding, removing or moving a site
changes a count, and the message says which row of the register to bring up to date.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

ANNOTATION = re.compile(r"//\s*scalafix:ok\s+DisableSyntax\.(\w+)")
ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*(\d+)\s*\|")
BEGIN = "<!-- mutation-register:begin -->"
END = "<!-- mutation-register:end -->"


def from_code(root: Path) -> Counter[tuple[str, str]]:
    found: Counter[tuple[str, str]] = Counter()
    for path in sorted(root.glob("modules/*/src/main/scala/**/*.scala")):
        for line in path.read_text(encoding="utf-8").splitlines():
            match = ANNOTATION.search(line)
            if match:
                found[(str(path.relative_to(root)), match.group(1))] += 1
    return found


def from_register(design: Path) -> Counter[tuple[str, str]] | None:
    text = design.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        return None
    body = text.split(BEGIN, 1)[1].split(END, 1)[0]
    listed: Counter[tuple[str, str]] = Counter()
    for line in body.splitlines():
        match = ROW.match(line.strip())
        if match:
            listed[(match.group(1), match.group(2))] += int(match.group(3))
    return listed


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    design = root / "docs" / "design.md"
    code = from_code(root)
    listed = from_register(design)

    if listed is None:
        print(f"{design} has no register between {BEGIN} and {END}", file=sys.stderr)
        return 2

    if code == listed:
        total = sum(code.values())
        print(f"the register matches the code: {total} annotated site(s) in {len(code)} group(s)")
        return 0

    print("the register in docs/design.md and the code disagree:", file=sys.stderr)
    for key in sorted(set(code) | set(listed)):
        in_code = code.get(key, 0)
        in_register = listed.get(key, 0)
        if in_code != in_register:
            where, rule = key
            print(
                f"  {where} [{rule}]: {in_code} annotated in the code, "
                f"{in_register} listed in the register",
                file=sys.stderr,
            )
    print(
        "\nEvery site of encapsulated local mutation must be annotated in the code and argued for\n"
        "in the register. Add the row, or remove the annotation.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
