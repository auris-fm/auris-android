#!/bin/bash
# Controls for the merge-queue trigger rule.
#
# A GitHub merge queue builds a temporary combined commit and reports status checks
# against it. A workflow that only listens to `pull_request` never runs for that
# commit, so a required check would stay pending forever and the queue would stall.
# Conversely the release workflow must never be triggered by a queue build: it fires
# on `push` to main only, and a merge-queue commit is not a merge to main.
#
# The rule has two halves, and both can be broken by an ordinary edit:
#   1. every workflow that can produce a required check listens to `merge_group`;
#   2. the release workflow does not, and still fires only on `push` to main.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
WF="$(cd "$DIR/../workflows" && pwd)"
PASS=0; FAIL=0
note() { # text -> a decision this control cannot make, said out loud
  echo "NOTE: $1"
}
check() { # name expected actual
  if [ "$2" = "$3" ]; then
    PASS=$((PASS+1)); echo "ok: $1"
  else
    FAIL=$((FAIL+1)); echo "FAIL: $1 (got $3, want $2)"
  fi
}

# The release workflow, named once so the exclusion and the assertion cannot drift apart.
RELEASE_WORKFLOW="auris-release.yml"

# Workflows that never produce a required check and so have no business in the queue:
# comment-driven reviews, scheduled scans, manual sweeps, and side effects such as
# dependency submission and Slack notifications.
NOT_REQUIRED="claude.yml claude-code-review.yml monthly-code-review.yml upstream-sync.yml submit-gradle-dependencies.yml high-prio-issue-notify-slack.yml"

# A workflow listens to the queue when `merge_group` appears under its `on:` key, either
# as a block key or inside a flow list. Checking the trigger block rather than the whole
# file matters: the string also appears in comments explaining this very rule.
# A workflow listens to the queue when `merge_group` is one of the workflow's triggers.
# Both YAML shapes are handled: the flow form `on: [push, pull_request]` and the block
# form that lists triggers beneath `on:`. The trigger block is read rather than the whole
# file, because the string legitimately appears in the comments that explain this rule.
trigger_block() { # file -> the workflow's `on:` body
  awk '
    /^on:[[:space:]]*\[/ { print; exit }
    /^on:/ { in_on = 1; next }
    in_on && /^[^[:space:]#]/ { in_on = 0 }
    in_on { print }
  ' "$1"
}

listens_to_queue() { # file -> 0 when merge_group is one of its triggers
  trigger_block "$1" | grep -qE '^on:[[:space:]]*\[[^]]*merge_group|^[[:space:]]+merge_group:'
}

# The triggers of a workflow, one per line, for assertions about shape.
triggers_of() { # file -> sorted trigger names
  trigger_block "$1" | awk '
    /^on:[[:space:]]*\[/ {
      line = $0; gsub(/^on:[[:space:]]*\[|\][[:space:]]*$/, "", line)
      n = split(line, parts, ",")
      for (i = 1; i <= n; i++) { gsub(/[[:space:]]/, "", parts[i]); print parts[i] }
      next
    }
    /^[[:space:]]+[a-z_]+:[[:space:]]*$/ { match($0, /[a-z_]+:/); print substr($0, RSTART, RLENGTH-1) }
  ' | sort -u | tr '\n' ' '
}

# The triggers of a workflow, as one sorted line, for assertions about shape.
triggers_of() { # file -> sorted trigger names
  awk '
    /^on:/ {
      in_on = 1
      if ($0 ~ /^on:[[:space:]]*\[/) {
        line = $0
        gsub(/^on:[[:space:]]*\[|\][[:space:]]*$/, "", line)
        n = split(line, parts, ",")
        for (i = 1; i <= n; i++) { gsub(/[[:space:]]/, "", parts[i]); print parts[i] }
        in_on = 0
      }
      next
    }
    in_on && /^[^[:space:]#]/ { in_on = 0 }
    in_on && /^[[:space:]]+[a-z_]+:[[:space:]]*$/ { match($0, /[a-z_]+:/); print substr($0, RSTART, RLENGTH-1) }
  ' "$1" | sort -u | tr '\n' ' '
}

echo "== every workflow that can produce a required check listens to merge_group"
for f in "$WF"/*.yml; do
  base="$(basename "$f")"
  if [ "$base" = "$RELEASE_WORKFLOW" ]; then continue; fi
  case " $NOT_REQUIRED " in *" $base "*) continue ;; esac
  check "$base listens to merge_group" "yes" "$(listens_to_queue "$f" && echo yes || echo no)"
done

echo "== a queue trigger names no activity type: a merge group reports only checks_requested"
for f in "$WF"/*.yml; do
  base="$(basename "$f")"
  # A `merge_group:` block may not carry any other type. `types:` listing events that a
  # merge group never reports is worse than no trigger at all: the check looks wired up
  # and never arrives.
  types="$(trigger_block "$f" | awk '/^[[:space:]]+merge_group:[[:space:]]*$/ { g = 1; next } g && /^[[:space:]]+types:/ { print; exit }')"
  check "$base names no activity type on merge_group" "" "$types"
done

echo "== a required check is never filtered by path in the queue"
for f in "$WF"/*.yml; do
  base="$(basename "$f")"
  if [ "$base" = "$RELEASE_WORKFLOW" ]; then continue; fi
  case " $NOT_REQUIRED " in *" $base "*) continue ;; esac
  # A path filter on a required check means a queued change touching none of those
  # paths gets no run and no status, and the queue waits forever. The filter belongs
  # inside the job.
  filter="$(trigger_block "$f" | awk '/^[[:space:]]+merge_group:[[:space:]]*$/ { g = 1; next } g && /^[[:space:]]+[a-z_-]+:/ && !/^[[:space:]]+(paths|paths-ignore):/ { g = 0 } g && /^[[:space:]]+(paths|paths-ignore):/ { print "path filter"; exit }')"
  check "$base is not path-filtered in the queue" "" "$filter"
done

echo "== a required check has a step that runs on a queue build"
# A step with a false condition does not run. A job where every step is conditional
# reports success with nothing run behind it, which is how a required check becomes
# decorative. So each of these workflows must have one step whose `run:` or `uses:` line
# carries no `if:` of its own.
unconditional_step() { # file -> 0 when some step cannot be skipped
  python3 - "$1" <<'PYTHON'
import sys

lines = open(sys.argv[1]).read().split("\n")
in_steps = False
step = None

def flush():
    # A step is unconditional when neither side of the pair carries a condition.
    if step and not step[1] and step[2]:
        return True
    return False

found = False
for line in lines:
    if line.startswith("    steps:"):
        in_steps = True
        continue
    if in_steps and line[:3] not in ("   ",) and line:
        if flush():
            found = True
        in_steps = False
        step = None
    if not in_steps:
        continue
    stripped = line.lstrip()
    if stripped.startswith("- "):
        if flush():
            found = True
        # The step's first line may itself carry the condition or the action.
        step = [stripped, stripped.startswith("- if:"), False]
        if stripped.startswith(("- run:", "- uses:")):
            step[2] = True
        continue
    if step is None:
        continue
    if stripped.startswith("if:"):
        step[1] = True
    elif stripped.startswith(("run:", "uses:")):
        step[2] = True
if flush():
    found = True
sys.exit(0 if found else 1)
PYTHON
}

for f in "$WF"/*.yml; do
  base="$(basename "$f")"
  if [ "$base" = "$RELEASE_WORKFLOW" ]; then continue; fi
  case " $NOT_REQUIRED " in *" $base "*) continue ;; esac
  check "$base runs a step on a queue build" "yes" \
    "$(unconditional_step "$f" && echo yes || echo no)"
done

echo "== a check reports the same name on a queue build as on a pull request"
# A required check is matched by its name. A workflow that reports one name for a pull
# request and another for a queue build leaves the rule satisfied on one path and waiting
# forever on the other, so the queue path must report exactly what the pull-request path
# reports, with no places to drift.
#
# A job that calls a reusable workflow reports `<calling job> / <reusable job>`. The
# reusable side is remote, so this check refuses to certify a guarded workflow that uses
# one: a person has to compare the two names by hand and say so here. That refusal is the
# point — a guess would certify the mistake it was added to catch.
queue_check_name() { # file -> the name one queue job reports, empty when there is none
  python3 - "$1" <<'PYTHON'
import re, sys

lines = open(sys.argv[1]).read().split("\n")
jobs, current, order = {}, None, []
for line in lines:
    m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
    if m:
        current = m.group(1)
        jobs[current] = []
        order.append(current)
        continue
    if current:
        jobs[current].append(line)

names = []
for name in order:
    text = "\n".join(jobs[name])
    if not re.search(r"^    if:.*merge_group", text, re.M):
        continue
    if re.search(r"^    uses:", text, re.M):
        print("REUSABLE", end="")
        sys.exit(0)
    m = re.search(r'^    name:\s*"?(.+?)"?\s*$', text, re.M)
    names.append(m.group(1) if m else name)
print(",".join(names))
PYTHON
}

pr_check_name() { # file -> the name the pull-request path reports
  python3 - "$1" <<'PYTHON'
import re, sys

lines = open(sys.argv[1]).read().split("\n")
jobs, current, order = {}, None, []
for line in lines:
    m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
    if m:
        current = m.group(1)
        jobs[current] = []
        order.append(current)
        continue
    if current:
        jobs[current].append(line)

for name in order:
    text = "\n".join(jobs[name])
    if re.search(r"^    if:.*merge_group", text, re.M):
        continue
    if re.search(r"^    uses:", text, re.M):
        print("REUSABLE", end="")
        sys.exit(0)
    m = re.search(r'^    name:\s*"?(.+?)"?\s*$', text, re.M)
    print(m.group(1) if m else name, end="")
    sys.exit(0)
sys.exit(1)
PYTHON
}

for f in "$WF"/*.yml; do
  base="$(basename "$f")"
  if [ "$base" = "$RELEASE_WORKFLOW" ]; then continue; fi
  case " $NOT_REQUIRED " in *" $base "*) continue ;; esac
  queue_name="$(queue_check_name "$f")"
  [ -n "$queue_name" ] || continue
  pr_name="$(pr_check_name "$f")"
  if [ "$queue_name" = "REUSABLE" ] || [ "$pr_name" = "REUSABLE" ]; then
    # The half of the check name that lives in another repository cannot be read here,
    # so this says so and asks for the comparison rather than guessing it.
    note "$base: one side calls a reusable workflow, so its check name cannot be compared here."
    note "  the queue job must report exactly what the pull request's checks report;"
    note "  a person compares them, and the name is written out beside the job."
    continue
  fi
  check "$base reports one check name on both paths" "$pr_name" "$queue_name"
done

echo "== the release workflow never runs for a queue build"
check "release workflow is excluded from merge_group" "no" \
  "$(listens_to_queue "$WF/$RELEASE_WORKFLOW" && echo yes || echo no)"
check "release workflow still fires only on push" "push workflow_dispatch " \
  "$(triggers_of "$WF/$RELEASE_WORKFLOW")"

echo
echo "passed: $PASS  failed: $FAIL"
[ "$FAIL" -eq 0 ]
