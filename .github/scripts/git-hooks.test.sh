#!/bin/bash
# Controls for the pre-commit hook.
#
# The hook runs on a developer's machine, in their working tree, at the moment they commit. Two
# properties matter there and neither is visible by reading the script:
#
#   1. It must never MODIFY the working tree. A hook that reformats files pulls changes the
#      author did not make into their commit -- including files the commit never touched.
#   2. It must only look at what is STAGED. Checking the whole repository makes every commit
#      pay for, and fail on, violations that were already on main.
#
# Each case drives the REAL hook in a throwaway repository with a fake `./gradlew` that records
# what it was asked to do and can mutate files the way spotlessApply would. No Gradle, no
# network: the question is what the hook asks for and what it does with the answer.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
HOOK="$DIR/scripts/git-hooks/pre-commit"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL: $1"; }
check_zero()    { if [ "$2" = 0 ]; then ok "$1"; else bad "$1 (exit $2)"; fi; }
check_nonzero() { if [ "$2" != 0 ]; then ok "$1"; else bad "$1 (unexpectedly succeeded)"; fi; }

[ -f "$HOOK" ] || { echo "FAIL: $HOOK missing"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "FAIL: git required"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# new_repo <name>: a repository with the hook installed and a fake gradlew that logs its args.
# The fake writes one line per invocation to $CALLS, and `spotlessApply` rewrites every .kt file
# it can find -- which is what the real task does to files outside the staged set.
new_repo() {
  R="$WORK/$1"
  mkdir -p "$R"
  cd "$R" || exit 1
  git init -q -b main .
  git config user.email t@example.invalid
  git config user.name t
  git config commit.gpgsign false
  CALLS="$R/calls"
  export CALLS
  : > "$CALLS"
  cat > ./gradlew <<'GRADLE'
#!/bin/sh
echo "$*" >> "$CALLS"
for arg in "$@"; do
  case "$arg" in
    spotlessApply)
      # What the real task does: rewrite Kotlin files in the tree, staged or not.
      find . -name '*.kt' -not -path './.git/*' -exec sh -c 'printf "// formatted\n" >> "$1"' _ {} \;
      ;;
  esac
done
# Copy the file list aside NOW: the hook deletes it on exit, so a control that reads the path
# afterwards would always find it gone and could never tell cleanup from never-created.
for arg in "$@"; do
  case "$arg" in
    -PspotlessPreCommitFiles=*)
      p="${arg#-PspotlessPreCommitFiles=}"
      if [ -f "$p" ]; then cp "$p" "$CALLS.list"; else : > "$CALLS.missing"; fi
      ;;
  esac
done
case "$1" in
  spotlessCheck) exit "${FAKE_CHECK_EXIT:-0}" ;;
esac
exit 0
GRADLE
  chmod +x ./gradlew
  cp "$HOOK" .git/hooks/pre-commit
  chmod +x .git/hooks/pre-commit
  printf 'val untouched = 1\n' > Untouched.kt
  git add -A && git commit -qm base
  : > "$CALLS"
}

# run_hook: invoke the installed hook the way git does, from the repository root.
run_hook() {
  FAKE_CHECK_EXIT="${FAKE_CHECK_EXIT:-0}" sh .git/hooks/pre-commit > "$WORK/out" 2>&1
  local rc=$?
  capture_list
  return $rc
}

# The list the hook handed to Gradle. Read from a COPY taken during the run: the hook removes
# its temporary file on exit, so reading the path afterwards would always find it gone and the
# cleanup case would pass for the wrong reason.
staged_list() { printf '%s\n' "${LIST_PATH:-}"; }
capture_list() {
  LIST_PATH="$(sed -n 's/.*-PspotlessPreCommitFiles=//p' "$CALLS" | head -1)"
  LIST_CONTENT=""
  LIST_EXISTS_AT_RUN=no
  if [ -f "$CALLS.list" ]; then
    LIST_EXISTS_AT_RUN=yes
    LIST_CONTENT="$(cat "$CALLS.list")"
  fi
}

# 1. A FAILING check must not rewrite anything. This is the property the old hook broke: it ran
#    spotlessApply on failure, which reformatted files the commit never touched.
new_repo no-mutation
printf 'val staged = 1\n' > Staged.kt
git add Staged.kt
before_staged=$(cat Staged.kt); before_other=$(cat Untouched.kt)
FAKE_CHECK_EXIT=1 run_hook
rc=$?
check_nonzero "a failing format check aborts the commit" "$rc"
[ "$(cat Staged.kt)" = "$before_staged" ] && ok "the staged file is left exactly as the author wrote it" \
  || bad "the hook rewrote the staged file"
[ "$(cat Untouched.kt)" = "$before_other" ] && ok "a file the commit never touched is left alone" \
  || bad "the hook reformatted a file outside the commit"
grep -qx "spotlessApply.*" "$CALLS" && bad "the hook ran spotlessApply" || ok "the hook never runs spotlessApply"

# 2. Only the STAGED files are checked, and the list is what git says is staged -- not a guess
#    from the working tree, which would include unstaged edits and untracked files.
new_repo scoped
printf 'val staged = 1\n' > Staged.kt
printf 'val dirty = 1\n' > Unstaged.kt
git add Staged.kt Unstaged.kt && git commit -qm both
printf 'val staged = 2\n' > Staged.kt
printf 'val dirty = 2\n' > Unstaged.kt
printf 'val untracked = 1\n' > Untracked.kt
git add Staged.kt
run_hook
check_zero "a passing check commits" $?
[ "$LIST_EXISTS_AT_RUN" = yes ] && ok "the check is given a file list" || bad "the check ran over the whole repository"
printf '%s\n' "$LIST_CONTENT" | grep -qx "Staged.kt" && ok "the staged file is in the list" || bad "the staged file is missing from the list"
printf '%s\n' "$LIST_CONTENT" | grep -qx "Unstaged.kt" && bad "an unstaged edit was included" || ok "an unstaged edit is not checked"
printf '%s\n' "$LIST_CONTENT" | grep -qx "Untracked.kt" && bad "an untracked file was included" || ok "an untracked file is not checked"

# 3. No Kotlin staged means no Gradle at all: a shell-only or YAML-only commit must not pay for
#    a Gradle run, and must not fail on Kotlin violations already on main.
new_repo no-kotlin
printf 'notes\n' > notes.txt
git add notes.txt
run_hook
check_zero "a commit with no Kotlin succeeds" $?
[ -s "$CALLS" ] && bad "Gradle ran for a commit with no Kotlin" || ok "no Gradle run for a commit with no Kotlin"

# 4. The temporary file list is cleaned up, including when the check fails. A failing run keeps
#    it on purpose -- the fix command the hook prints names this path -- and a passing run
#    removes it, so the two cases are checked separately.
new_repo cleanup
printf 'val staged = 1\n' > Staged.kt
git add Staged.kt
run_hook
[ "$LIST_EXISTS_AT_RUN" = yes ] && ok "the file list existed while Gradle read it" \
  || bad "the hook passed a path that did not exist"
[ -n "$(staged_list)" ] && [ ! -e "$(staged_list)" ] && ok "the file list is removed when the check passes" \
  || bad "the file list was left behind: $(staged_list)"

# 5. A failing run hands the list to the author instead of deleting it, because the command the
#    hook prints tells them to run spotlessApply against that exact path.
new_repo cleanup_kept
printf 'val staged = 1\n' > Staged.kt
git add Staged.kt
FAKE_CHECK_EXIT=1 run_hook
[ -e "$(staged_list)" ] && ok "a failing run keeps the file list for the fix command" \
  || bad "the failing run deleted the file list the fix command names"
[ "$(cat "$(staged_list)" 2>/dev/null)" = "Staged.kt" ] && ok "the kept list still names the staged file" \
  || bad "the kept list is wrong: $(staged_list) -> $(cat "$(staged_list)" 2>/dev/null)"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
