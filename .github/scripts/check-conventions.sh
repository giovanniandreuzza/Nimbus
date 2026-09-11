#!/usr/bin/env bash
# Validates branch name, commit subjects and pull request title against
# Conventional Commits and Conventional Branch.
#
# Sourced: exposes is_valid_branch and is_valid_subject.
# Executed: reads HEAD_REF, PR_TITLE, BASE_SHA, HEAD_SHA from the environment.
set -uo pipefail

TYPES='feat|fix|perf|refactor|docs|test|build|ci|chore|revert'
SUBJECT_RE="^(${TYPES})(\([a-z0-9]+([._-][a-z0-9]+)*\))?!?: .+"
BRANCH_RE='^(main|develop|(feature|bugfix|hotfix|release|chore)/[a-z0-9]+([._-][a-z0-9]+)*)$'
# release-please names its own branch, and that name has the consecutive
# hyphens the convention forbids. Allowed explicitly rather than by loosening
# the rule for everyone.
BOT_BRANCH='release-please--branches--main'

is_valid_branch() {
  [ "$1" = "$BOT_BRANCH" ] && return 0
  [[ "$1" =~ $BRANCH_RE ]]
}

is_valid_subject() {
  [[ "$1" =~ $SUBJECT_RE ]]
}

main() {
  local failures=0

  if is_valid_branch "$HEAD_REF"; then
    echo "branch ok: $HEAD_REF"
  else
    echo "::error::branch '$HEAD_REF' is not a Conventional Branch name."
    echo "::error::Use feature/, bugfix/, hotfix/, release/ or chore/ followed by lowercase words separated by single - _ or ."
    failures=$((failures + 1))
  fi

  if is_valid_subject "$PR_TITLE"; then
    echo "pull request title ok: $PR_TITLE"
  else
    echo "::error::pull request title '$PR_TITLE' is not a Conventional Commit subject."
    echo "::error::It becomes the commit message when this pull request is squashed."
    failures=$((failures + 1))
  fi

  # --no-merges is deliberate: only squash and rebase merges are allowed into
  # main, and neither puts a merge commit there — rebase replays the commits and
  # drops the merges, squash collapses everything into one. Checking merge
  # subjects would fail a branch that merged main into itself, over a commit
  # that will never exist on main.
  local subject
  while IFS= read -r subject; do
    [ -z "$subject" ] && continue
    if is_valid_subject "$subject"; then
      echo "commit ok: $subject"
    else
      echo "::error::commit subject '$subject' is not a Conventional Commit."
      echo "::error::Allowed types: ${TYPES//|/ }"
      failures=$((failures + 1))
    fi
  done < <(git log --no-merges --format=%s "${BASE_SHA}..${HEAD_SHA}")

  if [ "$failures" -gt 0 ]; then
    echo "::error::$failures convention violation(s)."
    exit 1
  fi
  echo "conventions ok"
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  main "$@"
fi
