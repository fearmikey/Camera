#!/usr/bin/env bash
# Builds the signed per-ABI release APKs for a tag, for upload to the GitHub release.
#
# F-Droid builds the same tag from source (reproducible builds) and only publishes it if its
# unsigned APK is byte-identical to ours, so this mirrors F-Droid's build: a fresh clone of the
# tag, the recipe's prebuild step, and a `git clean` before every ABI so nothing is incremental.
#
# Usage: scripts/build-release.sh <tag> [keystore.jks] [key-alias]
#   e.g. scripts/build-release.sh v1.2.4
#
# Signing settings come from a private file outside the repo, so the script (and agents running it)
# never prompt for or see the password. Default ~/.config/camera-release/release.env, override with
# CAMERA_RELEASE_CONFIG. chmod 600 it. Contents (bash syntax; quote values):
#   KEYSTORE='/path/to/Camera-Release.jks'
#   KS_PASS='keystore password'
#   KEY_PASS='key password'   # optional, defaults to KS_PASS
#   KEY_ALIAS='alias'         # optional
# Without the file, pass the keystore as the 2nd argument and type the passwords when asked.
#
# Output: dist/<tag>/Camera-<versionName>-<abi>.apk, e.g. Camera-1.2.4-arm64-v8a.apk (matches each
#         F-Droid build entry's `binary:` URL);
#         set CAMERA_RELEASE_OUT=<dir> to write somewhere else.
set -euo pipefail

CONFIG=${CAMERA_RELEASE_CONFIG:-$HOME/.config/camera-release/release.env}
if [[ -f "$CONFIG" ]]; then
    if (( 8#$(stat -c %a "$CONFIG") & 8#077 )); then
        echo "warning: $CONFIG is readable by other users; run: chmod 600 $CONFIG" >&2
    fi
    # shellcheck source=/dev/null
    source "$CONFIG"
fi

TAG=${1:?usage: $0 <tag> [keystore.jks] [key-alias]}
KEYSTORE=${2:-${KEYSTORE:-}}
[[ -n "$KEYSTORE" ]] || { echo "No keystore: pass it as the 2nd argument or set KEYSTORE in $CONFIG" >&2; exit 1; }
KEYSTORE=$(realpath "$KEYSTORE")
ALIAS=${3:-${KEY_ALIAS:-}}
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)

REPO=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
OUT=$(realpath -m "${CAMERA_RELEASE_OUT:-$REPO/dist/$TAG}")
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
if [[ -z "${JAVA_HOME:-}" && -d "$HOME/android-studio/jbr" ]]; then
    export JAVA_HOME="$HOME/android-studio/jbr"
fi
# apksigner runs `java` from PATH.
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"
APKSIGNER=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)

git -C "$REPO" rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null \
    || { echo "Tag $TAG does not exist" >&2; exit 1; }

if [[ -z "${KS_PASS:-}" ]]; then
    read -rsp "Keystore password: " KS_PASS; echo
    read -rsp "Key password (Enter if same): " KEY_PASS; echo
fi
export CAMERA_KS_PASS="$KS_PASS" CAMERA_KEY_PASS="${KEY_PASS:-$KS_PASS}"
unset KS_PASS KEY_PASS

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git clone --quiet "$REPO" "$WORK/Camera"
cd "$WORK/Camera"
git checkout --quiet --detach "$TAG"

mkdir -p "$OUT"
for abi in "${ABIS[@]}"; do
    echo "==> $abi"
    git reset --quiet --hard
    git clean -dffxq
    # Same prebuild step as the F-Droid recipe.
    sed -i -e '/foojay-resolver/d' settings.gradle.kts
    ./gradlew --quiet --no-daemon assembleRelease -PabiFilter="$abi"

    meta=app/build/outputs/apk/release/output-metadata.json
    name=$(grep -o '"versionName": *"[^"]*"' "$meta" | cut -d'"' -f4)
    code=$(grep -o '"versionCode": *[0-9]*' "$meta" | grep -o '[0-9]*$')
    apk="$OUT/Camera-$name-$abi.apk"
    echo "    versionCode $code"
    # F-Droid copies our signature onto its own unsigned build, so signing may only add the
    # APK Signing Block: no v1 JAR signature (rewrites META-INF; minSdk 31 doesn't need it) and
    # no re-alignment of entries (apksigner moves them unless --alignment-preserved).
    "$APKSIGNER" sign --ks "$KEYSTORE" ${ALIAS:+--ks-key-alias "$ALIAS"} \
        --ks-pass env:CAMERA_KS_PASS --key-pass env:CAMERA_KEY_PASS \
        --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
        --v4-signing-enabled false --alignment-preserved true \
        --out "$apk" app/build/outputs/apk/release/app-release-unsigned.apk
    echo "    $apk"
done

echo
"$APKSIGNER" verify --print-certs "$apk" | grep -i 'SHA-256'
echo "Upload everything in $OUT to the GitHub release for $TAG."
