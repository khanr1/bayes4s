#!/usr/bin/env python3
"""Fails if an entry point of the API has no worked example in its Scaladoc.

    python3 scripts/check-scaladoc.py

The Scaladoc is the API reference — the manual explains ideas, this says how a method is called —
and a reference without examples sends the reader back to the manual for every signature. So every
entry point a model-writer touches carries a compiled example showing the methods a caller
actually uses, and `docs/scaladoc-examples.csv` is the map of which.

This script checks that the example is **there** and names the methods it is supposed to show.
That it **compiles** is `sbt doc`'s job: `-snippet-compiler:compile` type-checks every fenced
`scala` block in a doc comment, so an example that stops compiling fails the build exactly as a
manual example that stops compiling fails `docs/mdoc`. The two halves are deliberately separate —
one of them cannot be satisfied by writing plausible-looking code, and the other cannot be
satisfied by writing code about something else.

It also refuses a snippet written under an `@example` tag. Scaladoc's snippet compiler does not
look inside those — a call to a method that does not exist passes `sbt doc` without a word — so a
fenced block there is an unchecked claim wearing the clothes of a checked one. Examples go in the
body of the comment, under a `**Example.**` heading.

What it does not check is that the example is a *good* one. Nothing can.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAP = ROOT / "docs" / "scaladoc-examples.csv"

# The doc comment immediately above a declaration of the named type, in any of the shapes this
# library declares one: an object, a trait, an enum, or a case class.
DECLARATION = (
    r"(?:^|\n)(?:final |sealed |abstract |opaque )*"
    r"(?:object|trait|enum|case class|class|type) {}\b"
)


def comment_spans(source: str) -> list[tuple[int, int]]:
    """Every `/** ... */` block, start and end.

    Scala's block comments **nest**, and an example that documents its own `def` has a comment
    inside a comment. Scanning backwards for the nearest `/**` finds that inner one and reads the
    rest of the snippet as if it were the doc comment — which is how this check first reported six
    examples it had just been given as missing.
    """
    spans = []
    at = 0
    while True:
        start = source.find("/**", at)
        if start == -1:
            return spans
        depth = 0
        cursor = start
        while cursor < len(source):
            if source.startswith("/*", cursor):
                depth += 1
                cursor += 2
            elif source.startswith("*/", cursor):
                depth -= 1
                cursor += 2
                if depth == 0:
                    break
            else:
                cursor += 1
        spans.append((start, cursor))
        at = cursor


def doc_comment_of(source: str, name: str) -> str | None:
    """The `/** ... */` block that precedes the declaration of `name`, if there is one."""
    found = re.search(DECLARATION.format(re.escape(name)), source)
    if found is None:
        return None
    for start, end in comment_spans(source):
        # The comment has to be the declaration's own: nothing but whitespace between them.
        if end <= found.start() and not source[end : found.start()].strip():
            return source[start:end]
    return None


def snippets(comment: str) -> list[str]:
    """Every fenced `scala` block in a doc comment, as the snippet compiler sees them."""
    return re.findall(r"```scala(.*?)```", comment, re.S)


def unchecked_examples() -> list[str]:
    """Snippets under `@example`, which Scaladoc renders and does not compile."""
    problems = []
    for source in sorted(ROOT.glob("modules/*/src/main/scala/**/*.scala")):
        for comment in re.findall(r"/\*\*.*?\*/", source.read_text(), re.S):
            if "@example" in comment and "```scala" in comment:
                name = source.relative_to(ROOT)
                problems.append(f"{name}: a snippet under `@example` is never compiled")
    return problems


def main() -> int:
    with MAP.open() as handle:
        rows = list(csv.DictReader(line for line in handle if not line.startswith("#")))
    problems = unchecked_examples()
    for row in rows:
        source = ROOT / row["file"]
        if not source.exists():
            problems.append(f"{row['type']}: {row['file']} does not exist")
            continue
        comment = doc_comment_of(source.read_text(), row["type"])
        if comment is None:
            problems.append(f"{row['type']}: no doc comment of its own in {source.name}")
            continue
        blocks = snippets(comment)
        if not blocks:
            problems.append(f"{row['type']}: its doc comment carries no example")
            continue
        shown = "\n".join(blocks)
        for method in row["methods"].split():
            if re.search(rf"\b{re.escape(method)}\b", shown) is None:
                problems.append(f"{row['type']}: the example never uses `{method}`")
    if problems:
        print("entry points whose example is missing or incomplete:")
        for problem in problems:
            print(f"  {problem}")
        print(f"\n{len(problems)} of {len(rows)} entry points need work.")
        print("The map is docs/scaladoc-examples.csv; `sbt doc` compiles what you write.")
        return 1
    methods = sum(len(row["methods"].split()) for row in rows)
    print(f"{len(rows)} entry points, {methods} methods, every one shown in a compiled example")
    return 0


if __name__ == "__main__":
    sys.exit(main())
