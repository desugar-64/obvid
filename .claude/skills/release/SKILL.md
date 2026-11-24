---
name: release
description: Release a new version of Obvid - bump the version, open the release pull request, push the release tag and check the published release. Use when the user asks for a release, a version bump, or what a release involves.
---

# Release Obvid

A release publishes the library's Maven files to GitHub Pages (`https://desugar-64.github.io/obvid/maven`) and a
GitHub Release with the signed demo APK and the native debug symbols. Published versions are never overwritten, so a
release cannot be taken back: only start one when the user asks for it in their own words.

## Rules

- **One version, one place:** `obvid.version` in `gradle.properties`. The library, its Maven files and the demo app
  (`versionName`; `versionCode` = major·10000 + minor·100 + patch) read it. The README's install line names it too;
  `scripts/bump.sh` sets both. Never edit the version by hand.
- **Tags:** `v<version>`, e.g. `v0.1.1`. Only the repository's admins can push one (a tag ruleset). The tag starts
  `.github/workflows/release.yml`, which runs to the end with no approval. Artifact names come from the version, not
  from the tag.
- **SemVer, under 1.0:** a change that breaks the public API (`obvid/api/obvid.api`) is a `minor` bump; everything
  else is a `patch`. `major` waits for 1.0.
- **Changelog:** every pull request that changes what users get adds its line under `## Unreleased` in
  `CHANGELOG.md`. At release time that section becomes the version's section and the release text.
- **Checks** (`scripts/version.sh`; CI runs it on every push and pull request, `release.sh` and the release workflow run
  it again): `gradle.properties` sets the version once, as `<major>.<minor>.<patch>` with each part below 100; it has a
  non-empty `CHANGELOG.md` section, the README's install line names it, and it is not older than the newest `v*` tag.
  Before the tag and in the release workflow it must also be newer than every earlier tag (the workflow: equal to its
  own), and the one `## Unreleased` section must be empty: a change merged after the bump moves its line into the
  pending version's section, in a pull request. The workflow also checks that the tagged commit is on `main` with green
  CI from a push to `main`, and that the build produced this version.

## Steps

`GH` selects the GitHub CLI (default `gh`), for a setup with several accounts.

1. **Bump**, from an up-to-date `main`:
   ```sh
   git switch main && git pull --ff-only
   git switch -c release-<version>
   scripts/bump.sh patch      # or minor
   ```
   It sets the version from the newest tag, renames `## Unreleased` to `## <version>` and puts a new empty
   `## Unreleased` above it. It stops if the section is empty or a bump is already waiting for its release. Read the
   section: it is the release text, written for people who use Obvid.
2. **Release pull request:** commit `release: <version>`, push, and open it (`${GH:-gh} pr create --title "Release
   <version>"`, body in the `feature-issue` skill's format: the AI line, STE). The user merges it.
3. **Tag**, once CI has passed on the merge commit in `main`:
   ```sh
   git switch main && git pull --ff-only
   GH=<cli> scripts/release.sh <version>      # --dry-run: every check, no tag
   ```
   It checks a clean `main` that matches GitHub, the version (`version.sh --new`), the changelog section, green CI on
   that commit and a new tag, shows the release text, asks once, then pushes `v<version>`.
4. **Watch** the run of this tag: `${GH:-gh} run list --workflow release.yml --branch v<version> --limit 1`, then
   `${GH:-gh} run watch <id>`.
5. **Verify:**
   - `${GH:-gh} release view v<version>`: not a draft, marked Latest, with `obvid-demo-<version>.apk` and
     `obvid-<version>-native-debug-symbols.zip`.
   - `https://desugar-64.github.io/obvid/maven/dev/serhiiyaremych/obvid/maven-metadata.xml` lists the version as
     `<release>` (GitHub Pages can take a few minutes).

## When the run fails

The workflow publishes in order: run artifacts, a draft release, the Maven files to `gh-pages`, then the draft goes
public.
- **Before the `gh-pages` push** nothing is public. A transient failure (a network error): rerun it,
  `${GH:-gh} run rerun <id>`; the new run replaces the draft. Otherwise fix the cause on `main` through a pull
  request. To release the same version, the tag must move to the fixed commit: deleting and re-creating it needs the
  user (the tag ruleset). A new patch version is often simpler.
- **After the `gh-pages` push** the Maven files are public and a complete draft is left; a new run refuses to overwrite
  them. Publish the draft by hand: `${GH:-gh} release edit v<version> --draft=false --latest`, or with
  `--latest=false` when a newer version is already released.
