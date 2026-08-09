#!/usr/bin/env bash
set -euo pipefail

# Builds the CloudflareSpeedTest (cfst) Go binary as Android shared libraries
# and installs them into the app's jniLibs directory.
#
# Usage:
#   bash scripts/build-cfst-android.sh [TAG] [JNI_LIBS_DIR]
#
#   TAG          git tag/branch of XIU2/CloudflareSpeedTest to build (default: v2.3.5)
#   JNI_LIBS_DIR jniLibs output directory (default: android/app/src/main/jniLibs)
#
# Requires Go 1.21+ (android/arm cross-compile support).

TAG="${1:-v2.3.5}"
JNI_LIBS_DIR="${2:-android/app/src/main/jniLibs}"
REPO_URL="https://github.com/XIU2/CloudflareSpeedTest.git"

if ! command -v go >/dev/null 2>&1; then
  echo "ERROR: go toolchain not found. Please install Go 1.21+ (https://go.dev/dl/)." >&2
  exit 1
fi

BASE_DIR="$(pwd)"
case "$JNI_LIBS_DIR" in
  /*) ;;
  *) JNI_LIBS_DIR="$BASE_DIR/$JNI_LIBS_DIR" ;;
esac

echo "Building CloudflareSpeedTest tag '$TAG' for Android into '$JNI_LIBS_DIR'"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

git clone --depth 1 --branch "$TAG" "$REPO_URL" "$TMP_DIR/cfst"
cd "$TMP_DIR/cfst"

build_target() {
  local abi="$1"
  local arch="$2"
  local goarm="$3"
  local dest="$JNI_LIBS_DIR/$abi"

  echo "==> Building $abi (GOARCH=$arch GOARM=${goarm:-none})"
  if [ -n "$goarm" ]; then
    GOOS=android GOARCH="$arch" GOARM="$goarm" CGO_ENABLED=0 go build -o cfst .
  else
    GOOS=android GOARCH="$arch" CGO_ENABLED=0 go build -o cfst .
  fi

  mkdir -p "$dest"
  cp cfst "$dest/libcfst.so"

  file "$dest/libcfst.so"
  sha256sum "$dest/libcfst.so"
}

build_target arm64-v8a arm64 ""
build_target armeabi-v7a arm 7

echo "Done. Native libraries installed under '$JNI_LIBS_DIR'"
