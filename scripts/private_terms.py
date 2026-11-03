"""Shared by hash-private-terms.py and check-private-terms.py.

The repository holds digests only (`.private-terms.sha256`). The plain-text term list lives
outside it, and so does the HMAC key: without the key the digests do not fall to a dictionary
attack, which a bare SHA-256 of ordinary words certainly would.

No function here ever prints a term or the key.
"""

from __future__ import annotations

import hmac
import os
import re
import sys
import unicodedata
from hashlib import sha256
from pathlib import Path

DIGEST_FILE = ".private-terms.sha256"
KEY_ENV = "BAYES4S_PRIVATE_TERMS_KEY"

_WORD = re.compile(r"[0-9a-z]+")


def load_key(key_file: str | None) -> bytes | None:
    """The HMAC key, from --key-file or the environment. Never from the repository."""
    if key_file:
        path = Path(key_file).expanduser()
        if not path.is_file():
            return None
        return path.read_bytes().strip()
    raw = os.environ.get(KEY_ENV, "").strip()
    return raw.encode("utf-8") if raw else None


def normalise(text: str) -> list[str]:
    """Normalised words: lower case, accents folded, punctuation dropped."""
    folded = unicodedata.normalize("NFKD", text.lower())
    stripped = "".join(c for c in folded if not unicodedata.combining(c))
    return _WORD.findall(stripped)


def tokens(text: str) -> set[str]:
    """Words, and pairs of consecutive words."""
    words = normalise(text)
    pairs = (f"{a} {b}" for a, b in zip(words, words[1:]))
    return set(words) | set(pairs)


def digest(token: str, key: bytes) -> str:
    return hmac.new(key, token.encode("utf-8"), sha256).hexdigest()


def read_digests(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        entry = line.strip()
        if entry and not entry.startswith("#"):
            out.add(entry.lower())
    return out


def fail(message: str) -> None:
    print(f"check-private-terms: {message}", file=sys.stderr)
