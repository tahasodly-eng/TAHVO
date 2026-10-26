#!/usr/bin/env bash
# Usage: publish_results.sh <subdir> <file>...
# Pushes the given files to the "ci-results" branch under <subdir>/ so they can be read via git.
set -e
sub="$1"; shift
tmp=$(mktemp -d)
git config --global user.name "tahvo-ci"
git config --global user.email "ci@users.noreply.github.com"
url="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
if git ls-remote --exit-code --heads "$url" ci-results >/dev/null 2>&1; then
  git clone -q --depth 1 --branch ci-results "$url" "$tmp"
else
  git init -q "$tmp"; git -C "$tmp" checkout -q -b ci-results
  git -C "$tmp" remote add origin "$url"
fi
mkdir -p "$tmp/$sub"
for f in "$@"; do [ -e "$f" ] && cp -r "$f" "$tmp/$sub/"; done
echo "$GITHUB_SHA $(date -u +%FT%TZ) run=$GITHUB_RUN_ID" > "$tmp/$sub/RUN_INFO.txt"
git -C "$tmp" add -A
git -C "$tmp" commit -q -m "CI results: $sub @ ${GITHUB_SHA::7}" || true
for i in 1 2 3; do
  git -C "$tmp" push -q origin ci-results && break
  git -C "$tmp" pull -q --rebase origin ci-results || true
done
