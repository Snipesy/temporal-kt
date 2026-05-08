#!/usr/bin/env bash
# Polls JetBrains for recent IDEA Ultimate builds and extracts each one's bundled
# `-ij`-tagged Kotlin compiler version. Prints any not present in `.github/kotlin-versions.json`.
#
# Usage: ./.github/scripts/check-idea-versions.sh [majorVersions...]
#   majorVersions: optional space-separated list (e.g. "2026.1 2026.2"). Default: latest 2 majors.
#
# Output: newline-separated list of unsupported `-ij` Kotlin versions on stdout.
# Exit 0 always (callers diff stdout to decide PR-bot action). Logs go to stderr.
#
# Discovery mechanism (per KEFS PLUGIN_AUTHORS.md):
# 1. JetBrains releases API enumerates IDEA build numbers per major version.
# 2. For each build, github.com/JetBrains/intellij-community at tag `idea/<build>` exposes
#    `plugins/kotlin/util/project-model-updater/resources/model.properties` whose
#    `kotlincVersion` is the canonical `-ij`-tagged Kotlin compiler version that build bundles.
# 3. Diff against our committed JSON. New entries → PR-bot opens a PR.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
JSON="${REPO_ROOT}/.github/kotlin-versions.json"

# Default: poll the two most-recent distinct IDEA majors. Override via positional args.
if [ $# -eq 0 ]; then
  MAJORS=$(curl -sfL --max-time 30 "https://data.services.jetbrains.com/products/releases?code=IIU" \
    | python3 -c '
import sys, json
d = json.load(sys.stdin)
mvs = list(dict.fromkeys(r["majorVersion"] for r in d.get("IIU", [])))
print("\n".join(mvs[:2]))
' 2>/dev/null || echo "2026.1")
else
  MAJORS="$*"
fi

echo "[check-idea-versions] Targeting IDEA majors: $MAJORS" >&2

ALL_BUILDS=""
for MAJOR in $MAJORS; do
  RESPONSE=$(curl -sfL --max-time 30 \
    "https://data.services.jetbrains.com/products/releases?code=IIU&type=release,eap&majorVersion=${MAJOR}" \
    || true)
  if [ -z "$RESPONSE" ]; then
    echo "[check-idea-versions] Failed to query releases for $MAJOR; skipping." >&2
    continue
  fi
  BUILDS=$(echo "$RESPONSE" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); [print(r["build"]) for r in d.get("IIU",[])]' \
    2>/dev/null || true)
  ALL_BUILDS="$ALL_BUILDS"$'\n'"$BUILDS"
done
ALL_BUILDS=$(echo "$ALL_BUILDS" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -u)

BUILD_COUNT=$(echo "$ALL_BUILDS" | grep -c '^' || echo 0)
echo "[check-idea-versions] Querying $BUILD_COUNT IDEA build(s) for kotlincVersion." >&2

# For each build, fetch model.properties from the tagged intellij-community ref.
DISCOVERED=""
for BUILD in $ALL_BUILDS; do
  URL="https://raw.githubusercontent.com/JetBrains/intellij-community/refs/tags/idea/${BUILD}/plugins/kotlin/util/project-model-updater/resources/model.properties"
  PROPS=$(curl -sfL --max-time 15 "$URL" 2>/dev/null || true)
  if [ -z "$PROPS" ]; then
    echo "[check-idea-versions]   $BUILD: model.properties not found at tag" >&2
    continue
  fi
  KV=$(echo "$PROPS" | grep -E '^kotlincVersion=' | cut -d= -f2)
  if [ -z "$KV" ]; then
    echo "[check-idea-versions]   $BUILD: kotlincVersion not set" >&2
    continue
  fi
  echo "[check-idea-versions]   $BUILD: kotlincVersion=$KV" >&2
  DISCOVERED="$DISCOVERED"$'\n'"$KV"
done

DISCOVERED=$(echo "$DISCOVERED" | { grep -E '^[0-9]+\.[0-9]+\.[0-9]+-ij[0-9]+-[0-9]+$' || true; } | sort -u)
KNOWN=$(jq -r '.versions[].kotlin' "$JSON" | sort -u)

# Print unsupported -ij versions, one per line on stdout.
comm -23 <(echo "$DISCOVERED") <(echo "$KNOWN") || true
