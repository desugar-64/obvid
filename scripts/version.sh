#!/usr/bin/env bash
# Checks the version of Obvid and prints it. The version lives in one place, `obvid.version` in gradle.properties: the
# library and the demo app read it from there (the demo's versionCode is major·10000 + minor·100 + patch).
#
# Every mode checks that the version is <major>.<minor>.<patch> (minor and patch below 100), that CHANGELOG.md has a
# section for it (scripts/changelog.sh), that the README's dependency line names it, and that it is not older than the
# newest `v<version>` tag on GitHub.
#
# Usage: scripts/version.sh [--new | --tag <tag>]
#   (none)       CI on main and pull requests: the version may equal the newest tag (released) or be newer (bumped).
#   --new        scripts/release.sh, before the tag: the version must be newer than every tag.
#   --tag <tag>  the release workflow: the tag must be v<version>.
set -euo pipefail

mode=${1:-}
tag=${2:-}
case "$mode" in
  "" | --new) ;;
  --tag) [ -n "$tag" ] || { echo "usage: scripts/version.sh [--new | --tag <tag>]" >&2; exit 2; } ;;
  *) echo "usage: scripts/version.sh [--new | --tag <tag>]" >&2; exit 2 ;;
esac

cd "$(git rev-parse --show-toplevel)"
fail() { echo "version: $*" >&2; exit 1; }

version=$(sed -n 's/^obvid\.version=\(.*\)$/\1/p' gradle.properties)
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] ||
  fail "gradle.properties: obvid.version '$version' is not <major>.<minor>.<patch> with minor and patch below 100"

scripts/changelog.sh "$version" > /dev/null || exit 1

grep -qF "implementation(\"dev.serhiiyaremych:obvid:$version\")" README.md ||
  fail "README.md: the dependency line does not name $version (scripts/bump.sh sets it)"

# Versions as one comparable number: minor and patch are below 100.
number() { local IFS=.; set -- $1; echo $(( $1 * 10000 + $2 * 100 + $3 )); }

newest=$(scripts/newest_tag.sh) || exit 1
if [ -n "$newest" ]; then
  v=$(number "$version")
  n=$(number "${newest#v}")
  [ "$v" -ge "$n" ] || fail "version $version is older than the newest tag $newest"
  if [ "$mode" = --new ] && [ "$v" -eq "$n" ]; then
    fail "version $version is already released ($newest): run scripts/bump.sh first"
  fi
fi

if [ "$mode" = --tag ] && [ "$tag" != "v$version" ]; then
  fail "tag $tag does not match version $version in gradle.properties"
fi

echo "$version"
