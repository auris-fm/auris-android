#!/bin/sh


##################
# Spotless
##################

STAGED_SPOTLESS_FILES="$(git diff --cached --name-only --diff-filter=ACMR -- '*.kt' '*.kts')"

if [ -n "$STAGED_SPOTLESS_FILES" ]; then
  SPOTLESS_FILE_LIST="$(mktemp "${TMPDIR:-/tmp}/pocketcasts-spotless.XXXXXX")"
  trap 'if [ "${KEEP_FILE_LIST:-0}" != 1 ]; then rm -f "$SPOTLESS_FILE_LIST"; fi' EXIT HUP INT TERM
  printf '%s\n' "$STAGED_SPOTLESS_FILES" > "$SPOTLESS_FILE_LIST"

  echo "Running Spotless on staged Kotlin files..."
  ./gradlew spotlessCheck "-PspotlessPreCommitFiles=$SPOTLESS_FILE_LIST"
  RESULT=$?
else
  echo "No staged Kotlin files. Skipping Spotless..."
  RESULT=0
fi

if [ $RESULT -ne 0 ]; then
  echo ""
  echo "Spotless found format violations, so aborting the commit."
  # Nothing is rewritten here on purpose. A hook that runs spotlessApply edits the working tree
  # behind the author's back: the reformatted files are not staged, so the commit still fails,
  # and the next `git add -A` sweeps in changes to files this commit never touched. The author
  # runs the fix themselves, when they can see what it changed.
  # Point at the scoped invocation, not a bare `./gradlew spotlessApply`: that reformats every
  # file in the repository, so the author would get back the unrelated churn this hook exists to
  # avoid. The list is left on disk for that command, since the trap that removes it runs after
  # this exit, so that is handed over below.
  echo "To fix: ./gradlew spotlessApply \"-PspotlessPreCommitFiles=$SPOTLESS_FILE_LIST\""
  echo "Then review the diff and stage it. A bare './gradlew spotlessApply' reformats the whole"
  echo "repository, including files this commit does not touch."
  # Hand the list over rather than deleting it: the command above names this path. Git ignores
  # everything under $TMPDIR, so this leaves no untracked file in the repository.
  KEEP_FILE_LIST=1
  exit $RESULT
fi

##################
# gitleaks
##################

# Exit early if gitleaks is not installed
if ! [ -x "$(command -v gitleaks)" ]; then

  # Check if user has access to secrets
  if [ -e "$HOME/.mobile-secrets/" ]; then
    RED='\033[0;31m'
    NO_COLOR='\033[0m'
    printf "\n${RED}ERROR: You have access to PocketCasts secrets, so you must install gitleaks.${NO_COLOR}\n"
    exit 1
  else
    echo "Gitleaks not installed. Skipping gitleaks check..."
    exit 0
  fi
fi

gitleaks protect -v --staged
RESULT=$?

if [ $RESULT -ne 0 ]; then
  echo ""
  echo "Warning: gitleaks has detected sensitive information in your changes. Aborting commit."
  exit $RESULT
fi

exit $RESULT
