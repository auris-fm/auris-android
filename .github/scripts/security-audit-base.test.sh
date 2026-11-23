#!/usr/bin/env bash
# Controls for the base-commit choice the security audit runs on.
#
# The defect these exist for: the step required a second parent, which a merge group's LINEAR
# commit never has, so every queue build failed before scanning anything -- and the check is
# required, so no pull request could land. Each case builds a real throwaway repository of the
# shape the event produces and runs the real script, with a stand-in audit that records the base
# it was handed. Nothing here needs the scanners.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$DIR/.github/scripts/security-audit-base.sh"
PASS=0; FAIL=0
ok()  { echo "ok: $1"; PASS=$((PASS + 1)); }
bad() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }
check_zero()    { if [ "$2" = 0 ]; then ok "$1"; else bad "$1 (exit $2)"; fi; }
check_nonzero() { if [ "$2" != 0 ]; then ok "$1"; else bad "$1 (unexpectedly succeeded)"; fi; }

[ -x "$SCRIPT" ] || { echo "FAIL: $SCRIPT missing or not executable"; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# A stand-in for the audit itself: records the base it was given, so the assertion is on what the
# script DID, not on what it printed. Installed beside a copy of the script under test.
install_probe() { # install_probe <sandbox>
  local s="$1"
  mkdir -p "$s/.github/scripts"
  cp "$SCRIPT" "$s/.github/scripts/security-audit-base.sh"
  cat > "$s/.github/scripts/security-audit.sh" <<'PROBE'
#!/usr/bin/env bash
printf '%s\n' "$2" > "$AUDIT_BASE_FILE"
printf '%s\n' "$1" > "$AUDIT_MODE_FILE"
exit 0
PROBE
  chmod +x "$s/.github/scripts/security-audit.sh"
}

# A repository with a base commit, a branch commit, and (optionally) a merge of the two.
make_repo() { # make_repo <dir> <shape: linear|merge>
  local r="$1" shape="$2"
  mkdir -p "$r"
  (
    cd "$r"
    git init -q -b main
    git config user.email t@t; git config user.name t; git config commit.gpgsign false
    echo base > f; git add f; git commit -qm base
    BASE_SHA="$(git rev-parse HEAD)"
    echo "$BASE_SHA" > "$WORK/base-sha"
    if [ "$shape" = merge ]; then
      git checkout -q -b topic
      echo topic > g; git add g; git commit -qm topic
      git checkout -q main
      echo main2 > h; git add h; git commit -qm main2
      git merge -q --no-ff topic -m "merge result"
    else
      # A queue commit: the queued change applied on top of the base tip, one parent.
      echo queued > g; git add g; git commit -qm "queued change"
    fi
  )
}

# 1. THE QUEUE'S SHAPE: a linear commit with the base named explicitly. This is the case that
#    failed on every queue build.
S="$WORK/sandbox-linear"; install_probe "$S"
R="$WORK/repo-linear"; make_repo "$R" linear
BASE="$(cat "$WORK/base-sha")"
( cd "$R" && AUDIT_BASE_FILE="$WORK/linear-base" AUDIT_MODE_FILE="$WORK/linear-mode" \
    MERGE_GROUP_BASE="$BASE" bash "$S/.github/scripts/security-audit-base.sh" ) > "$WORK/linear.out" 2>&1
check_zero "a linear queue commit audits without demanding a second parent" $?
if [ "$(cat "$WORK/linear-base" 2>/dev/null)" = "$BASE" ]; then
  ok "the audit diffs against the named merge-group base"
else
  bad "wrong base handed to the audit: $(cat "$WORK/linear-base" 2>/dev/null)"
fi
[ "$(cat "$WORK/linear-mode" 2>/dev/null)" = pr ] && ok "it runs in pr mode, not a full scan" || bad "wrong mode"

# 2. NOT HEAD^1. On a queue commit with several queued commits, HEAD^1 is an earlier queued
#    commit, so diffing against it would audit only the last one. Two queued commits prove it.
R2="$WORK/repo-linear2"
mkdir -p "$R2"; ( cd "$R2"
  git init -q -b main; git config user.email t@t; git config user.name t; git config commit.gpgsign false
  echo base > f; git add f; git commit -qm base; git rev-parse HEAD > "$WORK/base2-sha"
  echo one > g; git add g; git commit -qm queued1
  echo two > h; git add h; git commit -qm queued2 )
BASE2="$(cat "$WORK/base2-sha")"
( cd "$R2" && AUDIT_BASE_FILE="$WORK/linear2-base" AUDIT_MODE_FILE="$WORK/linear2-mode" \
    MERGE_GROUP_BASE="$BASE2" bash "$S/.github/scripts/security-audit-base.sh" ) > "$WORK/linear2.out" 2>&1
check_zero "two queued commits still audit as one range" $?
if [ "$(cat "$WORK/linear2-base" 2>/dev/null)" = "$BASE2" ]; then
  ok "the base is the queue base, not the previous queued commit"
else
  bad "the range covered only the last queued commit"
fi

# 3. THE PULL-REQUEST PATH IS UNCHANGED: a merge result, no MERGE_GROUP_BASE, base is HEAD^1.
S3="$WORK/sandbox-pr"; install_probe "$S3"
R3="$WORK/repo-merge"; make_repo "$R3" merge
PARENT="$(cd "$R3" && git rev-parse 'HEAD^1')"
( cd "$R3" && AUDIT_BASE_FILE="$WORK/pr-base" AUDIT_MODE_FILE="$WORK/pr-mode" \
    bash "$S3/.github/scripts/security-audit-base.sh" ) > "$WORK/pr.out" 2>&1
check_zero "a pull request's merge result still audits" $?
# The script hands the audit the EXPRESSION `HEAD^1`, which the audit resolves in the same
# checkout; assert on what that expression names, not on the literal string.
PR_RECORDED="$(cat "$WORK/pr-base" 2>/dev/null)"
PR_RESOLVED="$(cd "$R3" && git rev-parse --verify --quiet "$PR_RECORDED" 2>/dev/null)"
[ -n "$PR_RESOLVED" ] && [ "$PR_RESOLVED" = "$PARENT" ] \
  && ok "the pull-request base still resolves to HEAD^1" \
  || bad "pull-request base changed: '$PR_RECORDED' -> '$PR_RESOLVED', expected '$PARENT'"

# 4. A NON-MERGE COMMIT ON THE PULL-REQUEST PATH STILL FAILS CLOSED: without the second parent
#    the range would silently narrow to the last commit.
( cd "$R" && AUDIT_BASE_FILE="$WORK/nomerge-base" AUDIT_MODE_FILE="$WORK/nomerge-mode" \
    bash "$S/.github/scripts/security-audit-base.sh" ) > "$WORK/nomerge.out" 2>&1
check_nonzero "a linear commit with no named base fails closed" $?
grep -q "merge commit" "$WORK/nomerge.out" && ok "the failure names the checkout shape" || bad "unclear failure"
[ ! -e "$WORK/nomerge-base" ] && ok "the audit never ran on the wrong range" || bad "the audit ran despite the bad shape"

# 5. A BAD MERGE-GROUP BASE FAILS CLOSED, and the audit does not run. An empty or unknown base
#    must not become "scan nothing, report clean".
for bad_base in "0000000000000000000000000000000000000000" "not-a-sha"; do
  rm -f "$WORK/badbase-base"
  ( cd "$R" && AUDIT_BASE_FILE="$WORK/badbase-base" AUDIT_MODE_FILE="$WORK/badbase-mode" \
      MERGE_GROUP_BASE="$bad_base" bash "$S/.github/scripts/security-audit-base.sh" ) > "$WORK/badbase.out" 2>&1
  check_nonzero "an unresolvable merge-group base fails closed ($bad_base)" $?
  [ ! -e "$WORK/badbase-base" ] && ok "no audit ran on an unresolvable base" || bad "the audit ran with a bad base"
done

# 6. A BASE THAT IS NOT AN ANCESTOR fails closed too: it resolves as a commit, so only the
#    ancestry check catches it, and a wrong range would read as clean.
# A base that RESOLVES but is not an ancestor is the harder case: an unknown sha is caught by the
# first check, so only the ancestry check can catch this one.
R7="$WORK/repo-sidebranch"; mkdir -p "$R7"; ( cd "$R7"
  git init -q -b main; git config user.email t@t; git config user.name t; git config commit.gpgsign false
  echo base > f; git add f; git commit -qm base
  git checkout -q -b side; echo side > s; git add s; git commit -qm side
  git rev-parse HEAD > "$WORK/side-sha"
  git checkout -q main; echo queued > g; git add g; git commit -qm queued )
SIDE="$(cat "$WORK/side-sha")"
rm -f "$WORK/side-base"
( cd "$R7" && AUDIT_BASE_FILE="$WORK/side-base" AUDIT_MODE_FILE="$WORK/side-mode" \
    MERGE_GROUP_BASE="$SIDE" bash "$S/.github/scripts/security-audit-base.sh" ) > "$WORK/side.out" 2>&1
check_nonzero "a resolvable base off another branch fails closed" $?
grep -q "not an ancestor" "$WORK/side.out" && ok "the failure says the base is not an ancestor" || bad "unclear ancestry failure"
[ ! -e "$WORK/side-base" ] && ok "no audit ran on a non-ancestor base" || bad "the audit ran on a non-ancestor base"

# 7. THE WORKFLOW ACTUALLY CALLS THIS SCRIPT on both events, and passes the base through. A
#    correct script nothing invokes is no fix.
ruby -ryaml - "$DIR/.github/workflows/security-audit.yml" <<'RUBY'
  wf = YAML.load_file(ARGV[0])
  on = wf["on"] || wf[true]
  abort "workflow does not run on merge_group" unless on.key?("merge_group")
  abort "workflow does not run on pull_request" unless on.key?("pull_request")
  job = wf["jobs"]["pull-request"]
  step = job["steps"].find { |s| s["run"].to_s.include?("security-audit-base.sh") }
  abort "no step runs the base-choice script" unless step
  abort "the step does not pass merge_group.base_sha" unless step["env"]["MERGE_GROUP_BASE"].to_s.include?("merge_group.base_sha")
  body = job["steps"].map { |s| s["run"].to_s }.join("\n")
  abort "the workflow still demands a second parent inline" if body.include?("HEAD^2")
  puts "both events reach the script, with the queue base passed through"
RUBY
check_zero "the workflow calls the script on both events" $?

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
