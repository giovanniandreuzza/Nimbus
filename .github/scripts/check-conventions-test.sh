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

# End-to-end: main() reads commit subjects from stdin and exits non-zero on any
# violation. This is the contract both workflows depend on.
run_main() { # run_main <head_ref> <pr_title> <subjects...>
  local head_ref="$1" pr_title="$2"; shift 2
  printf '%s\n' "$@" | HEAD_REF="$head_ref" PR_TITLE="$pr_title" \
    bash "$(dirname "$0")/check-conventions.sh" >/dev/null 2>&1
}

if run_main "feature/all-good" "feat: a title" "fix: one" "docs: two"; then
  :
else
  echo "FAIL main(): a clean pull request should exit 0"
  failures=$((failures + 1))
fi

if run_main "feature/all-good" "feat: a title" "fix: one" "nope: two"; then
  echo "FAIL main(): a bad commit subject should exit non-zero"
  failures=$((failures + 1))
fi

if run_main "not-conventional" "feat: a title" "fix: one"; then
  echo "FAIL main(): a bad branch name should exit non-zero"
  failures=$((failures + 1))
fi

if run_main "feature/all-good" "no type here" "fix: one"; then
  echo "FAIL main(): a bad pull request title should exit non-zero"
  failures=$((failures + 1))
fi

if [ "$failures" -eq 0 ]; then
  echo "all fixtures pass"
else
  echo "$failures fixture(s) failed"
  exit 1
fi
