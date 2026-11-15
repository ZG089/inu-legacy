#!/usr/bin/env bash
# Posts ONE Telegram message for a legacy.yml run: plugin and pluginless status, the commit and the
# run (or the release, once published), and the commits the run covers in a collapsed quote.
#
# env, set by the notify job:
#   TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID   delivery (no token: skip quietly)
#   GH_TOKEN                               reads the run's jobs and the release list
#   BASE_SHA                               commits since this sha; empty = see commit_list
#   RELEASE_REQUESTED, RELEASE_RESULT, RELEASE_TAG
# DRY_RUN=1 prints the message instead of sending it.
set -euo pipefail
export LC_ALL=C.UTF-8

repo=${GITHUB_REPOSITORY:?}
sha=${GITHUB_SHA:?}
run_id=${GITHUB_RUN_ID:?}
run_number=${GITHUB_RUN_NUMBER:-0}
url=${GITHUB_SERVER_URL:-https://github.com}/$repo
release_requested=${RELEASE_REQUESTED:-false}
release_result=${RELEASE_RESULT:-skipped}
release_tag=${RELEASE_TAG:-}
export GH_REPO=$repo

# Telegram caps a message at 4096 characters; 25 lines of at most 80 characters stay far below it
max_commits=25
max_subject=80

html() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }

jobs=$(gh api "repos/$repo/actions/runs/$run_id/jobs?per_page=100" \
  --jq '.jobs[] | [.name, (.conclusion // .status)] | @tsv' 2>/dev/null || true)

status() { # job name -> "<emoji> <word>"
  case $(awk -F'\t' -v n="$1" '$1 == n { print $2; exit }' <<<"$jobs") in
    success) echo '✅ success' ;;
    failure | timed_out) echo '❌ failed' ;;
    cancelled) echo '⏹ cancelled' ;;
    skipped) echo '⏭ skipped' ;;
    *) echo '❔ unknown' ;;
  esac
}

plugin=$(status 'build (full)')
pluginless=$(status 'build (pluginless)')

# --- headline, link, overall state
if [ "$release_requested" = true ]; then
  case $release_result in
    success) release_line='✅ published' ;;
    failure) release_line='❌ failed' ;;
    cancelled) release_line='⏹ cancelled' ;;
    *) release_line='⏭ not run' ;;
  esac
  if [ "$release_result" = success ] && [ -n "$release_tag" ]; then
    title="Release $release_tag"
    link="Release: <a href=\"$url/releases/tag/$release_tag\">$(html <<<"$release_tag")</a>"
  else
    title='Release not published'
    link="Workflow run: <a href=\"$url/actions/runs/$run_id\">#$run_number</a>"
  fi
else
  release_line=''
  title="CI build #$run_number"
  link="Workflow run: <a href=\"$url/actions/runs/$run_id\">#$run_number</a>"
fi

all="$plugin $pluginless $release_line"
if [[ $all == *❌* ]]; then
  state='❌'
elif [[ $all == *❔* || $all == *⏹* || $all == *⏭* ]]; then
  state='⚠️'
else
  state='✅'
fi

# --- commits: BASE_SHA..head without merge commits (a sync's merged upstream commits are listed, the
# merge commit is not). Without a usable base a release run lists what came since the previous
# release, any other run just the commit it built.
valid_sha() { [[ $1 =~ ^[0-9a-f]{40}$ && ! $1 =~ ^0+$ ]] && git cat-file -e "$1^{commit}" 2>/dev/null; }
commit_list() { git log --no-merges --abbrev=8 --format='%h%x09%s' "$@"; }

commits=''
if valid_sha "${BASE_SHA:-}"; then
  commits=$(commit_list "$BASE_SHA..$sha")
fi
if [ -z "$commits" ] && [ "$release_requested" = true ]; then
  prev=$({ gh release list --limit 5 --json tagName --jq '.[].tagName' 2>/dev/null || true; } |
    { grep -vxF "$release_tag" || true; } | head -1 || true)
  if [ -n "$prev" ] && prev_sha=$(git rev-parse -q --verify "refs/tags/$prev^{commit}"); then
    commits=$(commit_list "$prev_sha..$sha")
  fi
fi
if [ -z "$commits" ]; then
  commits=$(git log -n 1 --abbrev=8 --format='%h%x09%s' "$sha")
fi

total=$(grep -c . <<<"$commits" || true)
lines=''
while IFS=$'\t' read -r h s; do
  [ ${#s} -le $max_subject ] || s="${s:0:$((max_subject - 1))}…"
  lines+="- $h • $(html <<<"$s")"$'\n'
done < <(head -n $max_commits <<<"$commits")
[ "$total" -le $max_commits ] || lines+="- … and $((total - max_commits)) more"$'\n'

# --- message
text="$state <b>$(html <<<"$title")</b>
armeabi-v7a • $repo

<b>Plugin</b> (full): $plugin
<b>Pluginless</b>: $pluginless"
[ -z "$release_line" ] || text+="
<b>Publish</b>: $release_line"
text+="

Commit: <a href=\"$url/commit/$sha\">${sha:0:8}</a> on <code>$(html <<<"${GITHUB_REF_NAME:-main}")</code>
$link

<b>Merged commits ($total)</b>
<blockquote expandable>${lines%$'\n'}</blockquote>"

if [ -n "${DRY_RUN:-}" ]; then
  printf '%s\n' "$text"
  exit 0
fi
if [ -z "${TELEGRAM_BOT_TOKEN:-}" ]; then
  echo '::notice::TELEGRAM_BOT_TOKEN is not set, skipping the Telegram post'
  exit 0
fi

response=$(curl -sS --max-time 30 -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
  --data-urlencode "chat_id=${TELEGRAM_CHAT_ID:?TELEGRAM_CHAT_ID is not set}" \
  --data-urlencode 'parse_mode=HTML' \
  --data-urlencode 'disable_web_page_preview=true' \
  --data-urlencode "text=$text") || { echo '::error::could not reach the Telegram API'; exit 1; }
if ! jq -e '.ok == true' >/dev/null 2>&1 <<<"$response"; then
  reason=$(jq -r '.description // "unparsable response"' 2>/dev/null <<<"$response" || true)
  echo "::error::Telegram refused the message: ${reason:-unparsable response}"
  exit 1
fi
echo 'posted to Telegram'
