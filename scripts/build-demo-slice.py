#!/usr/bin/env python3
"""
Carves a curated demo slice out of WANDS.

The full catalogue is 43k products; rendering images for all of it is hours we do not
have and slides nobody reads. This picks a handful of visually coherent, colour-driven
queries and the products that actually compete for their first page, which is what the
demo shows.

It is a curated slice and the README says so. What is NOT modified: the queries and
the relevance judgments are Wayfair's own, untouched.
"""
import csv, collections, json, os, sys

csv.field_size_limit(10**9)
DATA = "data"
DEMO_QUERIES = [
    # The vocabulary gap. The catalogue says "sofa" in 922 titles and "couch" in 30,
    # so a shopper using their own word cannot see 97% of the relevant inventory.
    # No ranking work fixes this — the words are not there. A synonym rule is.
    "donaldson teak couch",      # 87% of relevant inventory unreachable
    "toddler couch fold out",    # 52%
    "chaise lounge couch",       # 15% — the weak case, kept for honesty
    # Attribute gaps, for the enrichment half of the agent's job.
    "velvet chaise",
    "turquoise pillows",
    "teal chair",
    "wishbone chair",
    "led nightstand",
    "farmhouse cabinet",
]
PER_QUERY = {"Exact": 40, "Partial": 20, "Irrelevant": 20}

def parse_features(blob):
    out = {}
    for pair in (blob or "").split("|"):
        i = pair.find(":")
        if i > 0:
            k = pair[:i].strip().lower()
            v = pair[i+1:].strip()
            if k and v:
                out.setdefault(k, v)
    return out

queries = {}
for r in csv.DictReader(open(f"{DATA}/query.csv"), delimiter="\t"):
    queries[r["query"].strip().lower()] = r["query_id"]

wanted_qids = {}
for q in DEMO_QUERIES:
    qid = queries.get(q)
    if qid is None:
        sys.exit(f"query not in dataset: {q!r}")
    wanted_qids[qid] = q

labels = collections.defaultdict(list)
for r in csv.DictReader(open(f"{DATA}/label.csv"), delimiter="\t"):
    if r["query_id"] in wanted_qids:
        labels[r["query_id"]].append((r["product_id"], r["label"]))

keep, qrels = set(), {}
for qid, q in wanted_qids.items():
    buckets = collections.defaultdict(list)
    for pid, lab in labels[qid]:
        buckets[lab].append(pid)
    grades = {}
    for lab, cap in PER_QUERY.items():
        for pid in buckets.get(lab, [])[:cap]:
            keep.add(pid)
            grades[pid] = {"Exact": 2, "Partial": 1}.get(lab, 0)
    qrels[qid] = {"query": q, "grades": grades}

products, missing_color, has_color = [], 0, 0
for r in csv.DictReader(open(f"{DATA}/product.csv"), delimiter="\t"):
    pid = r["product_id"]
    if pid not in keep:
        continue
    feats = parse_features(r.get("product_features", ""))
    attrs = {}
    for src, dst in (("color", "color"), ("shape", "shape"),
                     ("primarymaterial", "material"), ("material", "material"),
                     ("upholsterymaterial", "material")):
        if src in feats and dst not in attrs:
            attrs[dst] = feats[src].lower()
    if "color" in attrs: has_color += 1
    else: missing_color += 1
    products.append({
        "id": pid,
        "title": r.get("product_name", ""),
        "description": (r.get("product_description") or "")[:900],
        "category": (r.get("product_class") or "").lower(),
        "attrs": attrs,
        "image": f"w{pid}.jpg",
    })

os.makedirs(DATA, exist_ok=True)
with open(f"{DATA}/demo-slice.jsonl", "w") as f:
    for p in products:
        f.write(json.dumps(p) + "\n")
with open(f"{DATA}/demo-qrels.json", "w") as f:
    json.dump(qrels, f, indent=1)
with open(f"{DATA}/image-manifest.txt", "w") as f:
    for p in products:
        f.write(p["id"] + "\n")

total = len(products)
print(f"{total} products across {len(wanted_qids)} queries")
print(f"  color present : {has_color:>4}  ({100*has_color/total:.0f}%)")
print(f"  color MISSING : {missing_color:>4}  ({100*missing_color/total:.0f}%)  <- the agent's work")
print(f"\nwrote {DATA}/demo-slice.jsonl, {DATA}/demo-qrels.json, {DATA}/image-manifest.txt")
print(f"\nnext, on the Mac Studio:")
print(f"  python3 scripts/generate-images.py     # ~{total*2//60}-{total*3//60} min for {total} renders")
