#!/usr/bin/env bash
#
# Puts the packages of the last published releases into the local Maven
# repository, so `mvn verify -Prelease` builds a delta from each of them.
#
# A delta has to be built from exactly the bytes users installed, and those
# are the ones attached to this repository's GitHub Releases. So they are
# taken from there, not from any other copy. Drafts are skipped: nobody has
# installed them.
#
# Prints how many packages it installed; CI checks that as many deltas come
# out, so a delta that silently fails to build stops the release.
#
# Needs: gh (authenticated, GH_TOKEN in CI), Maven.
#
# Usage: scripts/previous-releases.sh PLATFORM VERSION [COUNT]
#   PLATFORM   the platform being built, such as macos-arm64
#   VERSION    the version being released; only earlier ones are taken
#   COUNT      how many earlier releases (default 3, the delta goal's own)
#   MVN        the Maven to run (default: mvn on the PATH)
#   GH_REPO    the repository (default: the one gh finds from the checkout)

set -euo pipefail

platform="$1"
version="$2"
count="${3:-3}"
MVN="${MVN:-mvn}"
here="$(cd "$(dirname "$0")/.." && pwd)"
group_id="$(sed -n 's:^  <groupId>\(.*\)</groupId>$:\1:p' "$here/pom.xml" | head -1)"
artifact_id="$(sed -n 's:^  <artifactId>\(.*\)</artifactId>$:\1:p' "$here/pom.xml" | head -1)"
[ -n "$group_id" ] && [ -n "$artifact_id" ] || { echo "could not read the project's coordinates from pom.xml" >&2; exit 2; }

# Published releases tagged vX.Y.Z, newest first, earlier than VERSION.
earlier="$(gh api --paginate 'repos/{owner}/{repo}/releases' \
        --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name' \
    | sed -n 's/^v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$/\1/p' \
    | { cat; echo "$version"; } | sort -u -V \
    | awk -v v="$version" '$0 == v { exit } { print }' \
    | sort -r -V | head -n "$count")"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
installed=0
for previous in $earlier; do
    file="Expense-Tracker-$previous-$platform.xpkg"
    # A release that has no package for this platform (one published before
    # the platform was added) simply has no delta to offer.
    if ! gh release download "v$previous" --pattern "$file" --dir "$work" >/dev/null 2>&1; then
        echo "v$previous has no $file; no delta from it" >&2
        continue
    fi
    "$MVN" -B -q install:install-file -Dfile="$work/$file" \
        -DgroupId="$group_id" -DartifactId="$artifact_id" -Dversion="$previous" \
        -Dclassifier="$platform" -Dpackaging=xpkg -DgeneratePom=true
    echo "v$previous: $file installed for a delta" >&2
    installed=$((installed + 1))
done
echo "$installed"
