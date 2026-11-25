#!/usr/bin/env bash
# Builds the signed per-ABI release APKs for a tag, for upload to the GitHub release.
#
# F-Droid builds the same tag from source (reproducible builds) and only publishes it if its
# unsigned APK is byte-identical to ours, so this mirrors F-Droid's build: a fresh clone of the
# tag, the recipe's prebuild step, and a `git clean` before every ABI so nothing is incremental.
#
# Usage: scripts/build-release.sh <tag> <keystore.jks> [key-alias]
#   e.g. scripts/build-release.sh v1.2.4 ~/Documents/Keystores/Camera-Release.jks
# Output: dist/<tag>/Camera-<versionName>-<versionCode>.apk (matches the F-Droid `Binaries:` URL);
#         set CAMERA_RELEASE_OUT=<dir> to write somewhere else.
set -euo pipefail

TAG=${1:?usage: $0 <tag> <keystore.jks> [key-alias]}
KEYSTORE=$(realpath "${2:?usage: $0 <tag> <keystore.jks> [key-alias]}")
ALIAS=${3:-}
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

read -rsp "Keystore password: " KS_PASS; echo
read -rsp "Key password (Enter if same): " KEY_PASS; echo
export CAMERA_KS_PASS="$KS_PASS" CAMERA_KEY_PASS="${KEY_PASS:-$KS_PASS}"

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
    apk="$OUT/Camera-$name-$code.apk"
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
