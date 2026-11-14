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
# SCOPE -- comment text, plus the string forms whose contents are prose. Matching raw text
# would either fail on day one or be loosened until it caught nothing, so the guard first
# works out what is comment and what is code for each language, and matches only the former.
#
# A lane name collides with real syntax -- a framework resource attribute, a scoped package
# import -- but those collisions are in CODE, and code is not scanned. So there is no
# exemption for any lane shape, including a lane followed by ':' or '/', and a resource
# attribute written inside a comment IS a finding. Do not add an exemption back: this tree
# has no resource attribute in a comment, so one would cover a collision the guard never
# sees while letting through the most common citation there is.
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
# The lane set and both shapes are core's. The POSIX awk in CI has no lookahead, so a "whole word"
# is expressed as the lane followed by a non-word character or the end of the text.
CHAT_LANES='spec|reviewer|cloud|trainer|guru|android|ios|cindy|nightshift|merlinran'
# Case-insensitive, as core's `i` flag makes it, so a capitalised lane name reads the same as
# its lowercase form.
CHAT_LANE_ERE="[@]([Ss][Pp][Ee][Cc]|[Rr][Ee][Vv][Ii][Ee][Ww][Ee][Rr]|[Cc][Ll][Oo][Uu][Dd]|[Tt][Rr][Aa][Ii][Nn][Ee][Rr]|[Gg][Uu][Rr][Uu]|[Aa][Nn][Dd][Rr][Oo][Ii][Dd]|[Ii][Oo][Ss]|[Cc][Ii][Nn][Dd][Yy]|[Nn][Ii][Gg][Hh][Tt][Ss][Hh][Ii][Ff][Tt]|[Mm][Ee][Rr][Ll][Ii][Nn][Rr][Aa][Nn])([^A-Za-z0-9_-]|$)"

# A lane name followed by a hex message id (7+ characters, same minimum as core, because an
# ordinary word follows a lane name in prose far more often than an id does).
CHAT_HEX_ERE="(^|[^A-Za-z0-9_-])([Ss][Pp][Ee][Cc]|[Rr][Ee][Vv][Ii][Ee][Ww][Ee][Rr]|[Cc][Ll][Oo][Uu][Dd]|[Tt][Rr][Aa][Ii][Nn][Ee][Rr]|[Gg][Uu][Rr][Uu]|[Aa][Nn][Dd][Rr][Oo][Ii][Dd]|[Ii][Oo][Ss]|[Cc][Ii][Nn][Dd][Yy]|[Nn][Ii][Gg][Hh][Tt][Ss][Hh][Ii][Ff][Tt]|[Mm][Ee][Rr][Ll][Ii][Nn][Rr][Aa][Nn]) +[0-9a-fA-F]{7,}([^A-Za-z0-9]|$)"

# Directories skipped, by name or root-relative path. A name without a slash matches any path
# segment; a path with a slash is root-relative.
EXCLUDED_DIRS='build .gradle .git config/chat-reference-guard/fixtures'


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
# Working out what is comment and what is code is what keeps a framework resource attribute and
# a scoped package import out of the result: both are code, and code is not scanned. String
# literals are tracked so a lane name inside one is not mistaken for prose.
# Comment and string syntax for an extension. Mirrors core PR 172's table so one rule holds in
# all three repos. A language's own delimiters are the only thing that can answer "is this a
# comment", and generalising from one language to all of them got real files wrong: a Python
# docstring holding /* started a phantom block, and a TS regex literal holding a quote hid the
# rest of its line.
#
#   c     two-slash line, block, double and single quotes        kt java kts cpp h swift mm
#   js    two-slash line, block, backtick strings, regex literals ts
#   go    two-slash line, block, backtick strings                 go
#   hash  hash line only, double and single quotes                sh py
#
# The second field adds Python's triple-quoted strings, which span lines.
chat_lang_for() {
    case "$1" in
        *.ts)                                      echo "js 0 0 0 1 0" ;;
        *.go)                                      echo "go 0 0 0 1 0" ;;
        *.py)                                      echo "hash 1 0 0 0 0" ;;
        *.sh)                                      echo "hash 0 1 0 1 0" ;;
        # YAML has no quote handling: an apostrophe in an unquoted value ("Don't merge yet") is an
        # ordinary character, and treating it as an opener hid the rest of the file. A hash needs
        # whitespace before it, since a value may contain one.
        *.yml|*.yaml)                              echo "hash 0 0 0 1 1" ;;
        # TOML carries the dependency rationale, so its comments are prose like a Python
        # docstring's; it has Python's triple-quote spelling too.
        *.toml)                                    echo "hash 1 0 0 0 0" ;;
        # Ruby and awk both take hash comments, and both appear in this tree: Ruby in the CI
        # scripts, awk in this guard. Neither has a block-comment form.
        *.rb|*.awk)                                echo "hash 0 0 0 0 0" ;;
        # A .gitignore explains why an entry is ignored, which is exactly the kind of note a
        # rationale reference attaches to. A hash needs a word break, as in shell.
        *.gitignore)                               echo "hash 0 0 0 1 0" ;;
        # Kotlin, C++ and Swift all have a triple-quoted raw string that spans lines. Java does
        # not, but listing it there costs nothing: a Java file cannot contain the delimiter
        # without it being a compile error, so the row is about what the language allows.
        *.kt|*.kts|*.cpp|*.h|*.swift|*.mm)         echo "c 1 0 1 1 0" ;;
        *.java)                                    echo "c 0 0 0 1 0" ;;
        *)                                         echo "c 0 0 0 1 0" ;;
    esac
}

scan_file() {
    read -r lang triple shellmode nestmode hashbreak yamlmode <<< "$(chat_lang_for "$1")"
    # LC_ALL=C for BYTE semantics. The walk indexes characters with substr() and length(), and
    # in a UTF-8 locale awk aborts on a multibyte character it cannot convert ("towc: multibyte
    # conversion failure") -- one curly quote in a string literal was enough. Byte semantics are
    # safe here because every character the walk looks for is ASCII: a UTF-8 continuation byte is
    # always >= 0x80 and can never be mistaken for a quote, a slash or a hash.
    LC_ALL=C awk -v lane_ere="$CHAT_LANE_ERE" \
        -v hex_ere="$CHAT_HEX_ERE" \
        -v lang="$lang" \
        -v triple="${triple:-0}" \
        -v shell="${shellmode:-0}" \
        -v nest="${nestmode:-0}" \
        -v hash_needs_break="${hashbreak:-1}" \
        -v yaml="${yamlmode:-0}" \
        -f "$(dirname "$0")/chat-reference-guard.awk" "$1"
    # An awk that died mid-file prints no findings, which reads exactly like a clean file. Any
    # non-zero status other than a normal exit is surfaced so the run cannot pass on a scan that
    # did not happen. (awk exits 0 on a clean scan and 2 on a fatal error.)
    local awk_status=$?
    if [ "$awk_status" -ne 0 ]; then
        echo "check-chat-references: the scanner failed on $1 (status $awk_status)" >&2
        return 2
    fi
}

# The file list comes from git, not from the disk. Walking the filesystem made the answer depend
# on the machine: an untracked file was read locally and not in CI, so a clean local run and a
# green CI run could disagree about what had been scanned. Git already knows which files belong
# to the repository.
#
# **A symlink is skipped, not followed.** Git records the link itself (mode 120000) rather than
# its target, and following one can leave the tree or loop while reading files nobody is
# reviewing. This repo tracks `.claude/skills` as a link.
#
# **An unknown file type fails.** Collecting only the types this script knows meant a new one --
# a .toml with dependency rationale, a .rb script -- was silently never read, and the scan said
# clean. Zero files read and zero references found are the same output, so every tracked file
# must either have a row in chat_lang_for, or be recorded in no_comments_reason.
# The file type's disposition: "scan" for a type chat_lang_for knows, or the reason it carries
# nothing to read. Printing nothing means no decision was made, which is an error.
type_disposition() {
    local file="$1" base key
    base="${file##*/}"
    # A basename that is itself a known type wins over its extension: `gradlew` has none, and
    # `build.gradle` must not be read as `.gradle` the way `slice(lastIndexOf("."))` would want.
    case "$base" in
        Dockerfile|gradlew|gradlew.bat|Gemfile|Dangerfile|CODEOWNERS|Fastfile|config|shared-pipeline-vars|repo-id)
            echo "nothing: a script or a flat declaration with no rationale comment" ; return ;;
        .gitignore) echo "scan"; return ;;
    esac
    key=".${base##*.}"
    case "$key" in
        *.kt|*.kts|*.java|*.cpp|*.h|*.swift|*.mm|*.ts|*.go|*.py|*.sh|*.yml|*.yaml|*.toml|*.rb|*.awk|*.gradle|*.proto)
            echo "scan"; return ;;
        *.png|*.jpg|*.jpeg|*.gif|*.webp|*.ico|*.ttf|*.otf|*.woff|*.woff2)
            echo "nothing: a binary asset"; return ;;
        *.wav|*.mp3|*.mp4|*.flac)
            echo "nothing: media"; return ;;
        *.so|*.jar|*.onnx|*.tflite|*.bin)
            echo "nothing: a compiled or model artefact"; return ;;
        *.pem|*.keystore|*.enc|*.lock|*.pub)
            echo "nothing: generated or opaque"; return ;;
        *.json|*.jsonl)
            echo "nothing: data; a fixture may record text as records"; return ;;
        *.xml)
            echo "nothing: resource markup; this guard reads no comments there"; return ;;
        *.md)
            echo "nothing: prose documents a handle openly as data; this check reads comments only"; return ;;
        *.txt|*.pro|*.properties|*.properties-example|*.configure|*.editorconfig|*.gitattributes|*.env-example)
            echo "nothing: a flat list or a value"; return ;;
        *.java-version|*.ruby-version)
            echo "nothing: a version string"; return ;;
        *.gitleaksignore|.a8c-secrets/repo-id)
            echo "nothing: a list of paths"; return ;;
    esac
    echo ""
}




# The file list comes from git, not from the disk. Walking the filesystem made the answer depend
# on the machine: an untracked file was read locally and not in CI, so a clean local run and a
# green CI run could disagree about what had been scanned. Git already knows which files belong
# to the repository.
#
# **A symlink is skipped, not followed, and so is a submodule.** Git records a link as its own
# entry rather than its target, and following one can leave the tree, read the same content twice,
# or loop. A submodule's files are not in this repository at all, so there is nothing to decide
# about their type and nothing to claim about having read them. This repo tracks `.claude/skills`
# as a link and has no submodules today.
#
# **An unknown file type fails.** Collecting only the types this script knows meant a new one -- a
# `.toml` with dependency rationale, a `.rb` script -- was silently never read, and the scan said
# clean. Zero files read and zero references found print the same thing, so every tracked file
# must either have a row in chat_lang_for or a reason recorded by type_disposition.
list_tracked_files() {
    local root="$1" entry head file mode
    if ! (cd "$root" && git rev-parse --git-dir >/dev/null 2>&1); then
        echo "check-chat-references: $root is not a git checkout." >&2
        echo "check-chat-references: this reads tracked files; a disk walk would scan a" >&2
        echo "check-chat-references: different set here than in CI." >&2
        return 2
    fi
    # **NUL-separated, read with `read -d ''`.** A path may contain a newline, and splitting on
    # newlines would truncate it without saying so. `read -d ''` is the one reader here that takes
    # a NUL delimiter intact: a command substitution drops NULs, and the awk in CI cannot use one
    # as RS. Each entry is "<mode> <sha> <stage>\t<path>".
    while IFS= read -r -d '' entry; do
        head="${entry%%$'\t'*}"
        file="${entry#*$'\t'}"
        [ -z "$file" ] && continue
        mode="${head%% *}"
        case "$mode" in 120000|160000) continue ;; esac
        excluded_path "$file" || continue
        printf '%s\n' "$file"
    done < <(cd "$root" && git ls-files -s -z) | sort
}

# A name matches any path segment at any depth; an entry containing a slash is root-relative.
excluded_path() {
    local file="$1" dir seg rest
    for dir in $EXCLUDED_DIRS; do
        case "$dir" in
            */*) [ "$file" = "$dir" ] && return 1
                 [ "${file#"$dir"/}" != "$file" ] && return 1 ;;
            *)   rest="$file/"
                 while [ -n "$rest" ]; do
                     seg="${rest%%/*}"; rest="${rest#*/}"
                     [ "$seg" = "$dir" ] && return 1
                 done ;;
        esac
    done
    return 0
}

run_selftest() {
    local base status=0 dirty_out clean_out
    base="$(cd "$(dirname "$0")/.." && pwd)/config/chat-reference-guard/fixtures"

    echo "== negative control: every planted reference must be found =="
    # Counting findings is not enough: a guard that catches five of seven planted references
    # still "fails" the fixture and still passes a count-based test. Each planted line carries
    # DENY, and the number of findings must equal the number of markers. A fixture with no
    # markers would make this vacuous, which is the failure the check exists to prevent.
    local dirty_out planted found_count
    dirty_out=$(for f in $(find -L "$base/dirty" -type f); do scan_file "$f"; done)
    planted=$(grep -rh 'DENY' "$base/dirty" | wc -l | tr -d ' ')
    found_count=$(printf '%s\n' "$dirty_out" | grep -c . )
    # A finding with no marker beside it is also a defect: either the fixture contains a
    # reference nobody planted (so the count is hiding it), or the guard fires on something the
    # fixture says is clean. Both were real while building this -- the markers were prose that
    # said "DENY", and a header sentence still said an exemption applied.
    local unmarked
    unmarked=$(printf '%s\n' "$dirty_out" | grep -v 'DENY' | grep -c . || true)
    if [ "$planted" -eq 0 ]; then
        echo "FAIL: the dirty fixture carries no DENY markers; this control proves nothing." >&2
        status=1
    elif [ "$unmarked" -ne 0 ]; then
        echo "FAIL: $unmarked finding(s) in the dirty fixture have no DENY marker beside them:" >&2
        printf '%s\n' "$dirty_out" | grep -v 'DENY' | sed 's/^/    /' >&2
        status=1
    elif [ "$found_count" -ne "$planted" ]; then
        echo "FAIL: $planted planted references, $found_count found. Missing:" >&2
        grep -rh 'DENY' "$base/dirty" | sed 's/^[[:space:]]*/    /' >&2
        status=1
    else
        echo "ok: all $planted planted references found"
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

    echo "== a scanner that dies must not read as a clean file =="
    # Same failure one level in: an awk that aborts prints no findings, and no findings is
    # indistinguishable from a clean file unless the status is checked. Exercised through the
    # real scan_file by pointing its awk at a script that exits 2 -- if that status were
    # swallowed, this would report success. The real scan_file is re-sourced here because a
    # function cannot otherwise have its awk replaced.
    local broken_root broken_status
    broken_root="$(mktemp -d)"
    printf 'val x = 1\n' > "$broken_root/x.kt"
    (
        awk() { command awk 'BEGIN { exit 2 }'; }
        eval "$(sed -n '/^scan_file()/,/^}/p' "$0")"
        scan_file "$broken_root/x.kt"
    ) >/dev/null 2>&1
    broken_status=$?
    rm -rf "$broken_root"
    if [ "$broken_status" -ne 2 ]; then
        echo "FAIL: a scan that died reported status $broken_status, not 2; it reads as clean." >&2
        status=1
    else
        echo "ok: a scan that died exits 2"
    fi

    # == the file list comes from git, and skips what git marks as a link ==
    # A directory of files is not what the check reads, so these build a real checkout. A symlink
    # and a gitlink are built with update-index --cacheinfo: that is the index entry the listing
    # actually reads, and it needs no submodule checkout that no one's CI has.
    local list_root
    list_root="$(mktemp -d)"
    (
        cd "$list_root" || exit 1
        git init -q .
        printf 'val planted = "@spec said"\n' > Plain.kt
        printf 'val linked = "@spec said"\n' > Target.kt
        ln -s Target.kt Link.kt
        git add Plain.kt Target.kt
        git update-index --add --cacheinfo 120000,"$(git rev-parse :Target.kt)",Link.kt
        git update-index --add --cacheinfo 160000,"$(git rev-parse :Target.kt)",Sub
    ) >/dev/null 2>&1
    listed="$(list_tracked_files "$list_root" 2>/dev/null | sort | tr '\n' ' ')"
    case "$listed" in
        "Plain.kt Target.kt ")
            echo "ok: symlink and gitlink skipped, files kept" ;;
        *)
            echo "FAIL: listing was '$listed'; a symlink or gitlink was read as a file." >&2
            status=1 ;;
    esac
    # A path containing a newline must survive: splitting on newlines truncates it silently.
    (
        cd "$list_root" || exit 1
        printf 'val nl = 1\n' > "$(printf 'has\nnewline.kt')"
        git add "$(printf 'has\nnewline.kt')"
    ) >/dev/null 2>&1
    if list_tracked_files "$list_root" 2>/dev/null | grep -q 'newline.kt'; then
        echo "ok: a path containing a newline survives the listing"
    else
        echo "FAIL: a path containing a newline was truncated or dropped." >&2
        status=1
    fi
    # A root that is not a checkout must fail, not report no files.
    if list_tracked_files "$(dirname "$list_root")" >/dev/null 2>&1; then
        echo "FAIL: a non-git root reported a file list instead of failing." >&2
        status=1
    else
        echo "ok: a non-git root fails instead of listing nothing"
    fi
    rm -rf "$list_root"

    if [ "$status" -eq 0 ]; then echo "selftest: PASS"; else echo "selftest: FAIL"; fi
    return "$status"
}

if [ "$mode" = "selftest" ]; then
    run_selftest
    exit $?
fi

if [ "${#paths[@]}" -eq 0 ]; then
    # The listing runs in a subshell, so its exit status does not survive `paths+=` on its own.
    # Writing it to a file is what keeps "cannot list" from reading as "nothing to scan": both
    # print no paths, and only one of them means the tree was covered.
    listing_file="$(mktemp)"
    if ! list_tracked_files "$root" > "$listing_file"; then
        rm -f "$listing_file"
        exit 2
    fi
    while IFS= read -r f; do paths+=("$f"); done < "$listing_file"
    rm -f "$listing_file"
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
undecided=0
for p in "${paths[@]}"; do
    case "$p" in
        /*) target="$p" ;;
        *)  target="$root/$p" ;;
    esac
    # Decide the type BEFORE reading it. An undecided type must fail whether or not its bytes
    # would have parsed, and a binary read as text is a different problem from an unread type.
    disposition="$(type_disposition "$p")"
    case "$disposition" in
        scan) ;;
        nothing:*) continue ;;
        *)
            echo "check-chat-references: no decision for '${p##*.}' (in $p)" >&2
            echo "check-chat-references: give it a row in chat_lang_for, or record in" >&2
            echo "check-chat-references: type_disposition that it carries nothing to read." >&2
            undecided=$((undecided + 1))
            continue ;;
    esac
    if [ ! -f "$target" ]; then
        echo "check-chat-references: cannot read $p" >&2
        echo "check-chat-references: refusing to report a result for files it could not read" >&2
        exit 2
    fi
    scan_file "$target" >> "$hits_file"
done

if [ "$undecided" -gt 0 ]; then
    echo "check-chat-references: $undecided file type(s) with no decision; nothing was proven." >&2
    exit 2
fi

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
