#!/usr/bin/env python3
"""Fails if a private term appears in the repository or in a commit message.

    export BAYES4S_PRIVATE_TERMS_KEY="<the same secret phrase>"
    python3 scripts/check-private-terms.py                    # files tracked by git
    python3 scripts/check-private-terms.py --commits A..B      # commit messages
    python3 scripts/check-private-terms.py --paths docs/design.md

The script knows digests only: it cannot print the term it found, and so it never does. It names
the file, the line and the first bytes of the digest; matching that against the plain-text list,
outside the repository, is the author's job.

With neither digests nor key it passes, saying that nothing is configured yet. Once
`.private-terms.sha256` exists, a missing key is an error: nothing can be verified without it.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from private_terms import (  # noqa: E402
    DIGEST_FILE,
    KEY_ENV,
    digest,
    load_key,
    read_digests,
    tokens,
)

SKIP_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".pdf", ".zip", ".jar", ".class", ".ico")


def git(*args: str) -> list[str]:
    done = subprocess.run(
        ["git", *args], capture_output=True, text=True, check=False
    )
    if done.returncode != 0:
        return []
    return [line for line in done.stdout.splitlines() if line.strip()]


def scan(name: str, text: str, digests: set[str], key: bytes) -> list[str]:
    """Digests found, line by line, never giving away the term itself."""
    hits = []
    for number, line in enumerate(text.splitlines(), start=1):
        for token in tokens(line):
            found = digest(token, key)
            if found in digests:
                hits.append(f"{name}:{number}: private term (digest {found[:12]}...)")
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description="Refuse private terms in the repository.")
    parser.add_argument("--commits", help="git range whose commit messages are checked, e.g. A..B")
    parser.add_argument("--paths", nargs="*", help="check these files instead of everything tracked")
    parser.add_argument("--key-file", help=f"file holding the HMAC key (default: ${KEY_ENV})")
    parser.add_argument("--digests", default=DIGEST_FILE)
    args = parser.parse_args()

    digest_path = Path(args.digests)
    digests = read_digests(digest_path)
    key = load_key(args.key_file)

    if not digests:
        print(f"{digest_path} holds no digest: nothing to check (see docs/design.md, D-7)")
        return 0
    if key is None:
        print(
            f"{digest_path} holds {len(digests)} digests but no HMAC key is available: "
            f"set ${KEY_ENV} (CI secret) or pass --key-file. Refusing to pass silently.",
            file=sys.stderr,
        )
        return 2

    hits: list[str] = []

    if args.commits:
        for sha in git("rev-list", args.commits):
            message = "\n".join(git("show", "-s", "--format=%B", sha))
            hits += scan(f"commit {sha[:12]}", message, digests, key)

    if args.paths is not None:
        files = args.paths
    elif args.commits:
        files = []
    else:
        files = git("ls-files")

    for name in files:
        path = Path(name)
        if not path.is_file() or path.name == digest_path.name:
            continue
        if path.suffix.lower() in SKIP_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        hits += scan(name, text, digests, key)

    if hits:
        print(f"{len(hits)} private-term occurrence(s) found:", file=sys.stderr)
        for hit in hits:
            print(f"  {hit}", file=sys.stderr)
        return 1

    scope = "commit messages" if args.commits and args.paths is None else "tracked files"
    print(f"no private term in {scope} ({len(digests)} digests checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
