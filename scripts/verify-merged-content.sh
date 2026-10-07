#!/usr/bin/env bash
# Verify that what landed in a base branch is what was reviewed — file by file.
#
# Why this exists, and why it is not a diff between two of your own artifacts:
# a branch-to-branch (or rebuilt-tree-to-old-tree) comparison cannot detect content
# that BOTH dropped. That is not hypothetical: a tree-assembled rebuild silently lost
# 151 lines of documentation while its sibling branch had lost the same lines, so the
# empty diff between them was evidence of agreement, not of completeness.
#
# The comparison that catches it is against the SOURCE OF TRUTH:
#   base:<file>  vs  reviewed-sha:<file>, for every file the change touches.
#
# Usage:
#   scripts/verify-merged-content.sh <reviewed-sha> [base] [-- <path>...]
#   scripts/verify-merged-content.sh 3ff83ad9b origin/main
#
# Exit 0 when every file matches; non-zero listing the files that do not.
set -uo pipefail

reviewed="${1:?usage: $0 <reviewed-sha> [base] [-- paths...]}"
base="origin/main"
[ "$#" -ge 2 ] && base="$2"
# drop the two positionals (and an optional --) so "$@" is paths, if any
shift 2 2>/dev/null || shift 1 2>/dev/null || true
[ "${1:-}" = "--" ] && shift

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  # default: every file the reviewed commit's change touches, relative to its parent
  mapfile -t files < <(git diff --name-only "${reviewed}~1" "$reviewed")
fi

fail=0
for f in "${files[@]}"; do
  if ! git cat-file -e "${base}:${f}" 2>/dev/null; then
    printf 'ABSENT in %s: %s\n' "$base" "$f"
    fail=1
    continue
  fi
  if git diff --quiet "${base}:${f}" "${reviewed}:${f}" 2>/dev/null; then
    printf 'ok       %s\n' "$f"
  else
    printf 'DIFFERS  %s\n' "$f"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  printf '\nAt least one file in %s does not match %s.\n' "$base" "$reviewed"
  printf 'Compare the content, not the counts: `git diff %s:<file> %s:<file>`.\n' "$base" "$reviewed"
  exit 1
fi
exit 0
