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

status() { # job name -> status word
  case $(awk -F'\t' -v n="$1" '$1 == n { print $2; exit }' <<<"$jobs") in
    success) echo 'success' ;;
    failure | timed_out) echo 'failed' ;;
    cancelled) echo 'cancelled' ;;
    skipped) echo 'skipped' ;;
    *) echo 'unknown' ;;
  esac
}

plugin=$(status 'build (full)')
pluginless=$(status 'build (pluginless)')

api_url=${TELEGRAM_API_URL:-https://api.telegram.org}

# --- headline, links, and overall state
if [ -n "$release_tag" ]; then
  title="Release $release_tag"
  title_url="$url/releases/tag/$release_tag"
  release_url="$url/releases/tag/$release_tag"
  variant_tag="#Release"
  btn1_text="🏷️ Release Tag"
  btn1_url="$release_url"
else
  title="CI build #$run_number"
  title_url="$url/actions/runs/$run_id"
  release_url="$url/releases"
  variant_tag="#CI"
  btn1_text="🔍 View CI run"
  btn1_url="$url/actions/runs/$run_id"
fi
source_url="$url/tree/$sha"

if [ "$release_requested" = true ]; then
  case $release_result in
    success) release_line='published' ;;
    failure) release_line='failed' ;;
    cancelled) release_line='cancelled' ;;
    *) release_line='not run' ;;
  esac
else
  release_line=''
fi

all="$plugin $pluginless $release_line"
if [[ $all == *❌* || $all == *failed* ]]; then
  state='❌'
elif [[ $all == *❔* || $all == *⏹* || $all == *⏭* || $all == *cancelled* || $all == *not\ run* ]]; then
  state='⚠️'
elif [ "$release_requested" = true ] || [ -n "$release_tag" ]; then
  state='🚀'
else
  state='✨'
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

# --- message & buttons
text="$state <b><a href=\"$title_url\">$(html <<<"$title")</a></b>

<b>New commits ($total)</b>
<blockquote expandable>${lines%$'\n'}</blockquote>

$variant_tag"

# Try upstream-style MTProto upload via @mtcute/node first (supports files > 50 MB)
if command -v pnpm >/dev/null 2>&1 && [ -f "legacy/telegram-upload.ts" ] && [ -z "${DRY_RUN:-}" ]; then
  echo "Attempting upload via @mtcute/node (MTProto, upstream style)..."
  if pnpm exec tsx legacy/telegram-upload.ts; then
    echo "Posted to Telegram via MTProto"
    exit 0
  fi
  echo "::warning::MTProto upload failed, falling back to HTTP Bot API..."
fi

reply_markup=$(jq -nc \
  --arg b1_text "$btn1_text" \
  --arg b1_url "$btn1_url" \
  --arg s_url "$source_url" \
  '{inline_keyboard: [[{text: $b1_text, url: $b1_url}, {text: "📦 Source Code", url: $s_url}]]}')

apk_dir="${APK_DIR:-apks}"
apks=()
if [ -d "$apk_dir/legacy-signed" ] && [ -n "$(find "$apk_dir/legacy-signed" -name '*.apk' 2>/dev/null)" ]; then
  mapfile -t apks < <(find "$apk_dir/legacy-signed" -name '*.apk' | sort)
elif [ -d "$apk_dir" ]; then
  mapfile -t apks < <(find "$apk_dir" -name '*.apk' | sort)
fi

if [ -n "${DRY_RUN:-}" ]; then
  printf '%s\n\n' "$text"
  echo "Inline keyboard: $reply_markup"
  echo "Found ${#apks[@]} APK(s) to attach:"
  for apk in "${apks[@]}"; do
    echo "  - $apk"
  done
  exit 0
fi

if [ -z "${TELEGRAM_BOT_TOKEN:-}" ]; then
  echo '::notice::TELEGRAM_BOT_TOKEN is not set, skipping the Telegram post'
  exit 0
fi

response=$(curl -sS --max-time 30 -X POST "${api_url}/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
  --data-urlencode "chat_id=${TELEGRAM_CHAT_ID:?TELEGRAM_CHAT_ID is not set}" \
  --data-urlencode 'parse_mode=HTML' \
  --data-urlencode 'disable_web_page_preview=true' \
  --data-urlencode "text=$text" \
  --data-urlencode "reply_markup=$reply_markup") || { echo '::error::could not reach the Telegram API'; exit 1; }

if ! jq -e '.ok == true' >/dev/null 2>&1 <<<"$response"; then
  reason=$(jq -r '.description // "unparsable response"' 2>/dev/null <<<"$response" || true)
  echo "::error::Telegram refused the message: ${reason:-unparsable response}"
  exit 1
fi
echo 'posted message to Telegram'

msg_id=$(jq -r '.result.message_id' <<<"$response")

for apk in "${apks[@]}"; do
  [ -f "$apk" ] || continue
  apk_name=$(basename "$apk")
  size=$(stat -c%s "$apk" 2>/dev/null || wc -c <"$apk")
  mb=$(( (size + 1048575) / 1048576 ))
  echo "Sending document: $apk_name ($mb MB)..."

  doc_resp=$(curl -sS --max-time 300 -X POST "${api_url}/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
    -F "chat_id=${TELEGRAM_CHAT_ID}" \
    -F "reply_to_message_id=${msg_id}" \
    -F "document=@${apk}" \
    -F "caption=📦 <code>${apk_name}</code>" \
    -F "parse_mode=HTML") || true

  if jq -e '.ok == true' >/dev/null 2>&1 <<<"$doc_resp"; then
    echo "Uploaded $apk_name successfully"
  else
    desc=$(jq -r '.description // "upload failed"' 2>/dev/null <<<"$doc_resp" || true)
    echo "::warning::Could not attach $apk_name to Telegram: $desc"

    # If Telegram Bot API rejected upload (e.g. 50 MB limit exceeded on api.telegram.org),
    # post a reply message with direct download link so users can still download it:
    if [ -n "$release_tag" ]; then
      dl_url="$url/releases/download/$release_tag/$apk_name"
      dl_text="📦 <b>$(html <<<"$apk_name")</b> (${mb} MB)
⚠️ <i>Exceeds Telegram Bot 50 MB limit</i>
📥 <a href=\"$dl_url\">Direct Download</a>"
      curl -sS --max-time 30 -X POST "${api_url}/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
        --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" \
        --data-urlencode "reply_to_message_id=${msg_id}" \
        --data-urlencode 'parse_mode=HTML' \
        --data-urlencode 'disable_web_page_preview=true' \
        --data-urlencode "text=$dl_text" || true
    fi
  fi
done
