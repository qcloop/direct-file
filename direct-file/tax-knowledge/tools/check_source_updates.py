#!/usr/bin/env python3
"""Compare official source bytes with a pinned manifest; never approve or replace a release.

Designed for a scheduled CI job. Exit 0 means unchanged, 2 means changes need review,
1 means a source could not be checked. The output is a separate observation report.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys
import urllib.parse
import urllib.request

ALLOWED_HOSTS = {"www.irs.gov", "irs.gov", "www.ssa.gov", "ssa.gov", "uscode.house.gov"}


def fetch(url: str, timeout: int) -> bytes:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS:
        raise ValueError("Source must use HTTPS on an approved IRS, SSA, or US Code host")
    request = urllib.request.Request(url, headers={"User-Agent": "direct-file-tax-knowledge-source-monitor/0.2"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if urllib.parse.urlparse(response.url).hostname not in ALLOWED_HOSTS:
            raise ValueError("Source redirected outside the approved authority hosts")
        return response.read()


def check(catalog: dict, baseline: dict, timeout: int = 30, fetcher=fetch, snapshot_dir: Path | None = None) -> dict:
    old = {entry["source_id"]: entry for entry in baseline.get("entries", [])}
    observations, changes, errors = [], [], []
    current_ids = set()
    for source in catalog["sources"]:
        source_id = source["source_id"]
        current_ids.add(source_id)
        try:
            data = fetcher(source["url"], timeout)
            if source["filename"].lower().endswith(".pdf") and not data.startswith(b"%PDF-"):
                raise ValueError("Expected PDF bytes, received another content type")
            digest = hashlib.sha256(data).hexdigest()
            if snapshot_dir is not None:
                snapshot_dir.mkdir(parents=True, exist_ok=True)
                (snapshot_dir / (digest + "-" + Path(source["filename"]).name)).write_bytes(data)
            observations.append({"source_id": source_id, "url": source["url"], "sha256": digest, "bytes": len(data)})
            previous = old.get(source_id, {}).get("sha256")
            if digest != previous:
                changes.append({"source_id": source_id, "kind": "changed" if previous else "new",
                                "previous_sha256": previous, "observed_sha256": digest,
                                "action": "Verify revision, effective dates, affected artifacts, and scenarios before promotion."})
        except (OSError, ValueError) as exc:
            errors.append({"source_id": source_id, "message": str(exc)})
    changes.extend({"source_id": key, "kind": "removed_from_catalog", "action": "Review removal and its dependents."}
                   for key in sorted(old.keys() - current_ids))
    old_watch = {entry["url"]: entry["sha256"] for entry in baseline.get("watch_pages", [])}
    watch_pages = []
    for url in catalog.get("watch_urls", []):
        try:
            digest = hashlib.sha256(fetcher(url, timeout)).hexdigest()
            watch_pages.append({"url": url, "sha256": digest})
            if digest != old_watch.get(url):
                changes.append({"url": url, "kind": "discovery_page_changed",
                                "previous_sha256": old_watch.get(url), "observed_sha256": digest,
                                "action": "Review newly posted announcements, corrections, and final revisions. The listing is not itself tax-law authority."})
        except (OSError, ValueError) as exc:
            errors.append({"url": url, "message": str(exc)})
    return {"tax_year": catalog["tax_year"], "checked_at": dt.datetime.now(dt.UTC).isoformat(),
            "observations": observations, "watch_pages": watch_pages, "changes": changes, "errors": errors}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--snapshot-dir", type=Path, help="Optional content-addressed copies of observed source documents")
    args = parser.parse_args(argv)
    if args.output.resolve() in (args.baseline.resolve(), args.catalog.resolve()):
        parser.error("Observation output must differ from the pinned baseline and catalog")
    catalog = json.loads(args.catalog.read_text())
    baseline = json.loads(args.baseline.read_text())
    if catalog["tax_year"] != baseline["tax_year"]:
        parser.error("Catalog and baseline must cover the same income year")
    report = check(catalog, baseline, args.timeout, snapshot_dir=args.snapshot_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(f"Source check: changed={len(report['changes'])} errors={len(report['errors'])} report={args.output}")
    return 1 if report["errors"] else 2 if report["changes"] else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
