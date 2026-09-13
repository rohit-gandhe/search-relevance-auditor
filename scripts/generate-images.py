#!/usr/bin/env python3
"""
Renders catalogue illustrations for WANDS products on Apple Silicon.

WANDS ships no image URLs, and Wayfair's own site is behind bot detection, so these
are RENDERS, not photographs. Label them that way in the demo. They exist so a viewer
can see what a result is; the ranking claim itself rests on the dataset's 233k human
relevance judgments, not on the pictures.

SETUP (Mac Studio)
------------------
Python 3.14 has no torch wheels yet. Use 3.11 or 3.12:

    brew install python@3.12
    /opt/homebrew/bin/python3.12 -m venv ~/.venvs/imggen
    source ~/.venvs/imggen/bin/activate
    pip install --upgrade pip
    pip install torch torchvision diffusers transformers accelerate safetensors pillow

RUN
---
    python3 scripts/generate-images.py --manifest data/image-manifest.txt --out data/images

ALTERNATIVE BACKENDS
--------------------
Already running AUTOMATIC1111 or Forge? Skip the pip install entirely and drive its
API instead — start the webui with `--api --listen`, then:

    python3 scripts/generate-images.py --backend a1111 --url http://<host>:7860

Draw Things is the fastest Mac-native option for finding a prompt interactively; once
you like one, come back and batch it here. A1111 on Apple Silicon works but is the
slowest of the three and means keeping a server alive for the whole run.

Resumable: anything already on disk is skipped, so ctrl-c and rerun freely.
On a 36GB Mac Studio, SDXL-Turbo at 512px / 2 steps runs roughly 1.5-3s an image.
"""
import argparse, csv, os, sys, time

def build_prompt(name, klass, feats):
    bits = [name.strip()]
    if klass and klass.lower() not in name.lower():
        bits.append(klass.strip())
    # Feed back any colour/material the catalogue DOES have, so the render matches the
    # record. Products missing those are exactly the ones the agent later enriches —
    # their renders stay generic, which is honest.
    for key in ("color", "primarymaterial", "material", "upholsterymaterial", "shape"):
        v = feats.get(key)
        if v:
            bits.append(v.strip())
    subject = ", ".join(b for b in bits if b)[:220]
    return (f"product catalogue photograph of {subject}, "
            "isolated on a plain white studio background, centered, "
            "soft even lighting, sharp focus, no text, no watermark")

def parse_features(blob):
    out = {}
    for pair in (blob or "").split("|"):
        i = pair.find(":")
        if i > 0:
            out.setdefault(pair[:i].strip().lower(), pair[i+1:].strip())
    return out

def load_products(path):
    csv.field_size_limit(10**9)
    rows = {}
    with open(path, newline="") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            rows[r["product_id"]] = r
    return rows

def diffusers_renderer(args):
    import torch
    from diffusers import AutoPipelineForText2Image

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    if device == "cpu":
        print("WARNING: MPS unavailable — this will be extremely slow", file=sys.stderr)
    print(f"loading {args.model} on {device} (first run downloads ~7GB) ...", flush=True)

    pipe = AutoPipelineForText2Image.from_pretrained(
        args.model, torch_dtype=torch.float16, variant="fp16").to(device)
    pipe.set_progress_bar_config(disable=True)

    def render(prompt, path):
        # SDXL-Turbo is distilled for 1-4 steps; guidance must be 0.
        image = pipe(prompt=prompt, num_inference_steps=args.steps, guidance_scale=0.0,
                     height=args.size, width=args.size).images[0]
        image.save(path, "JPEG", quality=88)
    return render


def a1111_renderer(args):
    """Drives an already-running AUTOMATIC1111 / Forge instance started with --api."""
    import base64, json, urllib.request

    endpoint = args.url.rstrip("/") + "/sdapi/v1/txt2img"
    try:
        urllib.request.urlopen(args.url.rstrip("/") + "/sdapi/v1/options", timeout=10)
    except Exception as e:
        sys.exit(f"cannot reach the webui at {args.url}: {e}\n"
                 f"start it with:  ./webui.sh --api --listen")
    print(f"using AUTOMATIC1111 at {args.url}", flush=True)

    def render(prompt, path):
        payload = {
            "prompt": prompt,
            "negative_prompt": "text, watermark, logo, person, hands, blurry, collage",
            "steps": max(args.steps, 6),   # a full model, not a turbo distillation
            "cfg_scale": 6.5,
            "width": args.size, "height": args.size,
            "sampler_name": "DPM++ 2M Karras",
        }
        req = urllib.request.Request(
            endpoint, data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=180) as r:
            body = json.load(r)
        with open(path, "wb") as f:
            f.write(base64.b64decode(body["images"][0]))
    return render


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default="data/image-manifest.txt",
                    help="one product_id per line; written by `gradlew run --args=image-manifest`")
    ap.add_argument("--products", default="data/product.csv")
    ap.add_argument("--out", default="data/images")
    ap.add_argument("--model", default="stabilityai/sdxl-turbo")
    ap.add_argument("--steps", type=int, default=2)
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--backend", choices=("diffusers", "a1111"), default="diffusers")
    ap.add_argument("--url", default="http://127.0.0.1:7860",
                    help="AUTOMATIC1111 / Forge base url (needs --api)")
    args = ap.parse_args()

    if not os.path.exists(args.manifest):
        sys.exit(f"no manifest at {args.manifest} — run:\n"
                 f"  ./gradlew run --args=\"image-manifest\"")

    ids = [l.strip() for l in open(args.manifest) if l.strip()]
    if args.limit:
        ids = ids[:args.limit]
    products = load_products(args.products)
    os.makedirs(args.out, exist_ok=True)

    todo = [i for i in ids
            if not os.path.exists(os.path.join(args.out, f"w{i}.jpg")) and i in products]
    print(f"{len(ids)} requested · {len(ids)-len(todo)} already on disk · {len(todo)} to render")
    if not todo:
        return

    if args.backend == "a1111":
        render = a1111_renderer(args)
    else:
        render = diffusers_renderer(args)

    start = time.time()
    for n, pid in enumerate(todo, 1):
        r = products[pid]
        prompt = build_prompt(r.get("product_name", ""), r.get("product_class", ""),
                              parse_features(r.get("product_features", "")))
        try:
            render(prompt, os.path.join(args.out, f"w{pid}.jpg"))
        except Exception as e:
            print(f"  {pid} failed: {e}", file=sys.stderr)
            continue
        if n % 25 == 0 or n == len(todo):
            per = (time.time() - start) / n
            left = per * (len(todo) - n)
            print(f"  {n}/{len(todo)}  {per:.1f}s/image  ~{left/60:.0f} min remaining", flush=True)

    print(f"\ndone in {(time.time()-start)/60:.1f} min → {args.out}")
    print("copy back with:  rsync -av <mac-studio>:<repo>/data/images/ data/images/")

if __name__ == "__main__":
    main()
