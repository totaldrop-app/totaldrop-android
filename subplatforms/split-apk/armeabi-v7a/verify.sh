#!/bin/sh
# SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Isolated sub-platform artifact & provenance verifier for `android-split-apk-armeabi-v7a` (ADR 0020).

set -eu

UNIT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$UNIT_DIR/../../.." && pwd)
RECIPE="$UNIT_DIR/RECIPE.toml"
WORKFLOW="$REPO_ROOT/.github/workflows/pkg-split-apk-armeabi-v7a.yml"

if [ "${1:-}" = "--check-recipe" ]; then
  [ -f "$RECIPE" ] || { echo "missing $RECIPE" >&2; exit 1; }
  [ -f "$WORKFLOW" ] || { echo "missing workflow $WORKFLOW" >&2; exit 1; }
  sh -n "$UNIT_DIR/build.sh"
  sh -n "$UNIT_DIR/verify.sh"
  grep -q 'android-split-apk-armeabi-v7a-v\*' "$WORKFLOW"
  echo "verify check ok: android-split-apk-armeabi-v7a"
  exit 0
fi

ART_DIR="${1:?usage: verify.sh [--check-recipe | <artifact-dir>]}"
[ -f "$ART_DIR/totaldrop-android-0.4.0-beta.1-armeabi-v7a.apk" ] || { echo "missing artifact $ART_DIR/totaldrop-android-0.4.0-beta.1-armeabi-v7a.apk" >&2; exit 1; }
[ -s "$ART_DIR/totaldrop-android-0.4.0-beta.1-armeabi-v7a.apk" ] || { echo "empty artifact $ART_DIR/totaldrop-android-0.4.0-beta.1-armeabi-v7a.apk" >&2; exit 1; }
[ -f "$ART_DIR/SHA256SUMS" ] || { echo "missing $ART_DIR/SHA256SUMS" >&2; exit 1; }
(cd "$ART_DIR" && sha256sum -c SHA256SUMS)
echo "artifact verified: $ART_DIR/totaldrop-android-0.4.0-beta.1-armeabi-v7a.apk"
