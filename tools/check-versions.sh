#!/bin/sh
# SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# Verify version and toolchain parity across the standalone totaldrop-android
# module: Flutter pubspec.yaml, rust_lib_totaldrop Cargo.toml, Android TV
# build.gradle.kts, all 10 subplatforms/*/* recipes/scripts/workflows,
# workspace core crate versions, Rust toolchain channel, and pinned Flutter SDK.
set -eu
cd "$(dirname "$0")/.."

fail() {
  echo "version check failed: $*" >&2
  exit 1
}

# 1. Canonical Android version and monotonic build number from pubspec.yaml.
pubspec_line=$(sed -n 's/^version:[[:space:]]*//p' ui-m3-expressive/pubspec.yaml | head -n 1)
[ -n "$pubspec_line" ] || fail "missing version in ui-m3-expressive/pubspec.yaml"
case "$pubspec_line" in
  *+*)
    app_ver=${pubspec_line%+*}
    app_code=${pubspec_line#*+}
    ;;
  *)
    fail "ui-m3-expressive/pubspec.yaml version '$pubspec_line' must include +<buildNumber>"
    ;;
esac
case "$app_code" in
  ''|*[!0-9]*) fail "non-numeric build number '$app_code' in ui-m3-expressive/pubspec.yaml" ;;
esac
[ "$app_code" -ge 1 ] || fail "build number must be >= 1, got '$app_code'"

# 2. rust_lib_totaldrop crate and Dart TOTALDROP_VERSION fallback follow Android SemVer.
rust_bridge_ver=$(sed -n 's/^version = "\([^"]*\)"/\1/p' ui-m3-expressive/rust/Cargo.toml | head -n 1)
[ "$rust_bridge_ver" = "$app_ver" ] || fail "ui-m3-expressive/rust/Cargo.toml version '$rust_bridge_ver' != pubspec '$app_ver'"
dart_fallback_ver=$(sed -n "s/^[[:space:]]*defaultValue: '\([^']*\)',/\1/p" ui-m3-expressive/lib/main.dart | head -n 1)
[ "$dart_fallback_ver" = "$app_ver" ] || fail "ui-m3-expressive/lib/main.dart defaultValue '$dart_fallback_ver' != pubspec '$app_ver'"

# 3. Android TV shell follows Android app SemVer and build number.
tv_ver=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' ui-android-tv/build.gradle.kts | head -n 1)
tv_code=$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)/\1/p' ui-android-tv/build.gradle.kts | head -n 1)
[ "$tv_ver" = "$app_ver" ] || fail "ui-android-tv/build.gradle.kts versionName '$tv_ver' != pubspec '$app_ver'"
[ "$tv_code" = "$app_code" ] || fail "ui-android-tv/build.gradle.kts versionCode '$tv_code' != pubspec '+$app_code'"

# 4. Workspace core version matches rust_lib_totaldrop internal crate deps.
core_ver=$(awk '
  /^\[workspace\.package\]/ { in_wp = 1; next }
  /^\[/ { in_wp = 0 }
  in_wp && /^version = "/ {
    gsub(/^version = "/, ""); gsub(/".*/, ""); print; exit
  }
' Cargo.toml)
[ -n "$core_ver" ] || fail "missing [workspace.package].version in Cargo.toml"
for crate in totaldrop-core totaldrop-proto totaldrop-l10n; do
  dep_ver=$(sed -n "s/^${crate} = { version = \"\([^\"]*\)\".*/\1/p" ui-m3-expressive/rust/Cargo.toml | head -n 1)
  [ "$dep_ver" = "$core_ver" ] || fail "ui-m3-expressive/rust/Cargo.toml $crate version '$dep_ver' != workspace '$core_ver'"
done

# 5. All 10 isolated subplatforms/*/* recipes, scripts, and pkg-*.yml workflows.
recipe_count=0
for recipe in subplatforms/*/*/RECIPE.toml; do
  [ -f "$recipe" ] || fail "no RECIPE.toml files found under subplatforms/"
  recipe_count=$((recipe_count + 1))
  unit_dir=$(dirname "$recipe")
  r_ver=$(sed -n 's/^version = "\([^"]*\)"/\1/p' "$recipe" | head -n 1)
  r_art=$(sed -n 's/^artifact_name = "\([^"]*\)"/\1/p' "$recipe" | head -n 1)
  r_wf=$(sed -n 's/^workflow = "\([^"]*\)"/\1/p' "$recipe" | head -n 1)
  [ "$r_ver" = "$app_ver" ] || fail "$recipe version '$r_ver' != '$app_ver'"
  case "$r_art" in
    *"-$app_ver-"*) ;;
    *) fail "$recipe artifact_name '$r_art' does not contain '-$app_ver-'" ;;
  esac
  grep -Fq "$r_art" "$unit_dir/build.sh" || fail "$unit_dir/build.sh missing artifact '$r_art'"
  grep -Fq "$r_art" "$unit_dir/verify.sh" || fail "$unit_dir/verify.sh missing artifact '$r_art'"
  [ -f "$r_wf" ] || fail "$recipe references missing workflow '$r_wf'"
  grep -Fq "$r_art" "$r_wf" || fail "$r_wf missing artifact '$r_art'"
done
[ "$recipe_count" -eq 10 ] || fail "expected 10 subplatform recipes, found $recipe_count"

# 6. Pinned Rust toolchain channel matches cargo-deny in ci.yml.
rust_chan=$(sed -n 's/^channel = "\([^"]*\)"/\1/p' rust-toolchain.toml | head -n 1)
[ -n "$rust_chan" ] || fail "missing channel in rust-toolchain.toml"
ci_rust=$(sed -n 's/^[[:space:]]*rust-version:[[:space:]]*"\([^"]*\)"/\1/p' .github/workflows/ci.yml | head -n 1)
[ "$ci_rust" = "$rust_chan" ] || fail "ci.yml rust-version '$ci_rust' != rust-toolchain.toml '$rust_chan'"

# 7. Pinned Flutter SDK version matches across ci.yml and android-release.yml.
flutter_versions=$(sed -n 's/^[[:space:]]*flutter-version:[[:space:]]*//p' .github/workflows/ci.yml .github/workflows/android-release.yml | tr -d '"' | sort -u)
flutter_count=$(printf '%s\n' "$flutter_versions" | sed '/^$/d' | wc -l | tr -d ' ')
[ "$flutter_count" -eq 1 ] || fail "mismatched flutter-version across workflows: $flutter_versions"
flutter_ver=$(printf '%s\n' "$flutter_versions" | head -n 1)

echo "versions check OK: android=${app_ver}+${app_code}, core=${core_ver}, rust=${rust_chan}, flutter=${flutter_ver}, subplatforms=${recipe_count}/10"
