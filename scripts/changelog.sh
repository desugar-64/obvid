#!/usr/bin/env bash
# Prints the section of one version from CHANGELOG.md: the lines after its heading (`## <version>`, optionally followed
# by a space and more text, such as a date) up to the next `## ` heading. Fails when the section is missing or has no
# text, so that CI and the release workflow stop a version that has no changelog entry.
#
# Usage: scripts/changelog.sh <version> [changelog file, default CHANGELOG.md]
set -euo pipefail

version=${1:?usage: scripts/changelog.sh <version> [changelog file]}
file=${2:-CHANGELOG.md}

# The heading match is exact up to a space: `## 0.1.0` does not match `## 0.1.01`.
notes=$(awk -v heading="## $version" '
  found && /^## / { exit }
  found { print }
  $0 == heading || index($0, heading " ") == 1 { found = 1 }
' "$file")

if ! grep -q '[^[:space:]]' <<<"$notes"; then
  echo "$file has no entry for $version: add a '## $version' section with the changes of this release" >&2
  exit 1
fi
printf '%s\n' "$notes" | sed -e '/./,$!d'
