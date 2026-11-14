#!/usr/bin/env bash
# Fail on chat-lane references inside source comments.
#
# Why this exists:
# a comment that cites a reviewer's message id, a spec ruling or a quoted chat extract
# states WHO said a thing instead of WHAT the rule is. It goes stale the moment the
# conversation scrolls away, and the next reader cannot check it: the citation points at
# a message they may not be able to see, and the citation never carried the reasoning.
# A comment that states the rule survives on its own.
#
# The rule in one sentence: a comment says what holds and why, and never who said it.
#
# SCOPE -- comment text only, and that is the point. Lane names collide with real syntax:
#   @android:color/transparent   framework resource reference (Android XML and Kotlin)
#   @cloudflare/workers-types    a scoped package import (TypeScript)
#   @Spec... / @iOS...           a possible annotation
# Matching raw text would either fail on day one or be loosened until it caught nothing,
# so the guard reads comment bodies and matches whole words, rejecting a match that is
# followed by ':' or '/'.
#
# NOT a citation guard for issue trackers, documentation or upstream Pocket Casts code:
# those are out of the agreed scope, and widening it here would make the check fire on
# things nobody agreed to change.
#
# The negative control is part of the test, not an extra: a guard that never fires
# reports "clean" for every tree, and that reads exactly like a tree with no references.
#
# Usage:
#   scripts/check-chat-references.sh [--root DIR] [--paths PATH...]
#   scripts/check-chat-references.sh --selftest
#
# Exit: 0 clean, 1 references found, 2 usage or environment error.

set -o pipefail

# ONE PATTERN LIST, copied verbatim from core PR 172 (`.github/scripts/check-chat-references.mjs`)
# so all three repos hold one rule. Change it here only by changing it there first.
#
# The lane set and both shapes are core's. The POSIX awk in CI has no lookahead, so the rule is
# expressed as match-then-reject: the lane as a whole word, then a rejection for a following
# ':' or '/', which is what separates `@android:color` and `@cloudflare/` from a citation.
CHAT_LANES='spec|reviewer|cloud|trainer|guru|android|ios|cindy|merlinran'
# Case-insensitive, as core's `i` flag makes it: "Reviewer's" and "@Spec" are the same
# reference as their lowercase forms.
CHAT_LANE_ERE="[@]([Ss][Pp][Ee][Cc]|[Rr][Ee][Vv][Ii][Ee][Ww][Ee][Rr]|[Cc][Ll][Oo][Uu][Dd]|[Tt][Rr][Aa][Ii][Nn][Ee][Rr]|[Gg][Uu][Rr][Uu]|[Aa][Nn][Dd][Rr][Oo][Ii][Dd]|[Ii][Oo][Ss]|[Cc][Ii][Nn][Dd][Yy]|[Mm][Ee][Rr][Ll][Ii][Nn][Rr][Aa][Nn])([^A-Za-z0-9_-]|$)"
CHAT_RESOURCE_ERE="[@]([Ss][Pp][Ee][Cc]|[Rr][Ee][Vv][Ii][Ee][Ww][Ee][Rr]|[Cc][Ll][Oo][Uu][Dd]|[Tt][Rr][Aa][Ii][Nn][Ee][Rr]|[Gg][Uu][Rr][Uu]|[Aa][Nn][Dd][Rr][Oo][Ii][Dd]|[Ii][Oo][Ss]|[Cc][Ii][Nn][Dd][Yy]|[Mm][Ee][Rr][Ll][Ii][Nn][Rr][Aa][Nn])[:/]"

# A lane name followed by a hex message id (7+ characters, same minimum as core, because an
# ordinary word follows a lane name in prose far more often than an id does).
CHAT_HEX_ERE="(^|[^A-Za-z0-9_-])([Ss][Pp][Ee][Cc]|[Rr][Ee][Vv][Ii][Ee][Ww][Ee][Rr]|[Cc][Ll][Oo][Uu][Dd]|[Tt][Rr][Aa][Ii][Nn][Ee][Rr]|[Gg][Uu][Rr][Uu]|[Aa][Nn][Dd][Rr][Oo][Ii][Dd]|[Ii][Oo][Ss]|[Cc][Ii][Nn][Dd][Yy]|[Mm][Ee][Rr][Ll][Ii][Nn][Rr][Aa][Nn]) +[0-9a-fA-F]{7,}([^A-Za-z0-9]|$)"

# Extensions the rule applies to: source and test files in the repos' own code.
SOURCE_NAMES=(-name '*.kt' -o -name '*.java' -o -name '*.kts' -o -name '*.cpp' -o -name '*.h'
              -o -name '*.ts' -o -name '*.go' -o -name '*.py' -o -name '*.mm' -o -name '*.swift')

usage() {
    sed -n '3,38p' "$0" | sed 's/^# \{0,1\}//'
}

root="."
paths=()
mode="scan"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --root) root="$2"; shift 2 ;;
        --paths) shift; while [ "$#" -gt 0 ] && [ "${1#--}" = "$1" ]; do paths+=("$1"); shift; done ;;
        --selftest) mode="selftest"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# Print path:line: raw for every comment line that names a chat lane.
#
# Reading comment bodies rather than raw lines is what keeps `@android:` attributes and
# `@cloudflare/` imports out of the result. String and char literals are blanked first so
# a lane name inside a message template is not mistaken for a comment.
scan_file() {
    awk -v lane_ere="$CHAT_LANE_ERE" \
        -v hex_ere="$CHAT_HEX_ERE" \
        -v resource_ere="$CHAT_RESOURCE_ERE" \
        -f "$(dirname "$0")/chat-reference-guard.awk" "$1"
}

run_selftest() {
    local base status=0 dirty_out clean_out
    base="$(cd "$(dirname "$0")/.." && pwd)/config/chat-reference-guard/fixtures"

    echo "== negative control: the planted references must be found =="
    dirty_out=$(for f in $(find "$base/dirty" -type f); do scan_file "$f"; done)
    if [ -z "$dirty_out" ]; then
        echo "FAIL: the dirty fixture produced no findings; the guard cannot fail." >&2
        status=1
    else
        echo "ok: dirty fixture produced $(printf '%s\n' "$dirty_out" | wc -l | tr -d ' ') finding(s)"
        printf '%s\n' "$dirty_out" | sed 's/^/    /'
    fi

    echo "== false positives: real syntax that must NOT be flagged =="
    clean_out=$(for f in $(find "$base/clean" -type f); do scan_file "$f"; done)
    if [ -n "$clean_out" ]; then
        echo "FAIL: the clean fixture was flagged; the guard would fail on day one." >&2
        printf '%s\n' "$clean_out" | sed 's/^/    /' >&2
        status=1
    else
        echo "ok: clean fixture produced no findings"
    fi

    echo "== block-comment edge cases: a reopened block and code-then-comment =="
    local edge_dir edge_out
    edge_dir="$(mktemp -d)"
    printf '/* a */ /* b\n * @spec 1234567a inner\n */\n' > "$edge_dir/reopen.kt"
    printf 'val x = 1 // @reviewer 1234567b here\n' > "$edge_dir/oneline.kt"
    edge_out=$(for f in "$edge_dir"/*; do scan_file "$f"; done)
    if [ "$(printf '%s\n' "$edge_out" | grep -c .)" -ne 2 ]; then
        echo "FAIL: a reopened block or a code-then-comment line was not read." >&2
        printf '%s\n' "$edge_out" | sed 's/^/    /' >&2
        status=1
    else
        echo "ok: both edge cases found"
    fi
    rm -rf "$edge_dir"

    echo "== an unreadable path is an error, not a silent 'clean' =="
    local quiet_out quiet_status
    quiet_out=$("$0" --root "$base/clean" --paths does-not-exist.kt 2>&1)
    quiet_status=$?
    if [ "$quiet_status" -ne 2 ]; then
        echo "FAIL: an unreadable path exited $quiet_status, not 2; it would read as clean." >&2
        status=1
    else
        echo "ok: unreadable path exits 2"
    fi

    if [ "$status" -eq 0 ]; then echo "selftest: PASS"; else echo "selftest: FAIL"; fi
    return "$status"
}

if [ "$mode" = "selftest" ]; then
    run_selftest
    exit $?
fi

if [ "${#paths[@]}" -eq 0 ]; then
    # Two kinds of exclusion, and the difference is the leading './':
    #   './.git/*', './.gradle/*', './config/chat-reference-guard/fixtures/*'
    #       ROOT-ONLY. A nested `docs/.git` is not a build artefact, and a nested copy of the
    #       fixtures is not the guard's own planted references.
    #   '*/build/*'
    #       A NAME, excluded at ANY depth, because Gradle writes a `build/` into every module
    #       and the generated code in each is not source anyone edits.
    # Say which kind a rule is when adding one: a name that quietly became root-only, or a
    # root path that quietly started matching at depth, is how a guard stops covering a tree
    # while still reporting on it.
    while IFS= read -r f; do paths+=("$f"); done < <(
        cd "$root" && find . \( "${SOURCE_NAMES[@]}" \) \
            -not -path './.git/*' -not -path '*/build/*' -not -path './.gradle/*' \
            -not -path './config/chat-reference-guard/fixtures/*' -type f 2>/dev/null | sed 's|^\./||'
    )
fi

if [ "${#paths[@]}" -eq 0 ]; then
    echo "check-chat-references: no source files found under $root" >&2
    exit 2
fi

found=0
# A path that cannot be read is an ERROR, not a silent skip. A guard that answers "clean"
# because it scanned nothing is worse than one that fails: it reports the same thing for a
# clean tree and for a broken invocation, and nothing downstream can tell them apart.
hits_file="$(mktemp)"
trap 'rm -f "$hits_file"' EXIT
for p in "${paths[@]}"; do
    case "$p" in
        /*) target="$p" ;;
        *)  target="$root/$p" ;;
    esac
    if [ ! -f "$target" ]; then
        echo "check-chat-references: cannot read $p" >&2
        echo "check-chat-references: refusing to report a result for files it could not read" >&2
        exit 2
    fi
    scan_file "$target" >> "$hits_file"
done

while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    echo "$hit"
    found=$((found + 1))
done < "$hits_file"

if [ "$found" -gt 0 ]; then
    echo "" >&2
    echo "check-chat-references: $found chat-lane reference(s) in comments." >&2
    echo "State the rule the comment exists to record, not who said it." >&2
    exit 1
fi

echo "check-chat-references: clean (${#paths[@]} files scanned)"
exit 0
