#!/usr/bin/env python3
"""Add verified new catalog sources from monitor snapshots to a draft corpus.

Existing source bytes must match their pins. Changed sources require a separate
revision review instead of being overwritten. This command grants no approvals.
IRS HTML is extracted from <main> as logical page 1; PDFs use PDFBox downstream.
"""
from __future__ import annotations

import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path


class MainText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.in_main = False
        self.ignored = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == "main":
            self.in_main = True
        if self.in_main and tag in {"script", "style"}:
            self.ignored += 1
        if self.in_main and tag in {"h1", "h2", "h3", "p", "li", "tr", "br", "div"}:
            self.parts.append("\n\n")

    def handle_endtag(self, tag):
        if tag == "main":
            self.in_main = False
        if self.in_main and tag in {"script", "style"}:
            self.ignored -= 1
        if self.in_main and tag in {"p", "li", "tr", "h1", "h2", "h3"}:
            self.parts.append("\n\n")

    def handle_data(self, data):
        if self.in_main and not self.ignored:
            self.parts.append(data)


def extract_irs_html(data: bytes) -> str:
    parser = MainText()
    parser.feed(data.decode("utf-8"))
    paragraphs = [" ".join(part.split()) for part in "".join(parser.parts).split("\n\n")]
    text = "\n\n".join(part for part in paragraphs if part)
    if not text:
        raise ValueError("IRS HTML must contain nonempty main content")
    return "----- IRS_SOURCE_PAGE 1 -----\n\n" + text + "\n"


def ingest(root: Path, catalog: dict, baseline: dict, report: dict, snapshots: Path) -> list[dict]:
    if len({catalog["tax_year"], baseline["tax_year"], report["tax_year"]}) != 1:
        raise ValueError("Catalog, baseline, and observations must share an income year")
    if catalog.get("review_status") != "draft" or catalog.get("human_approvals"):
        raise ValueError("Ingestion requires a draft catalog without asserted approvals")
    if report.get("errors"):
        raise ValueError("Resolve source retrieval errors before ingestion")
    old = {entry["source_id"]: entry for entry in baseline["entries"]}
    observed = {entry["source_id"]: entry for entry in report["observations"]}
    if set(old) - {entry["source_id"] for entry in catalog["sources"]}:
        raise ValueError("Source removal requires separate review")
    entries, pending = [], []
    seen_ids, seen_names = set(), set()
    for source in catalog["sources"]:
        key, name = source["source_id"], source["filename"]
        if key in seen_ids or name in seen_names or Path(name).name != name:
            raise ValueError("Duplicate identity or unsafe source filename")
        seen_ids.add(key)
        seen_names.add(name)
        observation = observed[key]
        if observation["url"] != source["url"]:
            raise ValueError("Observation URL mismatch: " + key)
        digest = observation["sha256"]
        data = (snapshots / (digest + "-" + name)).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError("Snapshot hash mismatch: " + key)
        if source["document_status"] not in {"final", "enacted"}:
            raise ValueError("Draft/unverified documents cannot enter the final-source corpus")
        if key in old:
            if old[key]["sha256"] != digest or old[key]["url"] != source["url"]:
                raise ValueError("Changed pinned source requires revision review: " + key)
            target = root / old[key]["local_path"]
            if not target.resolve().is_relative_to(root.resolve()) or target.read_bytes() != data:
                raise ValueError("Existing local source differs from pin: " + key)
            entries.append({**old[key], **source})
            continue
        fmt = source.get("document_format", "pdf")
        if fmt == "pdf" and not data.startswith(b"%PDF-"):
            raise ValueError("Invalid PDF: " + key)
        if fmt not in {"pdf", "html"}:
            raise ValueError("Unsupported source format: " + fmt)
        relative = Path("corpora") / f"irs-{catalog['tax_year']}" / "downloads" / name
        text = extract_irs_html(data) if fmt == "html" else None
        entries.append({**source, "product_id": Path(name).stem.replace("-", "_"),
                        "local_path": relative.as_posix(), "sha256": digest,
                        "bytes_downloaded": len(data)})
        pending.append((root / relative, data, text))
    # Validate the entire batch before writing any new source snapshots.
    for target, data, text in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        if text is not None:
            text_path = target.parent.parent / "text" / target.with_suffix(".txt").name
            text_path.parent.mkdir(parents=True, exist_ok=True)
            text_path.write_text(text, encoding="utf-8")
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--snapshot-dir", type=Path, required=True)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    corpus = args.root / "corpora" / f"irs-{catalog['tax_year']}"
    baseline = json.loads((corpus / "source-manifest.json").read_text())
    report = json.loads(args.observations.read_text())
    entries = ingest(args.root, catalog, baseline, report, args.snapshot_dir)
    manifest = {**baseline, "retrieved_at": report["checked_at"], "entries": entries,
                "watch_pages": report["watch_pages"]}
    subsets = {
        "source-manifest.json": entries,
        "pdf-manifest.json": [e for e in entries if e.get("document_format", "pdf") == "pdf"],
        "active-pdf-manifest.json": [e for e in entries if e.get("document_format", "pdf") == "pdf" and e["disposition"] == "active"],
        "html-manifest.json": [e for e in entries if e.get("document_format") == "html" and e.get("agency", "IRS") == "IRS"],
    }
    for name, subset in subsets.items():
        (corpus / name).write_text(json.dumps({**manifest, "entries": subset}, indent=2, sort_keys=True) + "\n")
    print(f"Source ingestion complete: total={len(entries)} new={len(entries)-len(baseline['entries'])}; status=draft")


if __name__ == "__main__":
    main()
