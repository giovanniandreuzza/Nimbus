#!/usr/bin/env bash
# Fixtures for check-conventions.sh. Run: bash .github/scripts/check-conventions-test.sh
set -uo pipefail

source "$(dirname "$0")/check-conventions.sh"

failures=0

expect() { # expect <should_pass:0|1> <fn> <value>
  local want="$1" fn="$2" value="$3"
  if "$fn" "$value"; then got=0; else got=1; fi
  if [ "$got" != "$want" ]; then
    echo "FAIL $fn('$value'): wanted $( [ "$want" = 0 ] && echo pass || echo fail )"
    failures=$((failures + 1))
  fi
}

# Branches that must pass
expect 0 is_valid_branch "main"
expect 0 is_valid_branch "develop"
expect 0 is_valid_branch "feature/content-digest"
expect 0 is_valid_branch "bugfix/resume-offset"
expect 0 is_valid_branch "hotfix/416-truncate"
expect 0 is_valid_branch "release/2.3.0"
expect 0 is_valid_branch "chore/release-automation"
expect 0 is_valid_branch "release-please--branches--main"

# Branches that must fail
expect 1 is_valid_branch "worktree-nimbus-2-3-0-tests"
expect 1 is_valid_branch "feature/Content-Digest"
expect 1 is_valid_branch "feature/"
expect 1 is_valid_branch "feature/double--hyphen"
expect 1 is_valid_branch "random-branch"

# Commit subjects that must pass
expect 0 is_valid_subject "feat: content digest, computed in the pass that already writes the bytes"
expect 0 is_valid_subject "fix(storage): stop reporting unclassifiable failures as permission denials"
expect 0 is_valid_subject "chore(sample): give an Enqueued task somewhere to go"
expect 0 is_valid_subject "feat!: drop the deprecated builder"
expect 0 is_valid_subject "chore(main): release 2.3.0"

# Commit subjects that must fail
expect 1 is_valid_subject "sample: give an Enqueued task somewhere to go"
expect 1 is_valid_subject "current state"
expect 1 is_valid_subject "feat content digest"
expect 1 is_valid_subject "Feat: content digest"
expect 1 is_valid_subject "feat:"

if [ "$failures" -eq 0 ]; then
  echo "all fixtures pass"
else
  echo "$failures fixture(s) failed"
  exit 1
fi
