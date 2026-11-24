#!/usr/bin/env bash
# Prepares a release: sets the next version from the newest release tag in every place that names it, then checks the
# result with scripts/version.sh. Commit the changes in a pull request; after the merge, scripts/release.sh tags it.
#
# - gradle.properties: `obvid.version` (the library and the demo app read it there);
# - README.md: the dependency line;
# - CHANGELOG.md: the `## Unreleased` section becomes `## <version>`, and a new empty `## Unreleased` goes above it.
#   The section must have text: each change adds its line there in its own pull request.
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

[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "uncommitted changes"

current=$(sed -n 's/^obvid\.version=\(.*\)$/\1/p' gradle.properties)
newest=$(scripts/newest_tag.sh) || exit 1
if [ -n "$newest" ] && [ "v$current" != "$newest" ]; then
  fail "gradle.properties has $current, but the newest release is $newest: a bump is already waiting for its release"
fi

IFS=. read -r major minor patch <<<"$current"
case "$part" in
  patch) patch=$((patch + 1)) ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  major) major=$((major + 1)); minor=0; patch=0 ;;
esac
next="$major.$minor.$patch"

scripts/changelog.sh Unreleased > /dev/null 2>&1 ||
  fail "CHANGELOG.md: the '## Unreleased' section is missing or empty; add the changes of this release there first"

sed -i.bak "s/^obvid\.version=.*/obvid.version=$next/" gradle.properties
sed -i.bak "s/implementation(\"dev\.serhiiyaremych:obvid:[^\"]*\")/implementation(\"dev.serhiiyaremych:obvid:$next\")/" \
  README.md
awk -v next_version="$next" '
  !done && $0 == "## Unreleased" { print; print ""; print "## " next_version; done = 1; next }
  { print }
' CHANGELOG.md > CHANGELOG.md.new
mv CHANGELOG.md.new CHANGELOG.md
rm -f gradle.properties.bak README.md.bak

scripts/version.sh --new > /dev/null
echo "Bumped $current → $next. Review 'git diff', commit it in a pull request; after the merge, run scripts/release.sh $next."
