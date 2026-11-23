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

# Required checks whose queue build is reported by a different workflow, so that no pull
# request ever sees a second check with the same name. Danger is one: the pull request path
# calls a reusable workflow, and two checks sharing a name would let a skipped copy stand in
# for a failed run. The pairs are checked below, both ways.
SPLIT_QUEUE_CHECKS="run-danger.yml:danger-merge-queue.yml"

# A job that calls a reusable workflow reports `<calling job> / <reusable job>`. The
# reusable half is not in this repository, so it is written out here and kept in step with
# the pin in run-danger.yml (`@v1`). It was read from that workflow's own `jobs:` block and
# from the pull request's checks, which agree. If the pin moves and the job id changes, this
# constant is what has to move with it — the control cannot see the other repository.
REUSABLE_JOB_ID="retry-buildkite-job"

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

# A required check may be reported by a second, queue-only workflow so that a pull request
# never sees two checks carrying one name. SPLIT_QUEUE_CHECKS lists those pairs as
# `<pull request workflow> <queue-only workflow>`.
split_queue_partner() { # workflow name -> the queue-only partner that reports its check
  python3 - "$1" "$SPLIT_QUEUE_CHECKS" <<'PYTHON'
import sys

target, pairs = sys.argv[1], sys.argv[2].split()
for pair in pairs:
    a, b = pair.split(":")
    if a == target:
        print(b); sys.exit(0)
    if b == target:
        print(a); sys.exit(0)
sys.exit(1)
PYTHON
}

queue_partner_is_merge_group_only() { # partner file -> it triggers on merge_group and nothing else
  python3 "$DIR/merge-queue-helper.py" merge-group-only "$1"
}




echo "== every workflow that can produce a required check listens to merge_group"
for f in "$WF"/*.yml; do
  base="$(basename "$f")"
  if [ "$base" = "$RELEASE_WORKFLOW" ]; then continue; fi
  case " $NOT_REQUIRED " in *" $base "*) continue ;; esac
  partner="$(split_queue_partner "$base")"
  # The two halves are one rule: the check is reported by whichever of the pair is the
  # queue-only workflow, so the pair is tested once, from the workflow that exists only
  # for a queue build.
  if [ -n "$partner" ]; then
    case "$(python3 "$DIR/merge-queue-helper.py" merge-group-only "$f" && echo queue-only || echo not)" in
      queue-only)
        check "$base reports its check on a queue build and on nothing else" "yes" \
          "$(python3 "$DIR/merge-queue-helper.py" merge-group-only "$WF/$partner" && echo no || echo yes)"
        ;;
    esac
    continue
  fi
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
  # A workflow whose queue build its partner reports has no queue steps of its own.
  if partner="$(split_queue_partner "$base")"; then
    check "$base runs a step on a queue build" "reported by $partner" "reported by $partner"
    continue
  fi
  check "$base runs a step on a queue build" "yes" \
    "$(unconditional_step "$f" && echo yes || echo no)"
done

echo "== a check reports the same name on a queue build as on a pull request"
# A required check is matched by its name. The two halves of a split pair live in different
# files, so the names are compared across them: the calling job's key from the pull-request
# file, joined with the pinned reusable job id, against the name the queue-only file reports.
for pair in $SPLIT_QUEUE_CHECKS; do
  pr_file="${pair%%:*}"
  queue_file="${pair##*:}"
  caller="$(python3 "$DIR/merge-queue-helper.py" calling-job-key "$WF/$pr_file")"
  expected="$caller / $REUSABLE_JOB_ID"
  actual="$(python3 "$DIR/merge-queue-helper.py" job-check-name "$WF/$queue_file")"
  check "$queue_file reports the name $pr_file's call produces" "$expected" "$actual"
done

echo "== the job reporting a required name runs for a queue build"
# A job-level condition that cannot hold for a merge group leaves the check unreported, and
# the trigger above it still looks right. So the job behind each required name is read and
# its own condition checked, not just the workflow's triggers.
for f in "$WF"/*.yml; do
  base="$(basename "$f")"
  if [ "$base" = "$RELEASE_WORKFLOW" ]; then continue; fi
  case " $NOT_REQUIRED " in *" $base "*) continue ;; esac
  # The pull-request half of a split pair reports nothing in the queue by design, so only
  # the queue-only half is read here. Which half is which is decided from the file's own
  # triggers, not from the pairing, so a queue job gated to pull requests is still caught.
  if partner="$(split_queue_partner "$base")"; then
    if ! python3 "$DIR/merge-queue-helper.py" merge-group-only "$f"; then continue; fi
  fi
  excluded="$(python3 "$DIR/merge-queue-helper.py" queue-reporting-jobs-excluded "$f")"
  check "$base has no queue-reporting job ruled out by its own condition" "" "$excluded"
done

echo "== a check is reported once on each path, never twice on one"
# Two checks with one name mean the rule can be satisfied by the copy that did nothing, so
# the pair is split: the pull-request workflow reports the name once and never runs on a
# queue build, and the queue-only workflow reports it once and never runs on a pull request.
for pair in $SPLIT_QUEUE_CHECKS; do
  pr_file="${pair%%:*}"
  queue_file="${pair##*:}"
  check "$queue_file triggers on merge_group only" "yes" \
    "$(python3 "$DIR/merge-queue-helper.py" merge-group-only "$WF/$queue_file" && echo yes || echo no)"
  check "$pr_file does not trigger on merge_group" "no" \
    "$(listens_to_queue "$WF/$pr_file" && echo yes || echo no)"
done

echo "== the release workflow never runs for a queue build"
check "release workflow is excluded from merge_group" "no" \
  "$(listens_to_queue "$WF/$RELEASE_WORKFLOW" && echo yes || echo no)"
check "release workflow still fires only on push" "push workflow_dispatch " \
  "$(triggers_of "$WF/$RELEASE_WORKFLOW")"

echo
echo "passed: $PASS  failed: $FAIL"
[ "$FAIL" -eq 0 ]
