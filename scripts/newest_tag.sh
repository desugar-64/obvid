#!/usr/bin/env bash
# Prints the newest release tag on GitHub (`v<major>.<minor>.<patch>`, compared as numbers), or nothing when there is
# none. Reads the remote, so it also works in a shallow CI checkout without tags.
#
# Usage: scripts/newest_tag.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
tags=$(git ls-remote --tags --refs origin 'v*') || { echo "newest_tag: cannot list the tags of origin" >&2; exit 1; }
sed -n 's|.*refs/tags/\(v[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$|\1|p' <<<"$tags" |
  sort -t. -k1.2,1n -k2,2n -k3,3n | tail -n 1
