#!/usr/bin/env python3
"""Prove a Spotless-only change is formatting, not behaviour.

A whole-repository `spotlessApply` touches dozens of files at once, and "the diff looks like
whitespace" is not evidence: an import can be dropped, a statement can move between blocks, a
comment can be lost. This checks every changed file against its committed version and refuses
anything it cannot account for.

A file passes if EITHER holds:

  A. Its whitespace-insensitive LINE MULTISET is unchanged. Tolerates reordering (ktlint sorts
     imports and moves a misplaced comment above its annotation) while catching any added,
     removed or altered line.
  B. Its token stream is unchanged after removing whitespace, `;` separators inside one-line
     lambdas and trailing commas -- with the import block compared as a sorted set, and two
     known ktlint edits allowed: braces added around a single-expression `else`, and a redundant
     `: Unit` return type dropped from a block-bodied function.

Anything else is reported and exits non-zero, so a real edit cannot ride in on a formatting PR.

Usage: spotless-formatting-equivalence.py [<base-ref>]   (default HEAD)
"""
import collections
import re
import subprocess
import sys


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def squeeze(text):
    return re.sub(r"\s+", "", text)


def tokens(text):
    t = squeeze(text).replace(";", "")
    t = re.sub(r",([)}])", r"\1", t)          # trailing commas ktlint adds
    t = t.replace("):Unit{", "){")            # redundant Unit on a block body
    # ktlint wraps a single-expression `else` in braces. Removing ALL block braces is too blunt
    # (it would hide a statement moved between blocks), so match only that shape: `} else X }`
    # becomes `} else { X }`. The inner expression must not itself contain a brace.
    # ktlint wraps a single-expression `else` in braces. Strip that ONE added pair from the new
    # spelling so it compares against the old one; an unbraced `else` is left as it is. The inner
    # expression may not contain braces, so this cannot swallow a real block.
    t = re.sub(r"\}else\{([^{}]*?)\}\}", r"}else\1}", t)
    return t


def split_imports(text):
    lines = text.split("\n")
    imports = sorted(l.strip() for l in lines if l.strip().startswith("import "))
    rest = "\n".join(l for l in lines if not l.strip().startswith("import "))
    return imports, rest


def line_multiset(text):
    return collections.Counter(squeeze(l) for l in text.split("\n") if l.strip())


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    files = [f for f in git("diff", "--name-only", base).split("\n") if f.endswith(".kt")]
    if not files:
        print("no Kotlin files changed")
        return 0
    unexplained = []
    for path in files:
        before = git("show", f"{base}:{path}")
        with open(path, encoding="utf-8") as handle:
            after = handle.read()
        if line_multiset(before) == line_multiset(after):
            continue
        imports_before, rest_before = split_imports(before)
        imports_after, rest_after = split_imports(after)
        if imports_before == imports_after and tokens(rest_before) == tokens(rest_after):
            continue
        unexplained.append(path)
    print(f"{len(files)} Kotlin file(s) checked, {len(unexplained)} not accounted for")
    for path in unexplained:
        print(f"  NOT FORMATTING-ONLY: {path}")
    return 1 if unexplained else 0


if __name__ == "__main__":
    sys.exit(main())
