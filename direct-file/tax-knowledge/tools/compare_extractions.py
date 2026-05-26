#!/usr/bin/env python3
"""Compare two page-marked text extraction outputs.

The primary extraction is typically PDFBox text. The secondary extraction can be
from a vision model, OCR engine, or another parser. Both inputs should use the
IRS_SOURCE_PAGE markers produced by PdfToSourceText, or equivalent markers.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
import sys
from typing import Any


PAGE_MARKER_RE = re.compile(r"^-{5} IRS_SOURCE_PAGE (?P<page>\d+) -{5}$")


def parse_pages(path: pathlib.Path) -> dict[int, str]:
    pages: dict[int, list[str]] = {}
    current_page: int | None = None
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        marker = PAGE_MARKER_RE.match(raw_line.strip())
        if marker:
            current_page = int(marker.group("page"))
            pages[current_page] = []
        elif current_page is not None:
            pages[current_page].append(raw_line)
    if not pages:
        pages[1] = path.read_text(encoding="utf-8").splitlines()
    return {page: "\n".join(lines).strip() for page, lines in pages.items()}


def words(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+", text.lower()))


def compare_pair(primary: pathlib.Path, secondary: pathlib.Path, args: argparse.Namespace) -> dict[str, Any]:
    primary_pages = parse_pages(primary)
    secondary_pages = parse_pages(secondary)
    page_numbers = sorted(set(primary_pages) | set(secondary_pages))
    comparisons: list[dict[str, Any]] = []
    for page in page_numbers:
        primary_text = primary_pages.get(page, "")
        secondary_text = secondary_pages.get(page, "")
        primary_words = words(primary_text)
        secondary_words = words(secondary_text)
        union = primary_words | secondary_words
        intersection = primary_words & secondary_words
        word_jaccard = 1.0 if not union else len(intersection) / len(union)
        longer = max(len(primary_text), len(secondary_text))
        shorter = min(len(primary_text), len(secondary_text))
        length_ratio = 1.0 if longer == 0 else shorter / longer
        comparisons.append(
            {
                "page": page,
                "primary_words": len(primary_words),
                "secondary_words": len(secondary_words),
                "word_jaccard": round(word_jaccard, 4),
                "length_ratio": round(length_ratio, 4),
                "needs_review": word_jaccard < args.min_jaccard or length_ratio < args.min_length_ratio,
            }
        )
    return {
        "document_id": primary.stem,
        "primary_file": str(primary),
        "secondary_file": str(secondary),
        "page_comparisons": comparisons,
        "summary": {
            "pages_compared": len(comparisons),
            "pages_needing_review": sum(1 for item in comparisons if item["needs_review"]),
        },
    }


def compare(args: argparse.Namespace) -> dict[str, Any]:
    documents: list[dict[str, Any]] = []
    for primary in sorted(args.primary_text_dir.glob("*.txt")):
        secondary = args.secondary_text_dir / primary.name
        if secondary.exists():
            documents.append(compare_pair(primary, secondary, args))
    return {
        "primary_extraction": args.primary_name,
        "secondary_extraction": args.secondary_name,
        "compared_at": dt.datetime.now(dt.UTC).isoformat(),
        "documents": documents,
        "summary": {
            "documents_compared": len(documents),
            "pages_needing_review": sum(doc["summary"]["pages_needing_review"] for doc in documents),
        },
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--primary-text-dir", type=pathlib.Path, required=True)
    parser.add_argument("--secondary-text-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--primary-name", default="pdfbox")
    parser.add_argument("--secondary-name", default="vision_or_ocr")
    parser.add_argument("--min-jaccard", type=float, default=0.85)
    parser.add_argument("--min-length-ratio", type=float, default=0.75)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    result = compare(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Extraction comparison complete: documents={result['summary']['documents_compared']} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
