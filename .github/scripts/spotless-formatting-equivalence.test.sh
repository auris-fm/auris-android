#!/bin/bash
# Controls for the formatting-equivalence checker.
#
# The checker is the safety argument for a whole-repository reformat, so it has to be able to
# FAIL. Each case builds a two-commit repository -- a Kotlin file, then an edit -- and asserts
# the verdict. Formatting edits must pass; behaviour edits must not, including the ones that a
# naive whitespace comparison would wave through: a space removed inside a string, two statements
# swapped, a comment reworded, a raw-string or template literal altered.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
CHECKER="$DIR/.github/scripts/spotless-formatting-equivalence.py"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL: $1"; }

[ -f "$CHECKER" ] || { echo "FAIL: $CHECKER missing"; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# fixture <name> <original-content>: a repo whose HEAD holds File.kt with that content.
fixture() {
  R="$WORK/$1"; mkdir -p "$R"; cd "$R" || exit 1
  git init -q -b main .
  git config user.email t@example.invalid
  git config user.name t
  git config commit.gpgsign false
  cat > File.kt
  git add -A && git commit -qm base
}

# verdict: run the checker against HEAD (the working tree holds the edit).
verdict() { python3 "$CHECKER" HEAD >/dev/null 2>&1 && echo pass || echo fail; }
expect() { [ "$(verdict)" = "$1" ] && ok "$2" || bad "$2 (expected $1, got $(verdict))"; }

Q=$(printf '\042')      # a double quote, so fixtures need no escaping gymnastics
T=$(printf '\042\042\042')

# --- formatting edits must PASS -------------------------------------------------------------
fixture indent <<KT
fun f() {
    val x = 1
}
KT
printf 'fun f() {\n        val x = 1\n}\n' > File.kt
expect pass "re-indentation is formatting"

fixture wrap <<'KT'
private fun store(): Store =
    Store(app())
KT
printf 'private fun store(): Store = Store(app())\n' > File.kt
expect pass "joining a wrapped expression is formatting"

fixture imports <<'KT'
import b.Two
import a.One

fun f() = One()
KT
printf 'import a.One\nimport b.Two\n\nfun f() = One()\n' > File.kt
expect pass "sorting imports is formatting"

fixture unit <<'KT'
fun f(): Unit {
    g()
}
KT
printf 'fun f() {\n    g()\n}\n' > File.kt
expect pass "dropping a redundant : Unit is formatting"

fixture trailing <<'KT'
call(
    a,
    b
)
KT
printf 'call(\n    a,\n    b,\n)\n' > File.kt
expect pass "adding a trailing comma is formatting"

fixture elsebrace <<'KT'
val r = if (c) {
    A()
} else B
KT
printf 'val r = if (c) {\n    A()\n} else {\n    B\n}\n' > File.kt
expect pass "braces around a single-expression else are formatting"

fixture movedcomment <<'KT'
@Test
    // why this case exists
fun t() {}
KT
printf '// why this case exists\n@Test\nfun t() {}\n' > File.kt
expect pass "a comment moving above its annotation is formatting"

# --- behaviour edits must FAIL --------------------------------------------------------------
fixture literal-space <<KT
val msg = ${Q}gate off reason=${Q}
KT
printf 'val msg = %sgate offreason=%s\n' "$Q" "$Q" > File.kt
expect fail "removing a space INSIDE a string is not formatting"

fixture semicolon-literal <<KT
val msg = ${Q}a;b${Q}
KT
printf 'val msg = %sab%s\n' "$Q" "$Q" > File.kt
expect fail "removing a semicolon INSIDE a string is not formatting"

fixture raw-string <<KT
val msg = $T
  line one
  line two
$T
KT
printf 'val msg = %s\n  line one\n  linetwo\n%s\n' "$T" "$T" > File.kt
expect fail "editing a triple-quoted raw string is not formatting"

fixture template <<KT
val msg = ${Q}count=\${n}${Q}
KT
printf 'val msg = %stotal=\${n}%s\n' "$Q" "$Q" > File.kt
expect fail "editing a string template is not formatting"

fixture backtick <<'KT'
fun \`a test name\`() {}
KT
printf 'fun `atestname`() {}\n' > File.kt
expect fail "editing a backtick identifier is not formatting"

fixture swap <<'KT'
fun f() {
    val a = first()
    val b = second()
}
KT
printf 'fun f() {\n    val b = second()\n    val a = first()\n}\n' > File.kt
expect fail "swapping two statements is not formatting"

fixture dropped-import <<'KT'
import a.One
import b.Two

fun f() = One()
KT
printf 'import a.One\n\nfun f() = One()\n' > File.kt
expect fail "dropping an import is not formatting"

fixture changed-literal <<'KT'
var drained = false
KT
printf 'var drained = true\n' > File.kt
expect fail "changing a literal is not formatting"

fixture deleted-comment <<'KT'
// load-bearing note
fun f() {}
KT
printf 'fun f() {}\n' > File.kt
expect fail "deleting a comment is not formatting"

fixture reworded-comment <<'KT'
// the revision can disappear
fun f() {}
KT
printf '// the revision cannot disappear\nfun f() {}\n' > File.kt
expect fail "rewording a comment is not formatting"

fixture added-statement <<'KT'
fun f() {
    g()
}
KT
printf 'fun f() {\n    g()\n    h()\n}\n' > File.kt
expect fail "adding a statement is not formatting"

# --- root .kts is in scope ------------------------------------------------------------------
R="$WORK/kts"; mkdir -p "$R"; cd "$R" || exit 1
git init -q -b main .
git config user.email t@example.invalid; git config user.name t; git config commit.gpgsign false
printf 'plugins { kotlin("jvm") }\n' > build.gradle.kts
git add -A && git commit -qm base
printf 'plugins { kotlin("jvm") }\n// probe\n' > build.gradle.kts
expect fail "a root .kts edit is checked, not skipped"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
