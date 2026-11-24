#!/usr/bin/env bash
# Starts a release: checks that `main` is ready for version <version>, then creates and pushes the tag `v<version>`.
# The tag starts .github/workflows/release.yml, which publishes the release without further approval.
#
# Before running: scripts/bump.sh in a pull request, merged into `main`, and CI finished on `main`.
#
# Usage: scripts/release.sh <version> [--dry-run]
#   --dry-run  run every check, then stop before the tag
# GH selects the GitHub CLI command (default `gh`), for a setup with several accounts.
set -euo pipefail

version=${1:?usage: scripts/release.sh <version> [--dry-run]}
dry_run=false
[ "${2:-}" = "--dry-run" ] && dry_run=true
gh=${GH:-gh}
tag="v$version"

cd "$(git rev-parse --show-toplevel)"
fail() { echo "release: $*" >&2; exit 1; }

# 1. A clean `main` that matches GitHub: the tag goes on the commit that CI checked.
[ "$(git branch --show-current)" = main ] || fail "not on main"
[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "uncommitted changes"
git fetch --quiet origin main --tags
head=$(git rev-parse HEAD)
[ "$head" = "$(git rev-parse origin/main)" ] || fail "main differs from origin/main: pull or push first"

# 2. The version: newer than every release, with its changelog entry and README line (the release workflow checks
# again).
built=$(scripts/version.sh --new) || exit 1
[ "$built" = "$version" ] || fail "gradle.properties has version $built, not $version"
notes=$(scripts/changelog.sh "$version") || exit 1

# 3. CI passed on this commit.
ci=$("$gh" run list --workflow ci.yml --commit "$head" --limit 1 --json status,conclusion \
  --jq '.[0] | "\(.status) \(.conclusion)"' 2>/dev/null) || fail "cannot read CI runs with '$gh'"
case "$ci" in
  "completed success") ;;
  "" | "null null") fail "no CI run for ${head:0:7} yet" ;;
  completed*) fail "CI on ${head:0:7}: ${ci#completed }" ;;
  *) fail "CI on ${head:0:7} is still running" ;;
esac

# 4. A new tag.
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || git ls-remote --exit-code --tags origin "$tag" >/dev/null; then
  fail "tag $tag exists"
fi

echo "Release $tag from ${head:0:7}, with these notes:"
echo
printf '%s\n' "$notes" | sed 's/^/  /'
echo
if $dry_run; then
  echo "Dry run: every check passed; no tag created."
  exit 0
fi
read -r -p "Push tag $tag? [y/N] " answer
[ "$answer" = y ] || [ "$answer" = Y ] || fail "stopped"

git tag "$tag" "$head"
git push origin "$tag"
echo "Pushed $tag. The release runs under Actions > Release."
