#!/usr/bin/env bash
# Publish site/ to GitHub Pages (branch gh-pages, served from its root).
#
#   tools/publish-site.sh          split site/ into gh-pages and push
#   tools/publish-site.sh --dry    build the branch locally, do not push
#
# The site is plain HTML + WebP with no build step, so publishing is a subtree split: main keeps the
# sources under site/, the published branch holds exactly what the browser fetches.
set -euo pipefail
cd "$(dirname "$0")/.."

dry=0
[ "${1:-}" = "--dry" ] && dry=1

[ -f site/index.html ] || { echo "site/index.html missing" >&2; exit 1; }

# Jekyll would skip files starting with an underscore and rewrite links; nothing here needs it.
touch site/.nojekyll

if [ -n "$(git status --porcelain site)" ]; then
  echo "site/ has uncommitted changes — commit them first so the published page matches a commit." >&2
  exit 1
fi

git branch -D gh-pages >/dev/null 2>&1 || true
git subtree split --prefix site -b gh-pages >/dev/null
echo "gh-pages rebuilt at $(git rev-parse --short gh-pages)"

if [ "$dry" = 1 ]; then
  echo "dry run: not pushed"
  exit 0
fi

git push -f origin gh-pages
echo
echo "Published. First time only, enable Pages once:"
echo "  Settings → Pages → Source: Deploy from a branch → gh-pages / (root)"
echo "URL: https://$(git remote get-url origin | sed -E 's#.*github.com[:/]([^/]+)/([^/.]+).*#\1.github.io/\2/#')"
