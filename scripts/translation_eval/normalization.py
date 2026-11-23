"""Normalization used *only* for comparison, never applied to stored data.

The phase requires that raw outputs be preserved without destructive
normalization, and that any normalization be explicitly defined so a reader can
tell what was and was not ignored.

Two separate things live here, and conflating them would be a mistake:

* :func:`normalize_for_comparison` -- a small, documented set of equivalences
  used to decide whether two strings differ *at all*. It exists so that
  Arabic diacritic variance, trailing whitespace or an invisible bidi mark are
  not reported as a candidate "change". It intentionally does **not** reconcile
  synonymy, word order, or morphology.
* :func:`similarity` -- a character-level similarity value, exposed for
  diagnostics only. It is **not** a semantic score and must never be reported
  as one.

Because the normalized string is what equality is judged on, every difference
reported by the harness is a difference that survives this function. That is the
conservative direction: it can only ever make fewer cases look changed.
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass, field

#: Arabic diacritics (harakat) and the tatweel elongation character. Removing
#: these for comparison is safe because they do not change the lexical identity
#: of a word in unvocalised Arabic, which is how the corpus references are
#: written.
_ARABIC_MARKS = set("\u064b\u064c\u064d\u064e\u064f\u0650\u0651\u0652\u0653\u0654\u0655\u0670")
_TATWEEL = "\u0640"

#: Invisible formatting characters that carry no meaning: bidi controls, zero
#: width joiners/non-joiners, and the byte-order mark.
_INVISIBLE = set(
    "\u200b\u200c\u200d\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069\ufeff"
)


@dataclass(frozen=True)
class ComparisonConfig:
    """Which equivalences apply. Recorded in every run for reproducibility."""

    #: Collapse runs of whitespace and trim the ends.
    collapse_whitespace: bool = True
    #: Case-fold. Applied because the source is upper-case OCR of mixed-case
    #: dialogue and the references are unvocalised prose.
    fold_case: bool = True
    #: Drop Arabic diacritics and tatweel.
    strip_arabic_marks: bool = True
    #: Drop invisible bidi/formatting characters.
    strip_invisible: bool = True
    #: Unicode NFKC composition, so equivalent presentation forms compare equal.
    unicode_nfkc: bool = True

    def as_dict(self) -> dict[str, bool]:
        return {
            "collapse_whitespace": self.collapse_whitespace,
            "fold_case": self.fold_case,
            "strip_arabic_marks": self.strip_arabic_marks,
            "strip_invisible": self.strip_invisible,
            "unicode_nfkc": self.unicode_nfkc,
        }

    def fingerprint(self) -> str:
        """Stable identity of this configuration, for the run record."""
        import hashlib
        import json

        payload = json.dumps(self.as_dict(), sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


DEFAULT_COMPARISON = ComparisonConfig()

#: Deliberately empty: raw stored output is never touched by this module.
RAW_PRESERVED_NOTE = (
    "Raw outputs are stored verbatim. Normalization is applied only to compare."
)


def normalize_for_comparison(text: str, config: ComparisonConfig = DEFAULT_COMPARISON) -> str:
    """Return the comparison form of ``text``.

    Deterministic and idempotent: applying it twice yields the same string.
    """
    if not text:
        return ""
    out = text
    if config.strip_invisible:
        out = "".join(ch for ch in out if ch not in _INVISIBLE)
    if config.unicode_nfkc:
        out = unicodedata.normalize("NFKC", out)
    if config.strip_arabic_marks:
        out = "".join(ch for ch in out if ch not in _ARABIC_MARKS and ch != _TATWEEL)
    if config.fold_case:
        out = out.casefold()
    if config.collapse_whitespace:
        out = " ".join(out.split())
    else:
        out = out.strip()
    return out


def similar(left: str, right: str, config: ComparisonConfig = DEFAULT_COMPARISON) -> bool:
    """True when two strings are equivalent under the comparison rules."""
    return normalize_for_comparison(left, config) == normalize_for_comparison(right, config)


def similarity(a: str, b: str, config: ComparisonConfig = DEFAULT_COMPARISON) -> float:
    """Character-level similarity ratio in [0, 1], for diagnostics only.

    This is :func:`difflib.SequenceMatcher.ratio` over comparison forms. It is
    **not** a semantic-quality metric: it cannot distinguish a synonym from an
    error, and it penalises legitimate Arabic word-order changes. It is exposed
    so a reviewer can sort cases, not so a report can average it.
    """
    from difflib import SequenceMatcher

    left = normalize_for_comparison(a, config)
    right = normalize_for_comparison(b, config)
    if not left and not right:
        return 1.0
    if not left or not right:
        return 0.0
    return SequenceMatcher(None, left, right).ratio()


@dataclass
class NormalizationLog:
    """Records that normalization happened, and what it changed.

    Kept so a report can show that a case was reported "changed" only after
    normalization, rather than hiding a difference behind it.
    """

    applied: int = 0
    examples: list[dict[str, str | None]] = field(default_factory=list)

    def record(self, raw: str, normalized: str) -> None:
        if raw == normalized:
            return
        self.applied += 1
        if len(self.examples) < 5:
            self.examples.append({"raw": raw, "normalized": normalized})
