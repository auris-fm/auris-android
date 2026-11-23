#!/bin/bash -eu

# The release steps run whichever ruby and bundler the job's PATH resolves, and a stock
# macOS ruby is too old for this repo: the decode helper needs base64 as a default gem,
# and fastlane needs the bundler that wrote Gemfile.lock. A wrong interpreter does not
# always fail loudly -- an old bundler can resolve a different gem set and upload with it.
# So assert the pins BEFORE any use, and refuse to continue when they do not match.
#
# Sourced by both release steps from the repository root. Reads the pins from the checkout
# (.ruby-version, Gemfile.lock) rather than repeating literals here, so a deliberate bump
# moves the requirement with it.

auris_require_interpreter() {
  local want_ruby want_bundler have_ruby have_bundler
  want_ruby="$(cat .ruby-version)"
  want_bundler="$(awk '/^BUNDLED WITH/{getline; gsub(/[ \t]/,""); print}' Gemfile.lock)"

  if [ -z "$want_ruby" ] || [ -z "$want_bundler" ]; then
    echo "--- cannot read the interpreter pins (.ruby-version / Gemfile.lock BUNDLED WITH)" >&2
    return 1
  fi

  # `ruby -v` prints "ruby 3.4.9 (…revision…) [platform]"; take the version field only.
  have_ruby="$(ruby -v 2>/dev/null | awk '{print $2}' | sed 's/p[0-9]*$//')" || have_ruby=""
  if [ "$have_ruby" != "$want_ruby" ]; then
    echo "--- ruby $want_ruby required by .ruby-version, but PATH resolves '${have_ruby:-none}' ($(command -v ruby || echo 'not found'))" >&2
    return 1
  fi

  # `bundle -v` prints "Bundler version 4.0.17".
  have_bundler="$(bundle -v 2>/dev/null | awk '{print $NF}')" || have_bundler=""
  if [ "$have_bundler" != "$want_bundler" ]; then
    echo "--- bundler $want_bundler required by Gemfile.lock, but PATH resolves '${have_bundler:-none}' ($(command -v bundle || echo 'not found'))" >&2
    return 1
  fi

  # Ruby raises on non-ASCII output under a non-UTF-8 locale, and the release prints
  # translated text. Override BY VALUE: an inherited UTF-8 locale is a deliberate choice
  # (it may carry a region) and is left alone; anything else is replaced.
  case "${LC_ALL:-${LANG:-}}" in
    *UTF-8|*utf-8|*UTF8|*utf8) ;;
    *) export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 ;;
  esac
  # An inherited LC_ALL that is not UTF-8 would still win over LANG, so clear the mismatch.
  case "${LC_ALL:-}" in
    ""|*UTF-8|*utf-8|*UTF8|*utf8) ;;
    *) export LC_ALL=en_US.UTF-8 ;;
  esac

  echo "--- interpreter ok: ruby $have_ruby, bundler $have_bundler, locale ${LC_ALL:-${LANG:-unset}}"
}
