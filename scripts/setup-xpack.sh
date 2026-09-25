#!/usr/bin/env bash
#
# Installs the xPack this application is built with, at the version pinned in
# pom.xml (xpack.release), so a build here and a build in CI use the same one:
#
#   ~/.xpack/sdk/<version>/   xPack's binaries, from its GitHub release,
#                             checked against the release's SHA256SUMS
#   ~/.m2/repository          xpack-maven-plugin, built from xPack's source at
#                             the same release's tag
#
# Run once per machine, and again after changing xpack.release. Does nothing
# for a part that is already there.
#
# Needs: curl, tar (unzip on Windows), git, Maven, a JDK 21.
#
# Usage: scripts/setup-xpack.sh
#   XPACK_SDK_ROOT   where to put the binaries (default: ~/.xpack/sdk)
#   MVN              the Maven to run (default: mvn on the PATH)

set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
version="$(sed -n 's:.*<xpack.release>\(.*\)</xpack.release>.*:\1:p' "$here/pom.xml")"
[ -n "$version" ] || { echo "no <xpack.release> in pom.xml" >&2; exit 2; }
sdk_root="${XPACK_SDK_ROOT:-$HOME/.xpack/sdk}"
home="$sdk_root/$version"
source_url="https://github.com/Zaaim-Halim/xPack"
MVN="${MVN:-mvn}"

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)            platform=macos-arm64 archive=tar.gz exe= ;;
    Darwin-x86_64)           platform=macos-x64 archive=tar.gz exe= ;;
    Linux-x86_64)            platform=linux-x64 archive=tar.gz exe= ;;
    Linux-aarch64)           platform=linux-arm64 archive=tar.gz exe= ;;
    MINGW*-x86_64|MSYS*-x86_64) platform=windows-x64 archive=zip exe=.exe ;;
    *) echo "xPack $version has no build for $(uname -s) $(uname -m)" >&2; exit 2 ;;
esac

if [ -x "$home/xpack$exe" ]; then
    echo "xPack $version: $home"
else
    name="xpack-$version-$platform"
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    echo "xPack $version: downloading $name.$archive"
    curl -fsSL -o "$work/$name.$archive" "$source_url/releases/download/v$version/$name.$archive"
    curl -fsSL -o "$work/SHA256SUMS" "$source_url/releases/download/v$version/SHA256SUMS"
    # A binary that does not match the release's own checksum is refused,
    # whatever went wrong on the way.
    expected="$(awk -v f="$name.$archive" '$2 == f || $2 == "*" f { print $1 }' "$work/SHA256SUMS")"
    [ -n "$expected" ] || { echo "SHA256SUMS does not list $name.$archive" >&2; exit 1; }
    if command -v sha256sum >/dev/null; then
        actual="$(sha256sum "$work/$name.$archive" | awk '{ print $1 }')"
    else
        actual="$(shasum -a 256 "$work/$name.$archive" | awk '{ print $1 }')"
    fi
    [ "$actual" = "$expected" ] || { echo "$name.$archive does not match its checksum" >&2; exit 1; }
    if [ "$archive" = zip ]; then
        if command -v unzip >/dev/null; then
            unzip -q "$work/$name.$archive" -d "$work"
        else
            7z x -bd -o"$work" "$work/$name.$archive" >/dev/null
        fi
    else
        tar xzf "$work/$name.$archive" -C "$work"
    fi
    mkdir -p "$sdk_root"
    rm -rf "$home"
    mv "$work/$name" "$home"
    echo "xPack $version: $home"
fi

plugin_version="$(sed -n '/<artifactId>xpack-maven-plugin<\/artifactId>/{n;s:.*<version>\(.*\)</version>.*:\1:p;}' "$here/pom.xml" | head -1)"
repository="$("$MVN" -q help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null || echo "$HOME/.m2/repository")"
# Maven answers with a Windows path on Windows; the tests below need the
# shell's own form of it.
if command -v cygpath >/dev/null; then
    repository="$(cygpath -u "$repository")"
fi
plugin="$repository/io/xpack/xpack-maven-plugin/$plugin_version/xpack-maven-plugin-$plugin_version.jar"
marker="$repository/io/xpack/xpack-maven-plugin/$plugin_version/built-from-v$version"
if [ -f "$plugin" ] && [ -f "$marker" ]; then
    echo "xpack-maven-plugin $plugin_version: from xPack $version, installed"
else
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    echo "xpack-maven-plugin $plugin_version: building from xPack $version"
    git -c advice.detachedHead=false clone -q --depth 1 --branch "v$version" "$source_url.git" "$work/xpack"
    "$MVN" -B -q -f "$work/xpack/integrations/maven/pom.xml" install -DskipTests
    touch "$marker"
    echo "xpack-maven-plugin $plugin_version: installed"
fi
