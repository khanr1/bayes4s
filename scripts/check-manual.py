#!/usr/bin/env python3
"""Fails if the manual stops keeping the promises of the plan's checklist (§9.5).

    python3 scripts/check-manual.py

Four claims, each the mechanisable part of a checklist line. Everything else on that list — that
the prose is readable, that a figure says what a reader needs — is a person's job; these four are
not, and a person would stop checking them by the third chapter.

1. **Every capability has an example.** `docs/manual/capabilities.csv` maps each row of the plan's
   table of promised capabilities to the chapter that shows it and a literal marker that must
   appear there. A capability whose example is deleted or whose API is renamed fails here.

2. **Every error is documented, and the reference appendix is exhaustive.** Each `BayesError` case in `modules/core` must appear in
   `docs/manual/appendix-b-errors.md`. A seventh error added without a line in the appendix fails.

3. **The examples obey the functional rules, and every line fits in 100 columns.** No `throw`, no `var`, no `while`, no `.get` on an
   `Option` or an `Either`, no `asInstanceOf` in any code block of the manual: the examples are
   read as the house style, so a shortcut in one of them is worse than a shortcut in the library.

4. **Every numbered chapter points at the glossary**, where the vocabulary it uses is defined.

5. **Every chapter that talks about a diagnostic, a check or a forecast shows the figure.** Not a
   description of one: a figure, drawn by `Html.embed` when the manual is built. That the figures
   actually render is `scripts/smoke-plots.mjs`, which a browser runs in CI.

6. **The manual is navigable, and the README does not drift.** Every chapter file is listed in the
   manual's own index, every link in the index points at a file that exists, and the repository's
   README points at the manual. Its quick start is not compiled by mdoc — the README is not a
   manual page — so instead every line of it must appear in chapter 0, which is: the README shows
   a subset of a block mdoc compiles on every build.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs" / "manual"
INDEX = MANUAL / "README.md"
CAPABILITIES = MANUAL / "capabilities.csv"
APPENDIX_B = MANUAL / "appendix-b-errors.md"
ERRORS = ROOT / "modules" / "core" / "src" / "main" / "scala" / "bayes4s" / "BayesError.scala"
README = ROOT / "README.md"
REFERENCE = MANUAL / "appendix-e-every-knob.md"

# The files whose public vocabulary the reference appendix claims to list in full, and the pattern
# that finds each name in them. A name that exists in the code and is not in the appendix means the
# appendix's "every" is a lie, which is the only thing that makes a reference page worth having.
EXHAUSTIVE = [
    (
        "Constraint",
        ROOT / "modules/transforms/src/main/scala/bayes4s/Constraint.scala",
        re.compile(r"^  case (\w+)", re.M),
        "Constraint.{}",
    ),
    (
        "Init",
        ROOT / "modules/inference/src/main/scala/bayes4s/Mcmc.scala",
        re.compile(r"^  case (\w+)\(", re.M),
        "Init.{}",
    ),
    (
        "RunSettings",
        ROOT / "modules/inference/src/main/scala/bayes4s/Mcmc.scala",
        re.compile(r"final case class RunSettings\(([^)]*)\)", re.S),
        "`{}`",
    ),
    (
        "AdaptationSettings",
        ROOT / "modules/inference/src/main/scala/bayes4s/Adaptation.scala",
        re.compile(r"final case class AdaptationSettings\(([^)]*)\)", re.S),
        "`{}`",
    ),
    (
        "NutsSettings",
        ROOT / "modules/inference/src/main/scala/bayes4s/Nuts.scala",
        re.compile(r"final case class NutsSettings\(([^)]*)\)", re.S),
        "`{}`",
    ),
    (
        "component",
        ROOT / "modules/nowcast/src/main/scala/bayes4s",
        None,
        "`{}(",
    ),
    (
        "Catalogue",
        ROOT / "modules/plot/src/main/scala/bayes4s/Catalogue.scala",
        re.compile(r"^  def (\w+)\(", re.M),
        "`{}(",
    ),
    (
        "ParamSpec",
        ROOT / "modules/transforms/src/main/scala/bayes4s/ParamSpec.scala",
        None,
        "ParamSpec.{}",
    ),
]

# `.get` on a Map is legitimate and returns an Option; `.get` on an Option or an Either is the
# shortcut the rules forbid. Only the forms that actually appear in Scala are matched, so that a
# word like "target" cannot trip the check.
FORBIDDEN = [
    (re.compile(r"\bthrow\b"), "throw"),
    (re.compile(r"\bvar\s+[a-zA-Z_]"), "var"),
    (re.compile(r"\bwhile\s*\("), "while"),
    (re.compile(r"\basInstanceOf\b"), "asInstanceOf"),
    (re.compile(r"\.toOption\.get\b"), ".toOption.get"),
    (re.compile(r"\.right\.get\b"), ".right.get"),
    (re.compile(r"\.get\b(?!\s*\()"), ".get"),
]

# A line ending in this comment is allowed its flagged construct, with the reason beside it.
ALLOWED = "// manual-ok"


def code_blocks(text: str) -> list[tuple[int, str]]:
    """Every line inside a fenced Scala block, with its line number."""
    lines = text.splitlines()
    inside = False
    collected = []
    for number, line in enumerate(lines, start=1):
        if line.startswith("```"):
            inside = line.startswith("```scala")
            continue
        if inside:
            collected.append((number, line))
    return collected


def check_capabilities() -> list[str]:
    problems = []
    with CAPABILITIES.open() as handle:
        rows = list(csv.DictReader(line for line in handle if not line.startswith("#")))
    if not rows:
        return ["capabilities.csv holds no capability at all"]
    for row in rows:
        chapter = MANUAL / row["chapter"]
        if not chapter.exists():
            problems.append(f"{row['capability']}: no chapter {row['chapter']}")
        elif row["marker"] not in chapter.read_text():
            problems.append(
                f"{row['capability']}: {row['chapter']} no longer shows `{row['marker']}`"
            )
    return problems


def check_reference() -> list[str]:
    """Every name the reference appendix claims to list, actually listed."""
    problems = []
    text = REFERENCE.read_text()
    for label, source, pattern, template in EXHAUSTIVE:
        if not source.exists():
            problems.append(f"{label}: {source.name} has moved")
            continue
        code = (
            "\n".join(f.read_text() for f in sorted(source.glob("*.scala")))
            if source.is_dir()
            else source.read_text()
        )
        if label == "component":
            # A regex across the file picks up whatever class happens to sit before a component's
            # closing paren, so each `extends …Component` is matched to the declaration above it.
            names = []
            for line in code.splitlines():
                if line.startswith("final case class "):
                    pending = line[len("final case class ") :].split("(")[0]
                elif line.startswith(") extends RateComponent") or line.startswith(
                    ") extends DelayComponent"
                ):
                    names.append(pending)
        elif label == "ParamSpec":
            # The public constructors of the companion, which is where a model's blocks come from.
            body = code[code.index("object ParamSpec") :]
            names = re.findall(r"^  def (\w+)[\[(]", body, re.M)
            names = [n for n in names if n not in {"apply", "readAt"}]
        elif pattern is None:
            names = []
        elif label.endswith("Settings"):
            found = pattern.search(code)
            names = (
                re.findall(r"(\w+):", found.group(1)) if found else []
            )
        else:
            names = pattern.findall(code)
        for name in sorted(set(names)):
            # A plain substring would accept `ParamSpec.pureX` as evidence for `ParamSpec.pure`,
            # so a match that ends on a word character has to end at a word boundary. A template
            # that already ends in punctuation — a backtick, an opening bracket — needs no guard.
            wanted = template.format(name)
            needle = re.escape(wanted)
            if wanted[-1].isalnum() or wanted[-1] == "_":
                needle += r"(?![A-Za-z0-9_])"
            if re.search(needle, text) is None:
                problems.append(f"{label}.{name} is in no row of the reference appendix")
    return problems


def check_errors() -> list[str]:
    cases = re.findall(r"final case class (\w+)\(", ERRORS.read_text())
    documented = APPENDIX_B.read_text()
    return [f"BayesError.{case} is in no appendix B entry" for case in cases if case not in documented]


def check_examples() -> list[str]:
    problems = []
    for chapter in sorted(MANUAL.glob("*.md")):
        for number, line in code_blocks(chapter.read_text()):
            if ALLOWED in line:
                continue
            for pattern, name in FORBIDDEN:
                if pattern.search(line):
                    problems.append(f"{chapter.name}:{number}: `{name}` in an example")
                    break
    return problems


# The chapters whose subject is a diagnostic, a check or a forecast. Each must draw at least one
# figure, which is the mechanisable half of the plan's requirement that such a chapter show one.
MUST_DRAW = [
    "00-quick-start.md",
    "04-diagnosing.md",
    "05-checking-a-model.md",
    "06-predicting.md",
    "07-nowcasting-step-by-step.md",
    "08-scoring-a-forecast.md",
    "11-multilevel-models.md",
    "12-plots.md",
]


def check_glossary_links() -> list[str]:
    """Every numbered chapter points at the glossary where it first needs one of its terms.

    §9.6's rule is that a chapter naming both the book's term and the API's names the glossary at
    the first occurrence. Where that occurrence is, is a judgement; that there is one is not.
    """
    problems = []
    for chapter in sorted(MANUAL.glob("[0-9][0-9]-*.md")):
        if "glossary.md" not in chapter.read_text():
            problems.append(f"{chapter.name} never points at the glossary")
    return problems


def check_figures() -> list[str]:
    problems = []
    for name in MUST_DRAW:
        chapter = MANUAL / name
        if not chapter.exists():
            problems.append(f"{name} does not exist")
            continue
        text = chapter.read_text()
        if "Html.embed" not in text:
            problems.append(f"{name} draws no figure")
        elif "Html.scripts" not in text:
            # A figure without the scripts is a container that stays empty, and only a browser
            # notices — which is how this check came to exist.
            problems.append(f"{name} draws a figure but never emits Html.scripts")
    return problems


def check_width() -> list[str]:
    """The manual is read in a terminal as often as in a browser: 100 columns, like the code.

    Tables are exempt, because a markdown table cannot be wrapped, and so is a trailing HTML
    comment: the vocabulary check's escape has to sit at the end of the line it excuses, and a
    comment is not text a reader sees.
    """
    problems = []
    trailing = re.compile(r"(\s*<!--.*?-->)+\s*$")
    for chapter in sorted(MANUAL.glob("*.md")):
        for number, line in enumerate(chapter.read_text().splitlines(), start=1):
            visible = trailing.sub("", line)
            if len(visible) > 100 and not visible.strip().startswith("|"):
                problems.append(f"{chapter.name}:{number}: {len(visible)} columns")
    return problems


def check_navigation() -> list[str]:
    problems = []
    index = INDEX.read_text()
    linked = set(
        re.findall(
            r"\]\((\d\d-[a-z0-9-]+\.md|appendix-[a-z]-[a-z-]+\.md|glossary\.md|the-ideas\.md)\)",
            index,
        )
    )
    for chapter in sorted(MANUAL.glob("*.md")):
        if chapter.name == INDEX.name:
            continue
        if chapter.name not in linked:
            problems.append(f"{chapter.name} is in no row of the manual's index")
    for target in sorted(linked):
        if not (MANUAL / target).exists():
            problems.append(f"the index links {target}, which does not exist")
    readme = README.read_text()
    if "docs/manual/README.md" not in readme:
        problems.append("the repository README does not point at the manual")
    if "Mcmc.runNuts" not in readme:
        problems.append("the repository README carries no quick start")
    else:
        quick_start = MANUAL / "00-quick-start.md"
        compiled = {line.strip() for _, line in code_blocks(quick_start.read_text())}
        for number, line in code_blocks(readme):
            stripped = line.strip()
            if len(stripped) > 3 and stripped not in compiled:
                problems.append(
                    f"README:{number}: `{stripped}` is in no code block of 00-quick-start.md"
                )
    return problems


def main() -> int:
    sections = [
        ("capabilities without an example", check_capabilities()),
        ("errors missing from appendix B", check_errors()),
        ("names missing from the reference appendix", check_reference()),
        ("examples that break the functional rules", check_examples()),
        ("lines wider than 100 columns", check_width()),
        ("chapters that should show a figure", check_figures()),
        ("chapters that never point at the glossary", check_glossary_links()),
        ("navigation", check_navigation()),
    ]
    failed = False
    for title, problems in sections:
        if problems:
            failed = True
            print(f"{title}:")
            for problem in problems:
                print(f"  {problem}")
    if failed:
        return 1
    chapters = len(list(MANUAL.glob("*.md"))) - 1
    with CAPABILITIES.open() as handle:
        count = sum(1 for line in handle if line.strip() and not line.startswith("#")) - 1
    print(f"{chapters} chapter(s), {count} capability(ies), every one with an example")
    return 0


if __name__ == "__main__":
    sys.exit(main())
