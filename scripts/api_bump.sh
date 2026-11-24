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
# Always the tag on GitHub, never a local tag of the same name, read by its object ID (FETCH_HEAD can change under a
# concurrent fetch).
id=$(git ls-remote --refs origin "refs/tags/$tag" | cut -f1)
[ -n "$id" ] || { echo "api_bump: origin has no tag $tag" >&2; exit 1; }
git fetch --quiet --no-tags origin "refs/tags/$tag" || { echo "api_bump: cannot fetch the tag $tag" >&2; exit 1; }
old=$(git show "$id:$file") || { echo "api_bump: $tag has no $file" >&2; exit 1; }
new=$(cat "$file")
major=${tag#v}
major=${major%%.*}

if [ "$old" = "$new" ]; then
  echo patch
  exit 0
fi
diff -u --label "$file at $tag" --label "$file now" <(echo "$old") <(echo "$new") >&2 || true

# Each line with its class header in front, so that a member moved to another class counts as removed from the first.
# Sorted into files, so that a failure stops the script.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
members() { awk '/\{$/ { cls = $0 } { print cls "\t" $0 } /^}$/ { cls = "" }'; }
members <<<"$old" | sort > "$tmp/old"
members <<<"$new" | sort > "$tmp/new"
# Lines of the release's record that the current record lacks, duplicates counted.
removed=$(comm -23 "$tmp/old" "$tmp/new")
if [ -n "$removed" ]; then
  if [ "$major" -eq 0 ]; then echo minor; else echo major; fi
else
  if [ "$major" -eq 0 ]; then echo patch; else echo minor; fi
fi
