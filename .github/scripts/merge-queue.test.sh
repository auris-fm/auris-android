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

echo "== the release workflow never runs for a queue build"
check "release workflow is excluded from merge_group" "no" \
  "$(listens_to_queue "$WF/$RELEASE_WORKFLOW" && echo yes || echo no)"
check "release workflow still fires only on push" "push workflow_dispatch " \
  "$(triggers_of "$WF/$RELEASE_WORKFLOW")"

echo
echo "passed: $PASS  failed: $FAIL"
[ "$FAIL" -eq 0 ]
