#!/usr/bin/env python3
"""Prints the OFFICIAL SHA-256 of each catalog model, read from the Hugging Face API (LFS oid).

Run on a machine WITH internet access:   python3 tools/fetch_model_hashes.py
Then paste each value into `sha256 = "..."` of the matching entry in LlmState.kt (LlmModelCatalog).
Never type or copy a hash from anywhere else: a model without an official hash stays "unverified".
This script was written but NOT executed in the agent environment (no network there).
"""
import json, re, sys, urllib.request

# repo, file, catalog id  (must match the URLs in LlmModelCatalog)
MODELS = [
    ("Qwen/Qwen2.5-3B-Instruct-GGUF", "qwen2.5-3b-instruct-q4_k_m.gguf", "qwen2.5-3b-instruct-q4km"),
    ("bartowski/gemma-2-2b-it-GGUF", "gemma-2-2b-it-Q4_K_M.gguf", "gemma-2-2b-it-q4km"),
    ("Qwen/Qwen2.5-7B-Instruct-GGUF", "qwen2.5-7b-instruct-q4_k_m.gguf", "qwen2.5-7b-instruct-q4km"),
]

def lfs_info(repo, filename):
    url = f"https://huggingface.co/api/models/{repo}/tree/main"
    with urllib.request.urlopen(url, timeout=30) as r:
        for e in json.load(r):
            if e.get("path") == filename and e.get("lfs"):
                return e["lfs"]["oid"], e["lfs"].get("size", e.get("size"))
    return None, None

ok = True
for repo, fn, cid in MODELS:
    try:
        sha, size = lfs_info(repo, fn)
    except Exception as ex:
        print(f"# {cid}: FAILED ({ex})"); ok = False; continue
    if sha and re.fullmatch(r"[0-9a-f]{64}", sha):
        print(f'# {cid}  size={size}\nsha256 = "{sha}"')
    else:
        print(f"# {cid}: no LFS hash found for {fn}"); ok = False
sys.exit(0 if ok else 1)
