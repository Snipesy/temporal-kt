#!/usr/bin/env bash
# Per-Kotlin-version compiler-plugin publish loop. Used by `publish-compiler-plugin.yml`'s
# snapshot and release phases. Encapsulates the gradle invocation + summary reporting +
# (optionally) search.maven.org idempotency check.
#
# Required env:
#   MODE                          'snapshot' | 'release'
#   TARGETS                       newline-separated Kotlin versions to publish
#   SIGNING_KEY                   GPG armored private key
#   SIGNING_PASSWORD              GPG passphrase
#   OSSRH_USERNAME                Maven Central username
#   OSSRH_TOKEN                   Maven Central token
#   GITHUB_STEP_SUMMARY           (optional) path for summary markdown
#
# Behavior:
#   - SNAPSHOT mode: publishes every entry; skips idempotency check entirely (snapshots
#     replace by nature, search.maven.org doesn't index them anyway, and `central.sonatype.com/repository/maven-snapshots/`
#     accepts re-uploads of the same coord).
#   - RELEASE mode: pre-checks search.maven.org for each coord, skips if already published.
#     Post-failure 30s re-check handles search-index-stale races within the run.
#
# Failures collected (not aborted) so one bad version doesn't block the rest. Exits 1 if
# any version failed.

set -uo pipefail

if [ -z "${MODE:-}" ] || [ -z "${TARGETS:-}" ]; then
  echo "::error::MODE and TARGETS env vars required"
  exit 2
fi

case "$MODE" in
  snapshot|release) ;;
  *) echo "::error::MODE must be 'snapshot' or 'release', got '$MODE'"; exit 2 ;;
esac

LIB=$(grep '^version=' gradle.properties | cut -d= -f2)
GROUP="com.surrealdev.temporal"
ARTIFACT="compiler-plugin"

echo "[publish-loop] mode=$MODE library version=$LIB"

# Pre-check Maven Central via the search API. Returns 1 if the coord is already published,
# 0 otherwise. Snapshots are NOT indexed; this is only meaningful in release mode.
exists_on_central() {
  local KV=$1
  local COORD="${KV}-${LIB}"
  local URL="https://search.maven.org/solrsearch/select?q=g:${GROUP}+AND+a:${ARTIFACT}+AND+v:${COORD}&rows=1&wt=json"
  local RESP
  RESP=$(curl -s --max-time 10 "$URL" || echo '{"response":{"numFound":0}}')
  local FOUND
  FOUND=$(echo "$RESP" | jq -r '.response.numFound // 0')
  [ "$FOUND" -gt 0 ]
}

published=()
skipped=()
failed=()

while IFS= read -r KV; do
  [ -z "$KV" ] && continue

  # Release mode only: skip if already on Central. Snapshot mode always proceeds.
  if [ "$MODE" = "release" ] && exists_on_central "$KV"; then
    echo "::notice::Skipping ${KV}-${LIB} — already on Maven Central"
    skipped+=("$KV")
    sleep 1
    continue
  fi

  echo "::group::Publishing :compiler-plugin@${KV}-${LIB} (mode=${MODE})"
  if ./gradlew :compiler-plugin:publishToMavenCentral \
      -Pkotlin.compiler="$KV" \
      -Pkotlin.lang="$KV" \
      -PskipNativeBuild=true \
      -PsigningInMemoryKey="$SIGNING_KEY" \
      -PsigningInMemoryKeyPassword="$SIGNING_PASSWORD" \
      -PmavenCentralUsername="$OSSRH_USERNAME" \
      -PmavenCentralPassword="$OSSRH_TOKEN" \
      --no-configuration-cache --no-daemon; then
    published+=("$KV")
    echo "::endgroup::"
    sleep 1
    continue
  fi
  echo "::endgroup::"

  # Release mode only: post-failure recheck handles transient gradle blips that raced
  # through Central's validation despite the Gradle task reporting failure. Snapshot
  # mode skips this — re-uploading a snapshot is harmless, no need to second-guess.
  if [ "$MODE" = "release" ]; then
    echo "::warning::Publish reported failure for ${KV}; re-checking Maven Central in 30s…"
    sleep 30
    if exists_on_central "$KV"; then
      echo "::notice::${KV}-${LIB} found on Central post-failure → marking as success"
      skipped+=("$KV")
      sleep 1
      continue
    fi
  fi

  failed+=("$KV")
  sleep 1
done <<< "$TARGETS"

# Job step summary as markdown.
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "## ${MODE^} publish summary"
    echo "| Kotlin | Result |"
    echo "|---|---|"
    for v in "${published[@]:-}"; do [ -n "$v" ] && echo "| ${v}-${LIB} | ✅ published |"; done
    for v in "${skipped[@]:-}";   do [ -n "$v" ] && echo "| ${v}-${LIB} | ⏭️ skipped (already on Central) |"; done
    for v in "${failed[@]:-}";    do [ -n "$v" ] && echo "| ${v}-${LIB} | ❌ failed |"; done
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [ ${#failed[@]} -gt 0 ] && [ -n "${failed[0]:-}" ]; then
  echo "::error::Failed to publish: ${failed[*]}"
  exit 1
fi
echo "[publish-loop] mode=$MODE done. published=${#published[@]} skipped=${#skipped[@]}"
