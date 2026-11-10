#!/usr/bin/env python3
"""Prints the OFFICIAL SHA-256 and exact size of every model in the catalog, read from Hugging Face.

Run it on a machine with internet access, then paste the values into LlmModelCatalog
(app/src/main/java/ir/elviran/sam/ai/local/LlmState.kt): `sha256 = "<hash>"`.
Never type a hash from memory: a model is shown as "verified" only when the hash came from the host.
NOTE: written but NOT executed in the delivery environment (it had no network).
"""
import json, re, sys, urllib.request
SRC = "app/src/main/java/ir/elviran/sam/ai/local/LlmState.kt"
text = open(SRC, encoding="utf-8").read()
for m in re.finditer(r'id = "([^"]+)".*?url = "https://huggingface\.co/([^/]+/[^/]+)/resolve/([^/]+)/([^"]+)"', text, re.S):
    mid, repo, rev, path = m.groups()
    api = f"https://huggingface.co/api/models/{repo}/tree/{rev}"
    try:
        entries = json.load(urllib.request.urlopen(api, timeout=30))
    except Exception as e:
        print(f"{mid}: FAILED to query {api}: {e}", file=sys.stderr); continue
    hit = next((e for e in entries if e.get("path") == path), None)
    lfs = (hit or {}).get("lfs") or {}
    if not lfs.get("oid"):
        print(f"{mid}: no LFS sha256 found for {path}", file=sys.stderr); continue
    print(f'{mid}: sha256 = "{lfs["oid"]}"   // size {lfs.get("size", hit.get("size"))} bytes, {repo}/{path}')
