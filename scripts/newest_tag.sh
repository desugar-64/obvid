#!/usr/bin/env bash
# Prints the newest release tag on GitHub (`v<major>.<minor>.<patch>`, each below 100 without leading zeros, compared
# as numbers), or nothing when there is none. Tags of any other form are ignored. Reads the remote, so it also works in
# a shallow CI checkout without tags.
#
# Usage: scripts/newest_tag.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
refs=$(git ls-remote --tags --refs origin 'v*') || { echo "newest_tag: cannot list the tags of origin" >&2; exit 1; }
tags=$(sed -n 's|.*refs/tags/||p' <<<"$refs" | grep -E '^v(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$' || true)
[ -z "$tags" ] || sort -t. -k1.2,1n -k2,2n -k3,3n <<<"$tags" | tail -n 1
