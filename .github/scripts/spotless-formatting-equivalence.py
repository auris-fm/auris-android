#!/usr/bin/env python3
"""Prove a Spotless-only change is formatting, not behaviour.

A whole-repository `spotlessApply` touches dozens of files at once, and "the diff looks like
whitespace" is not evidence: an import can be dropped, a statement can move between blocks, a
comment can be lost. This checks every changed file against its committed version and refuses
anything it cannot account for.

A file passes if EITHER holds:

  A. Its lines are the same, IN ORDER, with layout whitespace removed. Import lines are compared
     separately as a set, because ktlint sorts them.
  B. Its token stream is unchanged after removing layout whitespace, `;` separators inside
     one-line lambdas and trailing commas, allowing two named ktlint edits: braces added around a
     single-expression `else`, and a redundant `: Unit` dropped from a block-bodied function.
     This path exists because ktlint also joins and splits statements across lines.
  C. Failing those, its CODE tokens are unchanged in order and its comment lines are the same
     multiset -- which permits only a comment moving (ktlint puts a misplaced comment above its
     annotation), not a comment being reworded or deleted.

Whitespace INSIDE a string literal is never treated as layout, so editing a message cannot pass
as formatting. Root-level `.kts` files are included, mirroring Spotless's `kotlinGradle` target.

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
    # Layout-only squeeze, line by line, so string contents survive; then join.
    t = "".join(strip_layout(l) for l in text.split("\n")).replace(";", "")
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


def strip_layout(line):
    """Remove layout whitespace from a line, but never inside a string literal.

    Squeezing the whole line would make `"a b"` and `"ab"` compare equal, so a real edit to a
    message could pass as formatting. Only whitespace outside quotes is layout.
    """
    out = []
    quote = None
    i = 0
    while i < len(line):
        ch = line[i]
        if quote:
            out.append(ch)
            if ch == "\\" and i + 1 < len(line):
                out.append(line[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
            out.append(ch)
        elif not ch.isspace():
            out.append(ch)
        i += 1
    return "".join(out)


def ordered_lines(text):
    """The file's non-blank lines, in order, with layout whitespace removed.

    Order matters: a statement moved between blocks keeps the same multiset of lines but changes
    the sequence, and that is a behaviour change, not formatting. Import lines are excluded here
    because ktlint sorts them; they are compared separately as a set.
    """
    return [strip_layout(l) for l in text.split("\n") if l.strip() and not l.strip().startswith("import ")]


def is_comment(line):
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*")


def code_tokens(text):
    return tokens("\n".join(l for l in text.split("\n") if not is_comment(l)))


def comment_multiset(text):
    return collections.Counter(strip_layout(l) for l in text.split("\n") if is_comment(l))


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    # Mirror Spotless's targets: `kotlin` covers .kt under the source roots, `kotlinGradle`
    # covers root-level .kts. Missing .kts would let a build-script edit pass unchecked.
    files = [
        f
        for f in git("diff", "--name-only", base).split("\n")
        if f.endswith(".kt") or (f.endswith(".kts") and "/" not in f)
    ]
    if not files:
        print("no Kotlin files changed")
        return 0
    unexplained = []
    for path in files:
        before = git("show", f"{base}:{path}")
        with open(path, encoding="utf-8") as handle:
            after = handle.read()
        imports_before, rest_before = split_imports(before)
        imports_after, rest_after = split_imports(after)
        if imports_before != imports_after:
            unexplained.append(path)
            continue
        # Path A: same lines in the same order (imports excluded, compared as a set above).
        if ordered_lines(before) == ordered_lines(after):
            continue
        # Path B: ktlint also joins and splits statements across lines, so line order alone
        # cannot decide. Compare the token stream instead -- still ordered, still literal-safe.
        if tokens(rest_before) == tokens(rest_after):
            continue
        # Path C: a comment ktlint moves above its annotation keeps its text but changes position.
        # Allow that ONLY for whole comment lines: compare code tokens in order, and comment
        # lines as a multiset. Anything else about a comment (wording, deletion) still fails.
        if code_tokens(rest_before) == code_tokens(rest_after) and comment_multiset(
            before
        ) == comment_multiset(after):
            continue
        unexplained.append(path)
    print(f"{len(files)} Kotlin file(s) checked, {len(unexplained)} not accounted for")
    for path in unexplained:
        print(f"  NOT FORMATTING-ONLY: {path}")
    return 1 if unexplained else 0


if __name__ == "__main__":
    sys.exit(main())
