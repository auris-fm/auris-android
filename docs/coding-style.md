# Coding Style

Our code style guidelines are based on the [Android Code Style Guidelines for Contributors](https://source.android.com/source/code-style.html). We only changed a few rules:

* Line length is 120 characters
* FIXME must not be committed in the repository—use TODO instead.

## Spotless

All code needs to comply with the Spotless checks before being merged. You can check this locally using `./gradlew spotlessCheck`, or auto-format your code with `./gradlew spotlessApply`.

The project has a Git hook you can install to run this check on pre-commit: `./gradlew installGitHooks`. It points git at `scripts/git-hooks` via `core.hooksPath`, so the hook that runs is the tracked file itself — in the main checkout and in every worktree — and it follows the repository instead of going stale. It checks only the Kotlin files in the commit, and it never reformats them: if they fail, it aborts and prints the command to fix them.

If you installed hooks before this, your `.git/hooks/pre-commit` is a copy that will keep running whatever it contained then. Run `./gradlew installGitHooks` once and delete that file.
