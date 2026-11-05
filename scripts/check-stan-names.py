#!/usr/bin/env python3
"""Fails if a Scala name in appendix A of the manual no longer exists in the sources.

    python3 scripts/check-stan-names.py

Appendix A is the table a reader coming from Stan navigates by, and a table that has drifted from
the code is worse than no table: it sends them to a name that is not there. Every row names a Stan
function and a Scala one; this looks the second up in `modules/*/src/main/scala`, as a definition,
an enum case or an object, and reports every row it cannot find.

It does not check the reverse direction. A function this library has and the table does not is not
an error — the table is for names Stan also has.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|")
BEGIN = "<!-- stan-names:begin -->"
END = "<!-- stan-names:end -->"


def rows(appendix: Path) -> list[tuple[str, str]]:
    text = appendix.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        sys.exit(f"{appendix} has no table between {BEGIN} and {END}")
    body = text.split(BEGIN, 1)[1].split(END, 1)[0]
    return [(m.group(1), m.group(2)) for m in (ROW.match(l.strip()) for l in body.splitlines()) if m]


def defined(sources: str, qualified: str) -> bool:
    """`Object.member` is defined when the member is, as a def, a val or an enum case."""
    member = qualified.split(".")[-1]
    patterns = [
        rf"\bdef {re.escape(member)}\b",
        rf"\bval {re.escape(member)}\b",
        rf"\bcase {re.escape(member)}\b",
        rf"\bobject {re.escape(member)}\b",
    ]
    return any(re.search(p, sources) for p in patterns)


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    appendix = root / "docs" / "manual" / "appendix-a-from-stan.md"
    sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.glob("modules/*/src/main/scala/**/*.scala"))
    )
    table = rows(appendix)
    missing = [(stan, ours) for stan, ours in table if not defined(sources, ours)]
    if missing:
        print("appendix A names things that are not in the sources:", file=sys.stderr)
        for stan, ours in missing:
            print(f"  {stan} -> {ours}", file=sys.stderr)
        print(
            "\nEither the name changed and the table should follow, or the row is wrong.",
            file=sys.stderr,
        )
        return 1
    print(f"appendix A: {len(table)} Stan names, every one mapped to something that exists")
    return 0


if __name__ == "__main__":
    sys.exit(main())
