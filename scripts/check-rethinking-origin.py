#!/usr/bin/env python3
"""Fails if the `rethinking` module looks like it came from the R package rather than its docs.

    python3 scripts/check-rethinking-origin.py

The R package is GPL. Reproducing **names** and **documented parameterisations** does not copy
code; translating its source would make this module a derived work under a licence incompatible
with this library's. The rule is therefore absolute: implement from the documentation and the
papers, never from the source — and do not reproduce its text, its examples or its datasets
(docs/design.md).

That rule cannot be checked by a script. What a script *can* do is refuse the traces a translation
would leave, so that a slip is loud rather than silent:

1. **No R.** No `<-`, no `function(`, no `c(` call, no `NULL`, no R file in the module.
2. **No datasets.** The package ships named data frames — `WaffleDivorce`, `chimpanzees`, and the
   rest. None of their names may appear anywhere in the repository, in any module.
3. **Every function says where it came from.** The module's own documentation has to name the
   documentation as its source, so that a reader knows what was and was not reproduced.

What this is not: a legal opinion, or a substitute for the human review the phase's definition of
done asks for. It is the mechanical floor under it.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "modules" / "rethinking"

# Syntax that is R and is not Scala. `<-` is Scala's for-comprehension arrow, so it is only
# evidence inside a string or a comment — which is where translated code would land.
R_SYNTAX = [
    (re.compile(r"\bfunction\s*\("), "an R function definition"),
    (re.compile(r"(?<![A-Za-z0-9_.])c\((?=\s*[-0-9\"'])"), "an R `c(...)` vector"),
    (re.compile(r"\bNULL\b"), "R's NULL"),
    (re.compile(r"\bTRUE\b|\bFALSE\b"), "R's logical literals"),
]

# Deliberately not a rule: R's `df$column`. In Scala `$` is string interpolation, so the pattern
# fires on every interpolated name and says nothing — a check that cries wolf is worse than no
# check, because it gets switched off.

# The datasets the package ships. None of them belongs in this repository: §2.8 allows only the
# invented world and public data this library documents itself.
DATASETS = [
    "WaffleDivorce",
    "chimpanzees",
    "reedfrogs",
    "Howell1",
    "milk",
    "rugged",
    "tulips",
    "UCBadmit",
    "Kline",
    "Trolley",
    "foxes",
    "cherry_blossoms",
    "Oceanic",
    "bangladesh",
    "Wines2012",
]


def check_no_r() -> list[str]:
    problems = []
    for source in sorted(MODULE.rglob("*")):
        if not source.is_file():
            continue
        if source.suffix.lower() in {".r", ".rd", ".rdata", ".rds"}:
            problems.append(f"{source.relative_to(ROOT)}: an R file in the module")
            continue
        if source.suffix != ".scala":
            continue
        text = source.read_text()
        for pattern, what in R_SYNTAX:
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{source.relative_to(ROOT)}:{line}: {what}")
    return problems


def check_no_datasets() -> list[str]:
    problems = []
    for source in sorted(ROOT.glob("modules/*/src/**/*.scala")):
        text = source.read_text()
        for dataset in DATASETS:
            if re.search(rf"\b{re.escape(dataset)}\b", text):
                problems.append(f"{source.relative_to(ROOT)}: the package's `{dataset}` dataset")
    for document in sorted(ROOT.glob("docs/**/*.md")):
        text = document.read_text()
        for dataset in DATASETS:
            if re.search(rf"\b{re.escape(dataset)}\b", text):
                problems.append(f"{document.relative_to(ROOT)}: the package's `{dataset}` dataset")
    return problems


def check_provenance() -> list[str]:
    problems = []
    facade = MODULE / "src" / "main" / "scala" / "bayes4s" / "rethinking" / "Rethinking.scala"
    if not facade.exists():
        return [f"{facade.relative_to(ROOT)} does not exist"]
    text = facade.read_text()
    for wanted, why in [
        ("documentation", "it must say it was written from the documentation"),
        ("GPL", "it must say why the source was not read"),
        ("not connected", "it must say it is unaffiliated with the package"),
    ]:
        if wanted not in text:
            problems.append(f"Rethinking.scala: {why}")
    return problems


def main() -> int:
    sections = [
        ("R syntax in a Scala module", check_no_r()),
        ("the package's datasets", check_no_datasets()),
        ("the provenance note", check_provenance()),
    ]
    failed = False
    for title, problems in sections:
        if problems:
            failed = True
            print(f"{title}:")
            for problem in problems:
                print(f"  {problem}")
    if failed:
        print("\nThe module is written from the package's documentation, never from its source.")
        return 1
    scala = len(list(MODULE.rglob("*.scala")))
    print(f"{scala} Scala files, no R, none of the package's {len(DATASETS)} datasets, provenance stated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
