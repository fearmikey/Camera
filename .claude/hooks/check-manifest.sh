#!/usr/bin/env bash
# Stop hook: block finishing a turn if app/build files changed but .agent_manifest.json did not.
# Only nudges once per stop cycle (stop_hook_active) to avoid loops.
input=$(cat)
if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

changed=$(git status --porcelain -- app gradle '*.gradle.kts' gradle.properties 2>/dev/null)
[ -z "$changed" ] && exit 0

if git status --porcelain -- .agent_manifest.json | grep -q .; then
  exit 0
fi

{
  echo "App/build files changed but .agent_manifest.json was not updated."
  echo "Per AGENTS.md, update .agent_manifest.json (and bump manifest_meta.last_updated) to reflect these changes:"
  printf '%s\n' "$changed"
} >&2
exit 2
