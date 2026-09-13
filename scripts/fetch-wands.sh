#!/usr/bin/env bash
# Pulls the Wayfair WANDS relevance dataset into ./data
# ~43k products, 480 queries, ~233k human relevance judgments.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="https://raw.githubusercontent.com/wayfair/WANDS/main/dataset"
for f in product.csv query.csv label.csv; do
  echo "fetching $f ..."
  curl -fsSL "$BASE/$f" -o "data/$f"
done
wc -l data/product.csv data/query.csv data/label.csv
echo
echo "Verify the column headers match WandsLoader's expectations:"
head -1 data/product.csv
head -1 data/label.csv
