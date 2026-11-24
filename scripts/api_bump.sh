#!/usr/bin/env bash
# Prints the smallest version bump that the public API change since a release needs: `patch`, `minor` or `major`.
# It compares the API record (obvid/api/obvid.api, which CI keeps equal to the code) with its copy at the release tag;
# the diff goes to stderr.
# - A line removed or changed breaks binary compatibility: `minor` under 1.0, `major` from 1.0.
# - Only new lines (a new function, overload or class): `patch` under 1.0, `minor` from 1.0.
# - No change: `patch`.
#
# Usage: scripts/api_bump.sh <tag>
set -euo pipefail

tag=${1:?usage: scripts/api_bump.sh <tag>}
file=obvid/api/obvid.api

cd "$(git rev-parse --show-toplevel)"
git rev-parse -q --verify "refs/tags/$tag" > /dev/null ||
  git fetch --quiet origin "refs/tags/$tag:refs/tags/$tag" ||
  { echo "api_bump: cannot fetch the tag $tag" >&2; exit 1; }
old=$(git show "$tag:$file") || { echo "api_bump: $tag has no $file" >&2; exit 1; }
new=$(cat "$file")
major=${tag#v}
major=${major%%.*}

if [ "$old" = "$new" ]; then
  echo patch
  exit 0
fi
diff -u --label "$file at $tag" --label "$file now" <(echo "$old") <(echo "$new") >&2 || true
# Lines of the release's record that the current record lacks, duplicates counted (each class ends with `}`).
removed=$(comm -23 <(sort <<<"$old") <(sort <<<"$new"))
if [ -n "$removed" ]; then
  if [ "$major" -eq 0 ]; then echo minor; else echo major; fi
else
  if [ "$major" -eq 0 ]; then echo patch; else echo minor; fi
fi
