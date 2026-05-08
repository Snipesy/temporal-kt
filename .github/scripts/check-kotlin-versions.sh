#!/usr/bin/env bash
# Polls Maven Central for new Kotlin compiler releases (Beta / RC / final) and prints any not
# present in .github/kotlin-versions.json.
#
# Usage: ./.github/scripts/check-kotlin-versions.sh
# Output: newline-separated list of unsupported versions on stdout.
# Exit 0 always (callers diff stdout to decide PR-bot action). Logs go to stderr.
#
# SCOPE NOTE — `-ij` versions are NOT covered by this script.
# IDE-bundled Kotlin builds (e.g. `2.4.0-ij261-32`) live inside JetBrains' internal build system
# and are not published to any queryable public Maven repo. These have to be added to
# kotlin-versions.json manually when a new IDEA EAP ships — see compiler-plugin/build.gradle.kts
# for the KEFS forge-prefix mechanism. The bot here only catches public releases on Central
# (Kotlin 2.4.0-Beta1 → Beta2 → RC1 → 2.4.0 final etc.) which still drives most CSM template
# work, since IDEA bundles always pin to a specific Maven Central release as their ABI baseline.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
JSON="${REPO_ROOT}/.github/kotlin-versions.json"

METADATA_URL="https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml"

echo "[check-kotlin-versions] Polling $METADATA_URL" >&2

XML=$(curl -sfL --max-time 30 "$METADATA_URL" || true)
if [ -z "$XML" ] || ! echo "$XML" | grep -q '<version>'; then
  echo "[check-kotlin-versions] Failed to fetch usable metadata; emitting empty result." >&2
  exit 0
fi

# We track only Kotlin minors >= our pinned default's minor. Filter out anything that's clearly
# not relevant: 1.x.y, dev builds, milestone builds, and snapshots. Keep Beta / RC / stable for
# 2.3.x and newer.
DEFAULT=$(jq -r '.default' "$JSON")

REMOTE=$(echo "$XML" \
  | grep -oE '<version>[^<]+</version>' \
  | sed -E 's|<version>([^<]+)</version>|\1|' \
  | { grep -E '^2\.[0-9]+\.[0-9]+(-(Beta|RC)[0-9]*)?$' || true; } \
  | { grep -vE '^1\.|-dev-|-M[0-9]|SNAPSHOT' || true; } \
  | tail -50 \
  | sort -u)

KNOWN=$(jq -r '.versions[].kotlin' "$JSON" | sort -u)

REMOTE_COUNT=$([ -n "$REMOTE" ] && echo "$REMOTE" | wc -l | tr -d ' ' || echo 0)
KNOWN_COUNT=$([ -n "$KNOWN" ] && echo "$KNOWN" | wc -l | tr -d ' ' || echo 0)
echo "[check-kotlin-versions] Found $REMOTE_COUNT recent 2.x versions on Central, $KNOWN_COUNT in JSON. Default: $DEFAULT" >&2

# We only care about versions strictly NEWER than the highest minor we already track.
# E.g. JSON has 2.3.21 + 2.4.0-Beta2 → highest minor is 2.4 → emit only 2.4.x versions we don't
# already have. This avoids back-publishing for old Kotlin minors that we don't intend to support.
HIGHEST_MAJMIN=$(echo "$KNOWN" | sed -E 's/^([0-9]+\.[0-9]+).*/\1/' | sort -V | tail -1)
HIGHEST_MAJ=$(echo "$HIGHEST_MAJMIN" | cut -d. -f1)
HIGHEST_MIN=$(echo "$HIGHEST_MAJMIN" | cut -d. -f2)

comm -23 <(echo "$REMOTE") <(echo "$KNOWN") | while read -r V; do
  [ -z "$V" ] && continue
  V_MAJ=$(echo "$V" | cut -d. -f1)
  V_MIN=$(echo "$V" | cut -d. -f2)
  if [ "$V_MAJ" -gt "$HIGHEST_MAJ" ] || [ "$V_MAJ" -eq "$HIGHEST_MAJ" -a "$V_MIN" -ge "$HIGHEST_MIN" ]; then
    echo "$V"
  fi
done
