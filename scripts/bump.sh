#!/usr/bin/env bash
# Prepares a release: sets the next version from the newest release tag in every place that names it, then checks the
# result with scripts/version.sh. Commit the changes in a pull request; after the merge, scripts/release.sh tags it.
#
# - gradle.properties: `obvid.version` (the library and the demo app read it there);
# - README.md: the install line;
# - CHANGELOG.md: the `## Unreleased` section becomes `## <version>`, and a new empty `## Unreleased` goes above it.
#   The section must have text: each change adds its line there in its own pull request.
# If any step fails, the three files are restored.
#
# Usage: scripts/bump.sh patch|minor|major
#   Under 1.0, a change that breaks the public API is a minor bump; otherwise a patch.
set -euo pipefail

part=${1:-}
case "$part" in
  patch | minor | major) ;;
  *) echo "usage: scripts/bump.sh patch|minor|major" >&2; exit 2 ;;
esac

cd "$(git rev-parse --show-toplevel)"
fail() { echo "bump: $*" >&2; exit 1; }
files=(gradle.properties README.md CHANGELOG.md)

status=$(git status --porcelain --untracked-files=no)
[ -z "$status" ] || fail "uncommitted changes"

# The current state must pass the CI check (one version, its changelog section, the install line).
current=$(scripts/version.sh) || exit 1
newest=$(scripts/newest_tag.sh) || exit 1
if [ -n "$newest" ] && [ "v$current" != "$newest" ]; then
  fail "gradle.properties has $current, but the newest release is $newest: a bump is already waiting for its release"
fi

IFS=. read -r major minor patch <<<"$current"
case "$part" in
  patch) patch=$((10#$patch + 1)) ;;
  minor) minor=$((10#$minor + 1)); patch=0 ;;
  major) major=$((10#$major + 1)); minor=0; patch=0 ;;
esac
[ "$major" -lt 100 ] && [ "$minor" -lt 100 ] && [ "$patch" -lt 100 ] ||
  fail "$major.$minor.$patch: each part must stay below 100 (the demo's versionCode); bump the next part instead"
next="$major.$minor.$patch"

grep -qx '## Unreleased' CHANGELOG.md || fail "CHANGELOG.md has no '## Unreleased' heading (exactly that line)"
scripts/changelog.sh Unreleased > /dev/null 2>&1 ||
  fail "CHANGELOG.md: the '## Unreleased' section is empty; add the changes of this release there first"
! grep -qE "^## ${next//./\\.}( |$)" CHANGELOG.md || fail "CHANGELOG.md already has a '## $next' section"

# Edits go through temporary files outside the work tree; any failure from here on restores the three files.
tmp=$(mktemp -d)
restore() { git checkout -- "${files[@]}"; echo "bump: stopped, ${files[*]} restored" >&2; }
trap 'rm -rf "$tmp"' EXIT
trap restore ERR
trap 'restore; exit 130' INT TERM HUP

sed "s/^obvid\.version=.*/obvid.version=$next/" gradle.properties > "$tmp/gradle.properties"
sed "s/^dependencies { implementation(\"dev\.serhiiyaremych:obvid:[^\"]*\") }$/dependencies { implementation(\"dev.serhiiyaremych:obvid:$next\") }/" \
  README.md > "$tmp/README.md"
awk -v next_version="$next" '
  !done && $0 == "## Unreleased" { print; print ""; print "## " next_version; done = 1; next }
  { print }
' CHANGELOG.md > "$tmp/CHANGELOG.md"
for f in "${files[@]}"; do cat "$tmp/$f" > "$f"; done

scripts/version.sh --new > /dev/null
trap - ERR INT TERM HUP
echo "Bumped $current → $next. Review 'git diff', commit it in a pull request; after the merge, run scripts/release.sh $next."
