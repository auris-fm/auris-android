#!/usr/bin/env bash
# Choose the base commit the security audit diffs against, then run it.
#
# Two events reach this step, and they check out different shapes:
#
#   - A pull request checks out the MERGE RESULT, whose first parent is the base branch tip.
#     Diffing against `HEAD^1` covers the whole pull request. The second parent must exist:
#     on any other commit `HEAD^1` is merely the previous commit, and the audit would cover
#     only the last commit of the pull request while reporting success.
#
#   - A merge group checks out a LINEAR commit -- the base tip with the queued changes on
#     top. It has no second parent, so requiring one fails every queue build regardless of
#     content. GitHub names the base for this event as `merge_group.base_sha`.
#
# Both paths fail closed: a base that cannot be resolved stops the run rather than letting a
# scan of the wrong range read as clean.
set -Eeuo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -n "${MERGE_GROUP_BASE:-}" ]; then
  # The queue's own commit. Verify the named base is actually an ancestor, so a wrong or
  # empty value cannot silently narrow the range to nothing.
  git rev-parse --verify --quiet "$MERGE_GROUP_BASE^{commit}" > /dev/null \
    || { echo "::error::Merge group base '$MERGE_GROUP_BASE' is not a commit in this checkout."; exit 2; }
  git merge-base --is-ancestor "$MERGE_GROUP_BASE" HEAD \
    || { echo "::error::Merge group base '$MERGE_GROUP_BASE' is not an ancestor of HEAD."; exit 2; }
  exec "$DIR/security-audit.sh" pr "$MERGE_GROUP_BASE"
fi

# A pull request: the merge result, whose first parent is the base tip.
git rev-parse --verify --quiet 'HEAD^2' > /dev/null \
  || { echo "::error::Expected the pull request's merge commit to be checked out."; exit 2; }
exec "$DIR/security-audit.sh" pr 'HEAD^1'
