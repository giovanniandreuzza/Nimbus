# Release Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Nimbus a CI that builds every pull request and a release-please pipeline that versions, changelogs, tags and publishes to Maven Central from a reviewed pull request — then land the finished 2.3.0 through it.

**Architecture:** Two GitHub Actions workflows. `ci.yml` runs on pull requests: one job builds every Kotlin Multiplatform target on macOS, another validates branch name, commit subjects and pull request title against Conventional Commits and Conventional Branch. `release.yml` runs on pushes to `main`: release-please opens a release pull request, and merging it tags, releases and publishes. A ruleset on `main` makes both checks mandatory.

**Tech Stack:** GitHub Actions, `googleapis/release-please-action@v4`, `actions/create-github-app-token@v2`, Gradle 9.5 + Kotlin Multiplatform, `com.vanniktech.maven.publish` 0.36.0, Bash.

**Spec:** `docs/specs/2026-09-12-release-automation-design.md`

## Global Constraints

- **Repository:** `giovanniandreuzza/Nimbus`, public, default branch `main`, remote `origin` at `git@github-personal:giovanniandreuzza/Nimbus.git`.
- **Current published version:** 2.2.0 (Maven Central, 2026-08-04). The next release is 2.3.0.
- **`bootstrap-sha` for release-please:** `acb87a1daa84a39d80e8d1e1fed198fb6a584af7` — the current tip of `main`.
- **JDK 17** (`JvmTarget.JVM_17`), Temurin distribution in CI.
- **macOS runners** for anything that compiles: the iOS targets do not build elsewhere. The repository is public, so these minutes are free.
- **Allowed commit types:** `feat fix perf refactor docs test build ci chore revert`. Nothing else. `sample` is not a type.
- **Allowed branch prefixes:** `feature/ bugfix/ hotfix/ release/ chore/`, plus the bare branches `main` and `develop`, plus the literal `release-please--branches--main`.
- **Never hand-edit `version.txt` or `CHANGELOG.md`** after Task 3. Both are written by release-please.
- **Never bump the version in a feature branch.** Version bumps are the release pull request's job.
- **The build must stay green at all times** (project rule, `CLAUDE.md`). Never downgrade a dependency to fix a build.

---

## File Structure

**Created by this plan:**

| Path | Responsibility |
|---|---|
| `version.txt` | The single source of truth for the version. Written by release-please, read by both Gradle modules. |
| `release-please-config.json` | How release-please versions this repository and shapes the changelog. |
| `.release-please-manifest.json` | The current version, standing in for the tags this repository does not have. |
| `.github/scripts/check-conventions.sh` | Pure validation logic for branch names and commit subjects, plus a `main` that reads the pull request context from the environment. Sourceable, so it can be tested without GitHub. |
| `.github/scripts/check-conventions-test.sh` | Fixture table asserting what must pass and what must fail. Runs locally in under a second. |
| `.github/workflows/ci.yml` | `conventions` and `build` jobs on every pull request. The two required status checks. |
| `.github/workflows/release.yml` | release-please on pushes to `main`, then publish gated on `release_created`. |
| `CHANGELOG.md` | Seeded with a header; every section after that is generated. |

**Deleted by this plan:** `versions.properties`, `README.md.template`.

**Modified:** `nimbus/build.gradle.kts`, `nimbus-ktor/build.gradle.kts`, `README.md`, `.gitignore`.

---

### Task 1: Prepare a clean workspace

`main`'s working tree is dirty and every modified file is byte-identical to `e6c64ff`, the 2.3.0 branch's baseline commit — the changes are already preserved on that branch. They are stashed rather than discarded so nothing depends on that claim being right.

**Files:**
- Modify: none (git state only)

**Interfaces:**
- Produces: branch `chore/release-automation` based on `main`, a clean working tree, and a stash entry named `pre-release-automation`.

- [ ] **Step 1: Confirm the working tree is what this plan expects**

```bash
cd /Users/giovanni/Documents/Projects/Nimbus
git rev-parse --abbrev-ref HEAD              # expect: main
git rev-parse HEAD                           # expect: acb87a1daa84a39d80e8d1e1fed198fb6a584af7
git diff --name-only | wc -l                 # expect: 14
```

If HEAD is not `acb87a1`, stop: `bootstrap-sha` in Task 6 is pinned to that commit and must be re-derived.

- [ ] **Step 2: Verify every modified file is already on the 2.3.0 branch**

```bash
for f in $(git diff --name-only); do
  git show e6c64ff:"$f" > /tmp/baseline-check 2>/dev/null \
    && cmp -s "$f" /tmp/baseline-check \
    && echo "ok   $f" \
    || echo "DIFF $f"
done
```

Expected: every line starts with `ok`. A `DIFF` line means that file holds work not on the branch — stop and resolve it before stashing.

- [ ] **Step 3: Stash tracked changes and untracked working state**

```bash
git stash push -u -m "pre-release-automation" \
  -- $(git diff --name-only) docs/specs/2026-09-11-content-digest-design.md \
     docs/specs/2026-09-11-storage-port-layering.md nimbus/src/jvmTest
git status --short
```

Expected: only `?? .claude/`, `?? graphify-out/`, and the two files this plan is adding under `docs/` remain untracked. `.claude/` and `graphify-out/` are gitignored in Task 3.

- [ ] **Step 4: Create the branch**

```bash
git switch -c chore/release-automation
git rev-parse --abbrev-ref HEAD   # expect: chore/release-automation
```

- [ ] **Step 5: Commit the design and plan documents**

```bash
git add docs/specs/2026-09-12-release-automation-design.md docs/plans/2026-09-12-release-automation.md
git commit -m "docs: design and plan for release automation"
```

---

### Task 2: Move the version to `version.txt`

`simple` is release-please's release type for projects it has no native strategy for, and it updates `version.txt` with its default updater. Reading that file directly removes the marker comments and `extra-files` entry a `.properties` file would have needed, and removes ten lines of `Properties` loading from each module.

**Files:**
- Create: `version.txt`
- Modify: `nimbus/build.gradle.kts`, `nimbus-ktor/build.gradle.kts`
- Delete: `versions.properties`, `README.md.template`

**Interfaces:**
- Produces: `version.txt` containing `2.2.0`; both Gradle projects resolve `version` from it; no `generateReadme` task exists.

- [ ] **Step 1: Create `version.txt`**

```bash
printf '2.2.0\n' > version.txt
cat version.txt
```

- [ ] **Step 2: Replace version loading in `nimbus/build.gradle.kts`**

Delete the `import java.io.FileNotFoundException` and `import java.util.Properties` lines at the top, and replace:

```kotlin
val localProperties = loadProperties()

group = "io.github.giovanniandreuzza"
version = localProperties.getVersion()
```

with:

```kotlin
val libraryVersion = rootProject.file("version.txt").readText().trim()

group = "io.github.giovanniandreuzza"
version = libraryVersion
```

Replace the `coordinates(...)` version argument:

```kotlin
    coordinates(
        groupId = "io.github.giovanniandreuzza",
        artifactId = "nimbus",
        version = libraryVersion
    )
```

Delete the whole `tasks.register("generateReadme") { ... }` block, the `tasks.build { dependsOn("generateReadme") }` block, and the two helper functions at the bottom:

```kotlin
fun loadProperties() = rootProject.file("versions.properties").let { ... }

fun Properties.getVersion() = getProperty("VERSION") ?: "1.0.0"
```

- [ ] **Step 3: Apply the same change to `nimbus-ktor/build.gradle.kts`**

Same edits, minus the `generateReadme` blocks, which that module does not have. The artifactId stays `nimbus-ktor`.

- [ ] **Step 4: Delete the files that are now unread**

```bash
git rm versions.properties README.md.template
```

- [ ] **Step 5: Verify Gradle resolves the version from the new file**

```bash
./gradlew :nimbus:properties --console=plain -q | grep '^version:'
./gradlew :nimbus-ktor:properties --console=plain -q | grep '^version:'
```

Expected: `version: 2.2.0` from both.

- [ ] **Step 6: Verify nothing still references the deleted files**

```bash
grep -rn "versions.properties\|generateReadme\|README.md.template" \
  --include="*.kts" --include="*.kt" nimbus nimbus-ktor sample_android
```

Expected: no output. Scope the search to the source trees: `docs/` describes this change and
mentions all three names legitimately, and `.claude/worktrees/` holds separate checkouts of the
2.3.0 work that still carry the old build files until Task 10 rebases them.

- [ ] **Step 7: Verify the build is still green**

```bash
./gradlew :nimbus:jvmTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add version.txt nimbus/build.gradle.kts nimbus-ktor/build.gradle.kts
git commit -m "build: read the version from version.txt"
```

---

### Task 3: README markers, changelog seed, gitignore

`README.md` stops being generated and becomes an ordinary file whose version numbers release-please rewrites in place. The markers are HTML comments, invisible when rendered.

**Files:**
- Modify: `README.md`, `.gitignore`
- Create: `CHANGELOG.md` (currently an empty tracked file)

**Interfaces:**
- Produces: `README.md` with a `x-release-please-start-version` / `x-release-please-end` block around the dependency snippet, both coordinates reading `2.2.0`.

- [ ] **Step 1: Wrap the dependency block in `README.md`**

Replace lines 64-70 of `README.md` — the fenced `kotlin` block under `## Installation`, opening at line 64 and closing at line 70, currently advertising `2.1.0` — with:

````markdown
<!-- x-release-please-start-version -->
```kotlin
dependencies {
    implementation("io.github.giovanniandreuzza:nimbus:2.2.0")
    // Optional — recommended Ktor HTTP adapter:
    implementation("io.github.giovanniandreuzza:nimbus-ktor:2.2.0")
}
```
<!-- x-release-please-end -->
````

Both numbers change from `2.1.0` to `2.2.0`: the README was two releases behind, and the manifest in Task 6 declares 2.2.0 as current.

- [ ] **Step 2: Seed `CHANGELOG.md`**

```bash
cat > CHANGELOG.md <<'EOF'
# Changelog

All notable changes to this project are documented in this file. It is generated from
Conventional Commits by release-please — do not edit it by hand.

Entries start at 2.3.0. Releases up to and including 2.2.0 predate this file; their contents are
in the git history and on Maven Central.
EOF
```

- [ ] **Step 3: Add local tooling output to `.gitignore`**

Append to `.gitignore`:

```gitignore
.claude/
graphify-out/
```

- [ ] **Step 4: Verify the markers are well-formed and the tree is clean**

```bash
grep -n "x-release-please" README.md
grep -c "2.2.0" README.md          # expect: 2
git status --short | grep '^??'    # expect: no output
```

- [ ] **Step 5: Commit**

```bash
git add README.md CHANGELOG.md .gitignore
git commit -m "docs: let release-please own the version in the README"
```

---

### Task 4: The conventions check, test first

The spec requires this logic to be exercised against fixtures rather than debugged through pull requests. It lives in a script so it can run in a second locally; the workflow only supplies the environment.

**Files:**
- Create: `.github/scripts/check-conventions-test.sh`, `.github/scripts/check-conventions.sh`

**Interfaces:**
- Produces: `check-conventions.sh`, which when sourced exposes `is_valid_branch <name>` and `is_valid_subject <subject>` (exit 0 = valid), and when executed reads `HEAD_REF`, `PR_TITLE`, `BASE_SHA`, `HEAD_SHA` from the environment and exits non-zero on the first violation.

- [ ] **Step 1: Write the failing fixture test**

```bash
mkdir -p .github/scripts
cat > .github/scripts/check-conventions-test.sh <<'EOF'
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
EOF
chmod +x .github/scripts/check-conventions-test.sh
```

- [ ] **Step 2: Run it to verify it fails**

```bash
bash .github/scripts/check-conventions-test.sh
```

Expected: failure — `check-conventions.sh: No such file or directory`.

- [ ] **Step 3: Write the script**

```bash
cat > .github/scripts/check-conventions.sh <<'EOF'
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
EOF
chmod +x .github/scripts/check-conventions.sh
```

- [ ] **Step 4: Run the fixtures to verify they pass**

```bash
bash .github/scripts/check-conventions-test.sh
```

Expected: `all fixtures pass`.

- [ ] **Step 5: Exercise `main` against this very branch**

```bash
HEAD_REF=chore/release-automation \
PR_TITLE="ci: build every pull request and release from main" \
BASE_SHA=acb87a1 HEAD_SHA=HEAD \
bash .github/scripts/check-conventions.sh
```

Expected: every commit reported `commit ok`, ending in `conventions ok`.

- [ ] **Step 6: Commit**

```bash
git add .github/scripts
git commit -m "ci: check branch, commit and pull request conventions"
```

---

### Task 5: The CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: two status check names, `conventions` and `build`, referenced by the ruleset in Task 9.

- [ ] **Step 1: Write the workflow**

```bash
mkdir -p .github/workflows
cat > .github/workflows/ci.yml <<'EOF'
name: CI

on:
  pull_request:

concurrency:
  group: ci-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  conventions:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
        with:
          fetch-depth: 0
      - name: Check branch, commit and pull request conventions
        env:
          HEAD_REF: ${{ github.head_ref }}
          PR_TITLE: ${{ github.event.pull_request.title }}
          BASE_SHA: ${{ github.event.pull_request.base.sha }}
          HEAD_SHA: ${{ github.event.pull_request.head.sha }}
        run: bash .github/scripts/check-conventions.sh

  build:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      - name: Build every target and run every test
        run: ./gradlew build --stacktrace
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: '**/build/reports/tests/**'
          if-no-files-found: ignore
EOF
```

`fetch-depth: 0` on the `conventions` job is required: the default shallow clone has no `BASE_SHA..HEAD_SHA` range to walk.

- [ ] **Step 2: Verify the YAML parses**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('ci.yml parses')"
```

- [ ] **Step 3: Verify the build command works locally before trusting it in CI**

```bash
./gradlew build --stacktrace
```

Expected: `BUILD SUCCESSFUL`. This runs on macOS with the iOS targets, which is what the runner will do. A failure here is a build problem, not a workflow problem — fix it before pushing, and do not downgrade dependencies to get there.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: build and check conventions on every pull request"
```

---

### Task 6: release-please configuration and the release workflow

**Files:**
- Create: `release-please-config.json`, `.release-please-manifest.json`, `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `version.txt` from Task 2 — release-please's `simple` strategy writes it; `README.md` markers from Task 3.
- Produces: secret names the maintainer must create in Task 8: `RELEASE_PLEASE_APP_ID`, `RELEASE_PLEASE_APP_PRIVATE_KEY`, `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`, `SIGNING_IN_MEMORY_KEY_ID`, `SIGNING_IN_MEMORY_KEY_PASSWORD`.

- [ ] **Step 1: Write the release-please config**

```bash
cat > release-please-config.json <<'EOF'
{
  "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
  "bootstrap-sha": "acb87a1daa84a39d80e8d1e1fed198fb6a584af7",
  "packages": {
    ".": {
      "release-type": "simple",
      "package-name": "nimbus",
      "include-v-in-tag": true,
      "extra-files": [
        { "type": "generic", "path": "README.md" }
      ],
      "changelog-sections": [
        { "type": "feat", "section": "Features" },
        { "type": "fix", "section": "Bug Fixes" },
        { "type": "perf", "section": "Performance" },
        { "type": "refactor", "section": "Internal" },
        { "type": "docs", "section": "Documentation" },
        { "type": "test", "hidden": true },
        { "type": "build", "hidden": true },
        { "type": "ci", "hidden": true },
        { "type": "chore", "hidden": true }
      ]
    }
  }
}
EOF
```

`bootstrap-sha` stops release-please walking back to 2025 and generating a changelog for seventeen historical releases. It is ignored once the first release pull request merges and can be deleted then.

- [ ] **Step 2: Write the manifest**

```bash
cat > .release-please-manifest.json <<'EOF'
{
  ".": "2.2.0"
}
EOF
```

This is what tells release-please the current version, in the absence of tags.

- [ ] **Step 3: Write the release workflow**

```bash
cat > .github/workflows/release.yml <<'EOF'
name: Release

on:
  push:
    branches:
      - main

permissions:
  contents: write
  pull-requests: write
  issues: write

jobs:
  release-please:
    runs-on: ubuntu-latest
    outputs:
      release_created: ${{ steps.release.outputs.release_created }}
      tag_name: ${{ steps.release.outputs.tag_name }}
    steps:
      # A GitHub App token, not GITHUB_TOKEN: events raised by GITHUB_TOKEN do
      # not start workflow runs, so CI would never report on the release pull
      # request and its required checks could never pass.
      - uses: actions/create-github-app-token@v2
        id: app-token
        with:
          app-id: ${{ secrets.RELEASE_PLEASE_APP_ID }}
          private-key: ${{ secrets.RELEASE_PLEASE_APP_PRIVATE_KEY }}
      - uses: googleapis/release-please-action@v4
        id: release
        with:
          token: ${{ steps.app-token.outputs.token }}
          config-file: release-please-config.json
          manifest-file: .release-please-manifest.json

  publish:
    needs: release-please
    if: needs.release-please.outputs.release_created == 'true'
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v5
        with:
          ref: ${{ needs.release-please.outputs.tag_name }}
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish to Maven Central
        # Uploads and releases in one step: no manual button in the Central Portal.
        run: ./gradlew publishAndReleaseToMavenCentral --stacktrace
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_IN_MEMORY_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.SIGNING_IN_MEMORY_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_IN_MEMORY_KEY_PASSWORD }}
EOF
```

- [ ] **Step 4: Verify both files parse**

```bash
python3 -c "import json; json.load(open('release-please-config.json')); json.load(open('.release-please-manifest.json')); print('json ok')"
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml')); print('release.yml parses')"
```

- [ ] **Step 5: Verify the publish task name exists**

```bash
./gradlew :nimbus:tasks --group=publishing --console=plain -q | grep publishAndReleaseToMavenCentral
```

Expected: `publishAndReleaseToMavenCentral - Publishes to Maven Central and automatically triggers release`.

- [ ] **Step 6: Commit**

```bash
git add release-please-config.json .release-please-manifest.json .github/workflows/release.yml
git commit -m "ci: release and publish from main with release-please"
```

---

### Task 7: Land the automation

The ruleset does not exist yet, and cannot: it will require status checks by name, and those names only exist once the workflow has run. This pull request is the first run.

**Files:** none — git and GitHub operations only.

**Interfaces:**
- Produces: `ci.yml` on `main`, and the check names `conventions` and `build` registered on the repository.

- [ ] **Step 1: Push the branch**

```bash
git push -u origin chore/release-automation
```

- [ ] **Step 2: Open the pull request**

```bash
gh pr create --repo giovanniandreuzza/Nimbus --base main \
  --title "ci: build every pull request and release from main" \
  --body "Adds CI and release-please automation. See docs/specs/2026-09-12-release-automation-design.md.

Merged before the ruleset exists, because the ruleset requires status checks whose names only exist once this workflow has run.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01EXYEpm7DFmidJV389qqXvz"
```

- [ ] **Step 3: Watch both checks**

```bash
gh pr checks --repo giovanniandreuzza/Nimbus --watch
```

Expected: `conventions` and `build` both pass. If `build` fails on the Android SDK, `android-actions/setup-android@v3` is already in the workflow — read the log before changing anything.

- [ ] **Step 4: STOP — get explicit confirmation before merging**

This merges to `main` and is the first thing that will trigger `release.yml`. It should be a no-op there: every commit is `docs:`, `build:` or `ci:`, none of which is releasable, so release-please must open no pull request. Confirm with the maintainer, then:

```bash
gh pr merge --repo giovanniandreuzza/Nimbus --rebase --delete-branch
```

Rebase, not squash: the commits are already conventional and individually meaningful.

- [ ] **Step 5: Verify release.yml ran and proposed nothing**

```bash
gh run list --repo giovanniandreuzza/Nimbus --workflow=release.yml --limit 1
gh pr list --repo giovanniandreuzza/Nimbus
```

Expected: the run exists. It will **fail** at `create-github-app-token` because the secrets do not exist yet — that is expected and is fixed by Task 8. No release pull request is open either way.

---

### Task 8: Maintainer-only — GitHub App and secrets

**This task cannot be done by an agent.** It involves a private GPG key and creating a GitHub App. Hand it to the maintainer and wait.

**Interfaces:**
- Produces: the seven secrets that Task 6's workflow reads by name.

- [ ] **Step 1: Create the GitHub App**

At <https://github.com/settings/apps/new>: any name, homepage `https://github.com/giovanniandreuzza/Nimbus`, **uncheck Webhook → Active**. Repository permissions: **Contents: Read and write**, **Pull requests: Read and write**, **Issues: Read and write**. Create, then **Generate a private key** and download the `.pem`. Note the **App ID**.

- [ ] **Step 2: Install the App on the repository**

App settings → *Install App* → select the account → **Only select repositories** → `Nimbus`.

- [ ] **Step 3: Add the App secrets**

```bash
gh secret set RELEASE_PLEASE_APP_ID --repo giovanniandreuzza/Nimbus --body "<the App ID>"
gh secret set RELEASE_PLEASE_APP_PRIVATE_KEY --repo giovanniandreuzza/Nimbus < /path/to/downloaded-key.pem
```

- [ ] **Step 4: Add the Maven Central credentials**

These are the *user token* name and password from <https://central.sonatype.com/account>, not the website login.

```bash
gh secret set MAVEN_CENTRAL_USERNAME --repo giovanniandreuzza/Nimbus
gh secret set MAVEN_CENTRAL_PASSWORD --repo giovanniandreuzza/Nimbus
```

- [ ] **Step 5: Add the signing key**

The local setup signs with `signing.secretKeyRingFile`, a keyring on disk, which a runner has no equivalent of. Export the key in ASCII-armored form instead:

```bash
gpg --list-secret-keys --keyid-format=long          # find the key id
gpg --armor --export-secret-keys <KEY_ID> | gh secret set SIGNING_IN_MEMORY_KEY --repo giovanniandreuzza/Nimbus
gh secret set SIGNING_IN_MEMORY_KEY_ID --repo giovanniandreuzza/Nimbus        # last 8 characters of the key id
gh secret set SIGNING_IN_MEMORY_KEY_PASSWORD --repo giovanniandreuzza/Nimbus  # the key's passphrase
```

- [ ] **Step 6: Verify all seven exist**

```bash
gh secret list --repo giovanniandreuzza/Nimbus
```

Expected: `RELEASE_PLEASE_APP_ID`, `RELEASE_PLEASE_APP_PRIVATE_KEY`, `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`, `SIGNING_IN_MEMORY_KEY_ID`, `SIGNING_IN_MEMORY_KEY_PASSWORD`.

- [ ] **Step 7: Re-run the failed release workflow**

```bash
gh run list --repo giovanniandreuzza/Nimbus --workflow=release.yml --limit 1
gh run rerun <run-id> --repo giovanniandreuzza/Nimbus
```

Expected: the `release-please` job now succeeds and still proposes nothing, because no releasable commit has landed.

---

### Task 9: Protect `main`

Only after Task 7 has registered the check names and Task 8 has made the release workflow able to run.

**Files:** none — repository configuration only.

**Interfaces:**
- Produces: a ruleset making `conventions` and `build` mandatory on `main`, and merge settings allowing only squash and rebase.

- [ ] **Step 1: Confirm the check names exist exactly as the ruleset will spell them**

```bash
gh api /repos/giovanniandreuzza/Nimbus/commits/main/check-runs --jq '.check_runs[].name' | sort -u
```

Expected to include `conventions` and `build`. If a name differs, use the name reported here — the ruleset matches on this string.

- [ ] **Step 2: Set the merge strategies**

```bash
gh api -X PATCH /repos/giovanniandreuzza/Nimbus \
  -F allow_squash_merge=true \
  -F allow_rebase_merge=true \
  -F allow_merge_commit=false \
  -F delete_branch_on_merge=true \
  -f squash_merge_commit_title=PR_TITLE \
  -f squash_merge_commit_message=PR_BODY
```

- [ ] **Step 3: Create the ruleset**

```bash
cat > /tmp/nimbus-ruleset.json <<'EOF'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [],
  "conditions": {
    "ref_name": { "include": ["refs/heads/main"], "exclude": [] }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "allowed_merge_methods": ["squash", "rebase"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [
          { "context": "conventions" },
          { "context": "build" }
        ]
      }
    }
  ]
}
EOF
gh api -X POST /repos/giovanniandreuzza/Nimbus/rulesets --input /tmp/nimbus-ruleset.json
```

`required_approving_review_count` is **0** deliberately: GitHub does not let anyone approve their own pull request, so on a single-maintainer repository any higher number locks the maintainer out — release-please's own pull request included.

`bypass_actors` is empty deliberately: the REST documentation does not give the numeric `actor_id` per repository role, and on a public repository a wrong id would grant bypass to everyone with read access. The escape hatch is disabling the ruleset in Settings.

- [ ] **Step 4: Verify it took effect**

```bash
gh api /repos/giovanniandreuzza/Nimbus/rulesets --jq '.[] | {id, name, enforcement}'
gh api /repos/giovanniandreuzza/Nimbus --jq '{squash: .allow_squash_merge, rebase: .allow_rebase_merge, merge: .allow_merge_commit}'
```

Expected: one active ruleset named `main`; `merge: false`.

- [ ] **Step 5: Verify `main` actually rejects a direct push**

```bash
git switch main && git pull
git commit --allow-empty -m "chore: protection probe"
git push origin main     # expect: rejected by the ruleset
git reset --hard origin/main
```

Expected: the push is refused. If it succeeds, the ruleset is not applying — do not continue to Task 10.

---

### Task 10: Prepare the 2.3.0 branch

Fifteen commits on the local branch `worktree-nimbus-2-3-0-tests`, no remote, so history can be rewritten freely.

**Files:**
- Modify: git history of the 2.3.0 work; conflict resolution in `versions.properties`, `README.md`, `nimbus/build.gradle.kts`

**Interfaces:**
- Consumes: `main` as it stands after Task 7 — `version.txt` present, `versions.properties` and `README.md.template` gone, README markers in place.
- Produces: branch `feature/content-digest-and-storage-port`, rebased onto `main`, every commit subject conventional.

- [ ] **Step 1: Create the working branch from the existing work**

```bash
git switch -c feature/content-digest-and-storage-port worktree-nimbus-2-3-0-tests
git log --oneline main..HEAD | wc -l    # expect: 15
```

- [ ] **Step 2: Reword the two `sample:` commits**

`sample` is not a Conventional Commit type — it is a scope. Both commits touch only `sample_android`, so library consumers do not need them in the changelog: `chore(sample)` is the honest type, and it is hidden from the changelog by the config in Task 6.

```bash
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --msg-filter '
  sed -e "1s|^sample: give an Enqueued task somewhere to go$|chore(sample): give an Enqueued task somewhere to go|" \
      -e "1s|^sample: exercise what 2.3.0 added, and drive the UI from one collector$|chore(sample): exercise what 2.3.0 added, and drive the UI from one collector|"
' main..HEAD
```

- [ ] **Step 3: Verify every subject now passes the check**

The script is not on this branch yet — it lands with the rebase in Step 4 — so take it from `main`:

```bash
git show origin/main:.github/scripts/check-conventions.sh > /tmp/check-conventions.sh
source /tmp/check-conventions.sh
while IFS= read -r subject; do
  is_valid_subject "$subject" && echo "ok   $subject" || echo "FAIL $subject"
done < <(git log --no-merges --format=%s main..HEAD)
```

Expected: fifteen `ok` lines, no `FAIL`. A `FAIL` here is a commit that would block the pull request in Task 11.

- [ ] **Step 4: Rebase onto `main`**

```bash
git fetch origin
git rebase origin/main
```

Conflicts are expected in three files and have one resolution each:

| File | Why | Resolution |
|---|---|---|
| `versions.properties` | deleted on `main`, modified by `e6c64ff` and `c18ab95` | `git rm versions.properties` — the version is release-please's now |
| `README.md` | `main` added markers and 2.2.0; the branch adds the Content Digest section and bumps to 2.3.0 | keep the branch's prose **and** `main`'s markers; leave both coordinates at **2.2.0** |
| `nimbus/build.gradle.kts` | `main` changed version reading and removed `generateReadme`; the branch adds test dependencies | keep both: `main`'s version block, the branch's `commonTest`/`jvmTest` dependencies |

After each resolution: `git add <file> && git rebase --continue`.

- [ ] **Step 5: Verify no version bump survived the rebase**

```bash
cat version.txt                                  # expect: 2.2.0
grep -c "2.3.0" README.md                        # expect: 0
git diff origin/main..HEAD --name-only | grep -c versions.properties   # expect: 0
```

A `2.3.0` anywhere in `version.txt` or `README.md` means a bump leaked through: remove it. release-please writes those numbers.

- [ ] **Step 6: Verify the build is green after the rebase**

```bash
./gradlew build --stacktrace
```

Expected: `BUILD SUCCESSFUL`. This is the first time the 2.3.0 work and the new build files have been compiled together.

---

### Task 11: Release 2.3.0

**Files:** none — git and GitHub operations only.

**Interfaces:**
- Consumes: everything above.
- Produces: tag `v2.3.0`, a GitHub Release, `nimbus` and `nimbus-ktor` 2.3.0 on Maven Central, a populated `CHANGELOG.md`.

- [ ] **Step 1: Push and open the pull request**

```bash
git push -u origin feature/content-digest-and-storage-port
gh pr create --repo giovanniandreuzza/Nimbus --base main \
  --title "feat: content digest, core storage port and transport fixes" \
  --body "The 2.3.0 work: content digest computed in the write pass, an internal storage port for core, five transport fixes, tests on every target, and sample app updates.

Specs: docs/specs/2026-09-11-content-digest-design.md, docs/specs/2026-09-11-storage-port-layering.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01EXYEpm7DFmidJV389qqXvz"
```

- [ ] **Step 2: Watch the checks**

```bash
gh pr checks --repo giovanniandreuzza/Nimbus --watch
```

Expected: `conventions` and `build` pass. `conventions` is now validating all fifteen commits — this is the first real test of Step 2 of Task 10.

- [ ] **Step 3: STOP — confirm, then rebase-merge**

Rebase, **not squash**: squashing collapses fifteen conventional commits into one and the 2.3.0 changelog becomes a single line.

```bash
gh pr merge --repo giovanniandreuzza/Nimbus --rebase --delete-branch
```

- [ ] **Step 4: Wait for release-please to open the release pull request**

```bash
gh run list --repo giovanniandreuzza/Nimbus --workflow=release.yml --limit 1
gh pr list --repo giovanniandreuzza/Nimbus
```

Expected: a pull request titled `chore(main): release 2.3.0` on branch `release-please--branches--main`.

- [ ] **Step 5: Read the proposed release before merging it**

```bash
gh pr diff --repo giovanniandreuzza/Nimbus <pr-number>
```

Check: `version.txt` reads `2.3.0`; `README.md` coordinates read `2.3.0`; `CHANGELOG.md` lists the features and fixes under 2.3.0 with nothing from before the bootstrap. If the version is not 2.3.0, do not merge — a `feat!:` or a `BREAKING CHANGE:` footer has forced a major bump, and the commit history needs fixing first.

- [ ] **Step 6: STOP — this is the irreversible step**

Merging publishes to Maven Central, where nothing can be deleted or overwritten. Get explicit confirmation from the maintainer, then:

```bash
gh pr merge --repo giovanniandreuzza/Nimbus <pr-number> --squash
```

- [ ] **Step 7: Watch the publish job**

```bash
gh run watch --repo giovanniandreuzza/Nimbus
```

If `publish` fails after the release exists, the tag and GitHub Release are already created: re-run the job. If the failure is that 2.3.0 is already on Central, do not retry — release the next patch instead.

- [ ] **Step 8: Verify the artifacts are on Maven Central**

```bash
curl -s https://repo1.maven.org/maven2/io/github/giovanniandreuzza/nimbus/maven-metadata.xml | grep -A1 '<release>'
curl -s https://repo1.maven.org/maven2/io/github/giovanniandreuzza/nimbus-ktor/maven-metadata.xml | grep -A1 '<release>'
```

Expected: `2.3.0` for both. Central's index can lag several minutes behind a successful publish.

- [ ] **Step 9: Remove the bootstrap and clean up the worktrees**

`bootstrap-sha` is ignored after the first release pull request merges. Removing it keeps the config honest. This is an ordinary pull request on a `chore/` branch.

```bash
git switch main && git pull
git switch -c chore/drop-release-bootstrap
python3 - <<'EOF'
import json
c = json.load(open('release-please-config.json'))
c.pop('bootstrap-sha', None)
json.dump(c, open('release-please-config.json','w'), indent=2)
open('release-please-config.json','a').write('\n')
EOF
git commit -am "chore: drop the release-please bootstrap sha"
git push -u origin chore/drop-release-bootstrap
gh pr create --repo giovanniandreuzza/Nimbus --base main --fill
```

Then remove the now-merged worktrees:

```bash
git worktree remove .claude/worktrees/nimbus-2-3-0-tests
git worktree remove .claude/worktrees/nimbus-2-3-0   # locked: add --force
git stash list    # the Task 1 stash is now redundant; drop it once satisfied
```
