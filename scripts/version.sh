#!/usr/bin/env bash
# Checks the version of Obvid and prints it. The version lives in one place, `obvid.version` in gradle.properties: the
# library and the demo app read it from there (the demo's versionCode is major·10000 + minor·100 + patch).
#
# Every mode checks that gradle.properties sets the version once, as <major>.<minor>.<patch> (each below 100), that
# CHANGELOG.md has a section for it (scripts/changelog.sh), that the README's install line names it, and that it is not
# older than the newest `v<version>` tag on GitHub.
#
# Usage: scripts/version.sh [--new | --tag <tag>]
#   (none)       CI on main and pull requests: the version may equal the newest tag (released) or be newer (bumped).
#   --new        scripts/release.sh, before the tag: the version is newer than every tag, the bump is at least what
#                the public API change since that tag needs (scripts/api_bump.sh), and `## Unreleased` in
#                CHANGELOG.md is empty (a change merged after the bump would be missing from the release text).
#   --tag <tag>  the release workflow: the tag is v<version>, and `## Unreleased` is empty.
set -euo pipefail

usage() { echo "usage: scripts/version.sh [--new | --tag <tag>]" >&2; exit 2; }
mode=${1:-}
tag=${2:-}
case "$mode" in
  "" | --new) ;;
  --tag) [ -n "$tag" ] || usage ;;
  *) usage ;;
esac

cd "$(git rev-parse --show-toplevel)"
fail() { echo "version: $*" >&2; exit 1; }

# Any spelling Java's properties loader reads as the key (spaces, `:`, an escaped dot, no value), so that a second
# line cannot win in Gradle.
count=$(grep -cE '^[[:space:]]*obvid\\?\.version([[:space:]=:]|$)' gradle.properties || true)
[ "$count" = 1 ] || fail "gradle.properties must set obvid.version exactly once (found $count)"
version=$(sed -n 's/^obvid\.version=\(.*\)$/\1/p' gradle.properties)
[[ "$version" =~ ^(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] ||
  fail "gradle.properties: obvid.version '$version' is not <major>.<minor>.<patch>, each below 100"

scripts/changelog.sh "$version" > /dev/null || exit 1
# One exact `## Unreleased` heading, so that the check of its contents below sees all of them.
# Markdown also takes a heading indented by up to three spaces.
count=$(grep -ciE '^ {0,3}##[[:space:]]*unreleased[[:space:]]*$' CHANGELOG.md || true)
[ "$count" = 1 ] && grep -qx '## Unreleased' CHANGELOG.md ||
  fail "CHANGELOG.md must have exactly one '## Unreleased' heading (exactly that line)"

grep -qxF "dependencies { implementation(\"dev.serhiiyaremych:obvid:$version\") }" README.md ||
  fail "README.md: the install line does not name $version (scripts/bump.sh sets it)"

# Versions as one comparable number (10#: no octal for a leading zero).
number() { local IFS=.; set -- $1; echo $(( 10#$1 * 10000 + 10#$2 * 100 + 10#$3 )); }

newest=$(scripts/newest_tag.sh) || exit 1
if [ -n "$newest" ]; then
  v=$(number "$version")
  n=$(number "${newest#v}")
  [ "$v" -ge "$n" ] || fail "version $version is older than the newest tag $newest"
  if [ "$mode" = --new ] && [ "$v" -eq "$n" ]; then
    fail "version $version is already released ($newest): run scripts/bump.sh first"
  fi
  # Before the tag: the bump is at least what the public API change since that release needs (also for an API change
  # merged after the bump).
  if [ "$mode" = --new ]; then
    IFS=. read -r a b _ <<<"${newest#v}"
    IFS=. read -r x y _ <<<"$version"
    if [ "$x" != "$a" ]; then bump=2; elif [ "$y" != "$b" ]; then bump=1; else bump=0; fi
    need=$(scripts/api_bump.sh "$newest") || exit 1
    case "$need" in patch) r=0 ;; minor) r=1 ;; major) r=2 ;; esac
    [ "$bump" -ge "$r" ] ||
      fail "the public API change since $newest (diff above) needs a $need bump, but $version is smaller"
  fi
fi

if [ "$mode" = --tag ] && [ "$tag" != "v$version" ]; then
  fail "tag $tag does not match version $version in gradle.properties"
fi
if [ -n "$mode" ] && scripts/changelog.sh Unreleased > /dev/null 2>&1; then
  fail "CHANGELOG.md: '## Unreleased' has changes that $version would ship without: move them into '## $version'"
fi

echo "$version"
