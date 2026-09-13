#!/usr/bin/env bash
# Put the demo back to its zero state: no rules, empty timeline, panels identical,
# and no pull requests left open from the last run.
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-baseline}" in
  baseline)
    echo '{}' > data/synonyms.json
    rm -f data/run-log.jsonl data/processed.txt
    rm -rf data/proposals
    echo "  rules     : none"
    echo "  timeline  : empty"
    echo "  proposals : cleared"

    repo=$(grep -E '^GITHUB_REPO=' .env | cut -d= -f2)
    if [ -n "${repo:-}" ] && command -v gh >/dev/null; then
      open=$(gh api "repos/$repo/pulls?state=open" --jq '.[].number' 2>/dev/null || true)
      for n in $open; do
        gh api "repos/$repo/pulls/$n" --method PATCH -f state=closed >/dev/null
        echo "  closed    : PR #$n"
      done
    fi
    echo
    echo "  restart the processes so they pick it up:  ./scripts/demo.sh restart"
    ;;
  *) echo "usage: $0 baseline"; exit 1 ;;
esac
