#!/usr/bin/env bash
#
# Validates xPack's install lifecycle with Expense Tracker, from nothing:
#
#   build with the Maven plugin -> installer -> silent install -> launch
#   -> bundled Java -> user data outside the installation -> health report
#   -> uninstall -> the user's data is still there
#
# Everything happens in a scratch directory with its own HOME and signing key,
# so nothing on this machine is touched and nothing is left behind.
#
# Needs: a JDK 21 with jlink, Maven, and xPack's binaries and Maven plugin
# (scripts/setup-xpack.sh installs both).
#
# Usage: validation/validate-install.sh
#   XPACK_HOME   directory holding xpack and its companions
#                (default: where scripts/setup-xpack.sh puts the version
#                pinned in pom.xml)
#   MVN          the Maven to run (default: mvn on the PATH)
#   KEEP=1       keep the scratch directory for inspection

set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
xpack_release="$(sed -n 's:.*<xpack.release>\(.*\)</xpack.release>.*:\1:p' "$here/pom.xml")"
XPACK_HOME="${XPACK_HOME:-$HOME/.xpack/sdk/$xpack_release}"
MVN="${MVN:-mvn}"
APP_ID="io.xpack.examples.expensetracker"
APP_NAME="Expense Tracker"

passed=0
failed=0
pass() { passed=$((passed + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { failed=$((failed + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; }
check() { if eval "$2"; then pass "$1"; else fail "$1" "${3:-}"; fi; }

[ -x "$XPACK_HOME/xpack" ] || { echo "no xpack in $XPACK_HOME; run scripts/setup-xpack.sh, or set XPACK_HOME" >&2; exit 2; }

# The physical path: on macOS the temporary directory is reached through a
# symbolic link, and the application reports where it really runs from.
scratch="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/expense-tracker-validation.XXXXXX")" && pwd -P)"
cleanup() { if [ "${KEEP:-0}" = 1 ]; then echo "kept $scratch"; else rm -rf "$scratch"; fi; }
trap cleanup EXIT
export HOME="$scratch/home"
mkdir -p "$HOME"
root="$scratch/apps"
data="$scratch/user-data"
install_dir="$root/$APP_ID"

echo "Expense Tracker: install lifecycle"
echo "  xpack    $("$XPACK_HOME/xpack" --version)"
echo "  scratch  $scratch"
echo

# --- build -------------------------------------------------------------------
"$XPACK_HOME/xpack" keygen --out "$scratch/keys/signing.json" >/dev/null 2>&1
build_log="$scratch/build.log"
if (cd "$here" && "$MVN" -B package io.xpack:xpack-maven-plugin:0.1.0-SNAPSHOT:installer \
        -Dxpack.home="$XPACK_HOME" -Dxpack.key="$scratch/keys/signing.json" \
        -Dxpack.publicKey="$scratch/keys/signing.pub.json" >"$build_log" 2>&1); then
    pass "the Maven plugin builds a signed package and an installer"
else
    fail "the Maven plugin builds a signed package and an installer" "see $build_log"
    tail -30 "$build_log"
    exit 1
fi
dist="$here/target/xpack/dist"
package="$(ls "$dist"/*.xpkg | head -1)"
check "the package verifies against the key it was signed with" \
    "'$XPACK_HOME/xpack' verify '$package' --key '$scratch/keys/signing.pub.json' >/dev/null 2>&1"

check "the package names its icon for this platform's desktop" \
    "'$XPACK_HOME/xpack' inspect --json '$package' 2>/dev/null | grep -q '\"icon\": *\"icon\\.'"
if [ -d "$dist/Install $APP_NAME.app" ]; then
    check "the installer carries the application's icon" "[ -f '$dist/Install $APP_NAME.app/Contents/Resources/AppIcon.icns' ]"
fi

# --- install -----------------------------------------------------------------
installer=""
if [ -d "$dist/Install $APP_NAME.app" ]; then
    installer="$(ls "$dist/Install $APP_NAME.app/Contents/MacOS/"* | head -1)"
else
    installer="$(ls "$dist"/*-installer 2>/dev/null | head -1 || true)"
fi
check "an installer was built for this platform" "[ -n '$installer' ] && [ -x '$installer' ]"
check "the installer installs silently" "'$installer' --silent --root '$root' >'$scratch/install.log' 2>&1" \
    "see $scratch/install.log"
launcher="$install_dir/$APP_NAME"
check "the launcher is where the installation keeps it" "[ -x '$launcher' ]"

# --- launch ------------------------------------------------------------------
version_out="$("$launcher" --version 2>/dev/null || true)"
check "the application starts through the launcher" "[ \"\$version_out\" = '$APP_NAME 1.0.0' ]" \
    "got: $version_out"

"$launcher" --data-dir="$data" --add "Coffee" 3.50 Food >/dev/null 2>&1 || true
"$launcher" --data-dir="$data" --add "Train ticket" 12.40 Transport >/dev/null 2>&1 || true
status="$("$launcher" --data-dir="$data" --status 2>/dev/null || true)"
field() { printf '%s\n' "$status" | sed -n "s/^$1=//p"; }

check "the command-line arguments reach the application" "[ \"\$(field expenses)\" = 2 ]" \
    "status was: $status"
check "the data is written where the user asked, outside the installation" \
    "[ -f '$data/expenses.db' ] && case '$data' in '$install_dir'*) false;; *) true;; esac"
check "it runs on the bundled Java, not the machine's" \
    "case \"\$(field java.home)\" in '$install_dir'/*) true;; *) false;; esac" "java.home=$(field java.home)"
check "xPack tells the application where it is installed" "[ \"\$(field xpack.application.dir)\" = '$install_dir' ]"
check "the total is exact to the cent" "[ \"\$(field total)\" = 15.90 ]" "total=$(field total)"
check "the version reported its own start (health report)" \
    "'$XPACK_HOME/xpack' --root '$root' list '$APP_ID' 2>/dev/null | grep -q '1.0.0 *good'"

# --- uninstall ---------------------------------------------------------------
# The uninstaller hands the work to a copy of itself and exits without
# waiting (on Windows it has to, so its own file can be deleted), so the
# installation disappears shortly after the command returns.
gone() {
    for _ in $(seq 1 60); do
        [ -e "$install_dir" ] || return 0
        sleep 0.5
    done
    return 1
}
check "the uninstaller removes the application" \
    "'$install_dir/Uninstall $APP_NAME' --yes >'$scratch/uninstall.log' 2>&1 && gone" \
    "see $scratch/uninstall.log"
check "the desktop entry is removed with it" "[ ! -e '$HOME/Applications/$APP_NAME.app' ]"
check "the user's data survives the uninstall" "[ -f '$data/expenses.db' ]"

echo
echo "  $passed passed, $failed failed"
[ "$failed" -eq 0 ]
