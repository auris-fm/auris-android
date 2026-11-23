#!/usr/bin/env python3
"""Prove a Spotless-only change is formatting, not behaviour.

A whole-repository `spotlessApply` touches dozens of files at once, and "the diff looks like
whitespace" is not evidence: an import can be dropped, a statement can move between blocks, a
comment can be lost, a space can vanish from a message. This checks every changed file against
its committed version and refuses anything it cannot account for.

The file is split into CODE and LITERAL spans first. Everything below applies to code only;
literal text is carried through untouched, so no transform can reach inside a string -- including
triple-quoted raw strings, `${...}` templates and backtick identifiers, which a per-line quote scanner
would mis-read.

A file passes if any of these holds:

  A. Its lines are the same, IN ORDER, with layout whitespace removed. Import lines are compared
     separately as a set, because ktlint sorts them.
  B. Its code token stream is unchanged after removing layout whitespace, `;` separators inside
     one-line lambdas and trailing commas, allowing two named ktlint edits: braces added around a
     single-expression `else`, and a redundant `: Unit` dropped from a block-bodied function.
     This path exists because ktlint also joins and splits statements across lines.
  C. Failing those, its code tokens are unchanged in order and its comment lines are the same
     multiset -- permitting only a comment MOVING (ktlint puts a misplaced comment above its
     annotation), not a comment being reworded or deleted.

Root-level `.kts` files are included, mirroring the Spotless `kotlinGradle` target.

Usage: spotless-formatting-equivalence.py [<base-ref>]   (default HEAD)
"""
import collections
import re
import subprocess
import sys


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def _is_char_literal(text, i):
    """True when the quote at i opens a Kotlin character literal rather than something else.

    Kotlin has no single-quoted strings, so a stray apostrophe (in a comment, say) must not open
    a span: it would swallow the code after it. Accept only '\\x' and 'x' shapes.
    """
    if text.startswith("'\\", i):
        j = i + 2
        while j < len(text) and text[j] != "'" and text[j] != "\n":
            j += 1
        return j < len(text) and text[j] == "'"
    return i + 2 < len(text) and text[i + 2] == "'" and text[i + 1] != "\n"


TRIPLE = chr(34) * 3


def split_spans(text):
    '''Split source into [('code', s) | ('lit', s)] spans.

    Handles // and /* */ comments (comments are code here; they are handled separately),
    ordinary quoted strings with escapes, triple-quoted raw strings (no escapes inside), and
    backtick identifiers. A `${...}` template inside a string stays part of the literal: its
    contents are compared verbatim, which is stricter than needed and never wrong.
    '''
    spans = []
    buf = []
    i = 0
    n = len(text)

    def flush_code():
        if buf:
            spans.append(("code", "".join(buf)))
            buf.clear()

    while i < n:
        ch = text[i]
        if text.startswith(TRIPLE, i):
            flush_code()
            end = text.find(TRIPLE, i + 3)
            end = n if end == -1 else end + 3
            spans.append(("lit", text[i:end]))
            i = end
            continue
        if ch in '"`' or (ch == "'" and _is_char_literal(text, i)):
            flush_code()
            j = i + 1
            while j < n:
                if ch == '"' and text[j] == "\\":
                    j += 2
                    continue
                if text[j] == ch:
                    j += 1
                    break
                if text[j] == "\n" and ch == '"':
                    break  # unterminated on this line; stop rather than swallow the file
                j += 1
            spans.append(("lit", text[i:j]))
            i = j
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end == -1 else end
            buf.append(text[i:end])
            i = end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            buf.append(text[i:end])
            i = end
            continue
        buf.append(ch)
        i += 1
    flush_code()
    return spans


def strip_code_whitespace(code):
    return re.sub(r"\s+", "", code)


def render(text, transform):
    """Apply `transform` to code spans only; literals pass through verbatim."""
    return "".join(transform(s) if kind == "code" else s for kind, s in split_spans(text))


def strip_layout(text):
    return render(text, strip_code_whitespace)


def tokens(text):
    def transform(code):
        t = strip_code_whitespace(code).replace(";", "")
        t = re.sub(r",([)}])", r"\1", t)          # trailing commas ktlint adds
        t = t.replace("):Unit{", "){")            # redundant Unit on a block body
        # ktlint wraps a single-expression `else` in braces. Strip that ONE added pair so the
        # braced spelling compares against the unbraced one. The inner expression may not contain
        # braces, so this cannot swallow a real block.
        t = re.sub(r"\}else\{([^{}]*?)\}", r"}else\1", t)
        return t

    return render(text, transform)


def split_imports(text):
    lines = text.split("\n")
    imports = sorted(l.strip() for l in lines if l.strip().startswith("import "))
    rest = "\n".join(l for l in lines if not l.strip().startswith("import "))
    return imports, rest


def ordered_lines(text):
    """Non-blank lines in order, layout whitespace removed -- except inside a multi-line literal.

    A raw string's interior lines ARE content: squeezing them there would make an edited message
    compare equal. Walk the spans so each line knows which parts of it are literal.
    """
    # Mark every character position that belongs to a literal.
    inside = [False] * len(text)
    pos = 0
    for kind, chunk in split_spans(text):
        if kind == "lit":
            for k in range(pos, pos + len(chunk)):
                inside[k] = True
        pos += len(chunk)
    out = []
    pos = 0
    for line in text.split("\n"):
        blank_inside_literal = not line.strip() and any(
            inside[pos : pos + len(line) + 1]
        )
        if (line.strip() or blank_inside_literal) and not line.strip().startswith("import "):
            kept = []
            quote = None
            for offset, ch in enumerate(line):
                index = pos + offset
                if inside[index]:
                    kept.append(ch)
                    continue
                if quote:
                    kept.append(ch)
                    if ch == quote:
                        quote = None
                elif ch in '"`':
                    quote = ch
                    kept.append(ch)
                elif not ch.isspace():
                    kept.append(ch)
            out.append("".join(kept))
        pos += len(line) + 1
    return out


def is_comment(line):
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*")


def code_tokens(text):
    return tokens("\n".join(l for l in text.split("\n") if not is_comment(l)))


def comment_multiset(text):
    return collections.Counter(strip_layout(l) for l in text.split("\n") if is_comment(l))


def equivalent(before, after):
    imports_before, rest_before = split_imports(before)
    imports_after, rest_after = split_imports(after)
    if imports_before != imports_after:
        return False
    if ordered_lines(before) == ordered_lines(after):
        return True
    if tokens(rest_before) == tokens(rest_after):
        return True
    return code_tokens(rest_before) == code_tokens(rest_after) and comment_multiset(
        before
    ) == comment_multiset(after)


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
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
        if not equivalent(before, after):
            unexplained.append(path)
    print(f"{len(files)} Kotlin file(s) checked, {len(unexplained)} not accounted for")
    for path in unexplained:
        print(f"  NOT FORMATTING-ONLY: {path}")
    return 1 if unexplained else 0


if __name__ == "__main__":
    sys.exit(main())
