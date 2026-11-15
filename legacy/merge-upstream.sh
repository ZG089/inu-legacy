#!/usr/bin/env bash
# Merges an upstream ref into the checked-out fork branch and settles the conflicts this fork's layout
# makes unavoidable, so a sync only stops for a conflict a human has to decide.
#
#   legacy/merge-upstream.sh <ref>      clean checkout, <ref> already fetched, git identity set
#
# Who owns what:
#   patches/, patches-native/   upstream. The fork adds patches of its own but never edits upstream's, so a
#                               conflict, a deletion upstream made or drift is settled for upstream.
#   series                      upstream's list plus the patches only the fork has.
#   .github/workflows/          the fork. The workflow token cannot push changes to workflow files, and
#                               upstream's workflows must not run here.
#   README.md                   the fork, when both sides changed it.
# Any other conflict stops the sync and leaves the checkout clean.
set -euo pipefail

ref=${1:?usage: merge-upstream.sh <upstream ref>}
git diff --quiet HEAD || { echo '::error::the checkout has uncommitted changes'; exit 2; }

in_ref() { git cat-file -e "$ref:$1" 2>/dev/null; }

base=$(git merge-base HEAD "$ref")

# Series entries only the fork has: not in upstream now, and never upstream's (a patch upstream
# dropped since the last merge is in the base tree and must go).
fork_only=$(git show HEAD:series | while IFS= read -r entry; do
  [ -n "$entry" ] || continue
  in_ref "patches/$entry" || git cat-file -e "$base:patches/$entry" 2>/dev/null || printf '%s\n' "$entry"
done)

git merge --no-commit --no-ff "$ref" || true
if [ ! -f "$(git rev-parse --git-dir)/MERGE_HEAD" ]; then
  echo "already up to date with $ref"
  exit 0
fi

# workflows: the fork's
for f in $({ git diff --name-only HEAD -- .github/workflows; git ls-files -u -- .github/workflows | cut -f2; } | sort -u); do
  if git cat-file -e "HEAD:$f" 2>/dev/null; then
    git checkout HEAD -- "$f"
  else
    git rm -qf -- "$f"
  fi
done

# patches: upstream's. Files upstream deleted that the fork touched are conflicts with nothing to take.
for f in $(git ls-files -u -- patches patches-native | cut -f2 | sort -u); do
  in_ref "$f" || git rm -qf -- "$f"
done
overridden=$(git diff --name-only "$base" HEAD -- patches patches-native | while IFS= read -r f; do
  ! in_ref "$f" || printf '%s\n' "$f"
done)
git checkout "$ref" -- $(git ls-tree -d --name-only "$ref" patches patches-native)
if [ -n "$overridden" ]; then
  echo "::warning::reset $(grep -c . <<<"$overridden") fork edit(s) to upstream patches, for example: $(head -3 <<<"$overridden" | paste -sd' ')"
fi

# series: upstream's, then the fork's own
{ git show "$ref:series" | sed -e '$a\'; [ -z "$fork_only" ] || printf '%s\n' "$fork_only"; } > series
git add series

# README: the fork's when both sides changed it
if git ls-files -u -- README.md | grep -q .; then
  git checkout --ours -- README.md
  git add README.md
fi

unresolved=$(git diff --name-only --diff-filter=U)
if [ -n "$unresolved" ]; then
  echo "::error::conflicts that need a human: $(paste -sd' ' <<<"$unresolved")"
  git merge --abort
  exit 1
fi

commits=$(git rev-list --count HEAD.."$ref")
git commit --no-verify -q -m "Merge upstream/main ($commits commits)"
echo "merged $commits commit(s) from $ref"
