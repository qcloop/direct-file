#!/usr/bin/env python3
"""Segment extracted IRS publication text into stable citation chunks.

Input is the JSON manifest produced by download_irs_pdfs.py plus text files
created from those PDFs by utils/pdf-to-yaml/PdfToSourceText. Output is one YAML
source-chunk file per PDF.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import re
import sys
from dataclasses import dataclass
from typing import Any


PAGE_MARKER_RE = re.compile(r"^-{5} IRS_SOURCE_PAGE (?P<page>\d+) -{5}$")
HEADING_RE = re.compile(
    r"^(?:Chapter\s+\d+\.?|Part\s+[IVXLC]+\.?|[A-Z][A-Za-z0-9][A-Za-z0-9 ,;:'’()&/\-]{4,120})$"
)


@dataclass(frozen=True)
class Page:
    number: int
    text: str


@dataclass
class Chunk:
    chunk_id: str
    source_id: str
    product_id: str
    title: str | None
    pages: list[int]
    heading: str | None
    text: str
    text_sha256: str
    source_url: str
    pdf_sha256: str | None


def yaml_scalar(value: Any, indent: int = 0) -> str:
    prefix = " " * indent
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    text = str(value)
    if "\n" in text:
        body = "\n".join(f"{prefix}  {line}" if line else f"{prefix}" for line in text.splitlines())
        return f"|\n{body}"
    escaped = text.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def write_yaml_document(path: pathlib.Path, document: dict[str, Any]) -> None:
    lines = render_mapping(document, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def render_mapping(mapping: dict[str, Any], indent: int) -> list[str]:
    lines: list[str] = []
    prefix = " " * indent
    for key, value in mapping.items():
        if isinstance(value, dict):
            if value:
                lines.append(f"{prefix}{key}:")
                lines.extend(render_mapping(value, indent + 2))
            else:
                lines.append(f"{prefix}{key}: {{}}")
        elif isinstance(value, list):
            if value:
                lines.append(f"{prefix}{key}:")
                lines.extend(render_list(value, indent + 2))
            else:
                lines.append(f"{prefix}{key}: []")
        else:
            lines.append(f"{prefix}{key}: {yaml_scalar(value, indent)}")
    return lines


def render_list(values: list[Any], indent: int) -> list[str]:
    lines: list[str] = []
    prefix = " " * indent
    for value in values:
        if isinstance(value, dict):
            if not value:
                lines.append(f"{prefix}- {{}}")
                continue
            items = list(value.items())
            first_key, first_value = items[0]
            if isinstance(first_value, dict):
                lines.append(f"{prefix}- {first_key}:")
                lines.extend(render_mapping(first_value, indent + 4))
            elif isinstance(first_value, list):
                lines.append(f"{prefix}- {first_key}:")
                lines.extend(render_list(first_value, indent + 4))
            else:
                lines.append(f"{prefix}- {first_key}: {yaml_scalar(first_value, indent + 2)}")
            lines.extend(render_mapping(dict(items[1:]), indent + 2))
        elif isinstance(value, list):
            lines.append(f"{prefix}-")
            lines.extend(render_list(value, indent + 2))
        else:
            lines.append(f"{prefix}- {yaml_scalar(value, indent)}")
    return lines


def parse_pages(text: str) -> list[Page]:
    pages: list[Page] = []
    current_page: int | None = None
    current_lines: list[str] = []

    for raw_line in text.splitlines():
        marker = PAGE_MARKER_RE.match(raw_line.strip())
        if marker:
            if current_page is not None:
                pages.append(Page(current_page, normalize_page_text("\n".join(current_lines))))
            current_page = int(marker.group("page"))
            current_lines = []
        else:
            current_lines.append(raw_line)

    if current_page is not None:
        pages.append(Page(current_page, normalize_page_text("\n".join(current_lines))))
    elif text.strip():
        pages.append(Page(1, normalize_page_text(text)))
    return [page for page in pages if page.text]


def normalize_page_text(text: str) -> str:
    lines = [line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    compacted: list[str] = []
    blank = False
    for line in lines:
        clean = line.strip()
        if not clean:
            if not blank:
                compacted.append("")
            blank = True
        else:
            compacted.append(clean)
            blank = False
    return "\n".join(compacted).strip()


def chunk_pages(entry: dict[str, Any], pages: list[Page], args: argparse.Namespace) -> list[Chunk]:
    chunks: list[Chunk] = []
    source_id = entry.get("source_id") or f"irs_{entry['product_id']}_{args.tax_year}"
    product_id = entry["product_id"]
    title = entry.get("description")

    chunk_lines: list[str] = []
    chunk_page_numbers: list[int] = []
    chunk_heading: str | None = None
    chunk_index = 1

    def flush() -> None:
        nonlocal chunk_index, chunk_lines, chunk_page_numbers, chunk_heading
        text = "\n".join(chunk_lines).strip()
        if not text:
            chunk_lines = []
            chunk_page_numbers = []
            chunk_heading = None
            return
        chunk_id = f"{source_id}_chunk_{chunk_index:04d}"
        chunks.append(
            Chunk(
                chunk_id=chunk_id,
                source_id=source_id,
                product_id=product_id,
                title=title,
                pages=sorted(set(chunk_page_numbers)),
                heading=chunk_heading,
                text=text,
                text_sha256=hashlib.sha256(text.encode("utf-8")).hexdigest(),
                source_url=entry["url"],
                pdf_sha256=entry.get("sha256"),
            )
        )
        chunk_index += 1
        chunk_lines = []
        chunk_page_numbers = []
        chunk_heading = None

    for page in pages:
        paragraphs = split_paragraphs(page.text)
        for paragraph in paragraphs:
            heading = detect_heading(paragraph)
            projected_words = word_count("\n".join(chunk_lines + [paragraph]))
            if chunk_lines and (projected_words > args.max_words or heading):
                flush()
            if heading and chunk_heading is None:
                chunk_heading = heading
            chunk_lines.append(paragraph)
            chunk_page_numbers.append(page.number)
        if word_count("\n".join(chunk_lines)) >= args.max_words:
            flush()

    flush()
    return chunks


def split_paragraphs(text: str) -> list[str]:
    return [paragraph.strip() for paragraph in re.split(r"\n\s*\n", text) if paragraph.strip()]


def word_count(text: str) -> int:
    return len(re.findall(r"\S+", text))


def detect_heading(paragraph: str) -> str | None:
    first_line = paragraph.splitlines()[0].strip()
    if len(first_line.split()) > 14:
        return None
    if HEADING_RE.match(first_line):
        return first_line
    return None


def source_id_for_entry(entry: dict[str, Any], tax_year: int) -> str:
    return f"irs_{entry['product_id']}_{tax_year}"


def segment_manifest(args: argparse.Namespace) -> list[pathlib.Path]:
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    generated_paths: list[pathlib.Path] = []
    for entry in manifest.get("entries", []):
        if args.require_downloaded and not entry.get("local_path"):
            continue
        text_path = args.text_dir / pathlib.Path(entry["filename"]).with_suffix(".txt").name
        if not text_path.exists():
            if args.require_text:
                raise FileNotFoundError(f"Missing extracted text for {entry['filename']}: {text_path}")
            continue
        enriched = dict(entry)
        enriched["source_id"] = source_id_for_entry(enriched, args.tax_year)
        pages = parse_pages(text_path.read_text(encoding="utf-8"))
        chunks = chunk_pages(enriched, pages, args)
        output_path = args.output_dir / f"{enriched['source_id']}.yaml"
        write_yaml_document(
            output_path,
            {
                "source_id": enriched["source_id"],
                "product_id": enriched["product_id"],
                "tax_year": args.tax_year,
                "title": enriched.get("description"),
                "source_url": enriched["url"],
                "pdf_sha256": enriched.get("sha256"),
                "text_path": str(text_path),
                "generated_at": dt.datetime.now(dt.UTC).isoformat(),
                "chunking": {"max_words": args.max_words},
                "chunks": [chunk.__dict__ for chunk in chunks],
            },
        )
        generated_paths.append(output_path)
    return generated_paths


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tax-year", type=int, required=True)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--text-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--max-words", type=int, default=450)
    parser.add_argument("--require-downloaded", action="store_true")
    parser.add_argument("--require-text", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    generated = segment_manifest(args)
    print(f"IRS source segmentation complete: generated={len(generated)} output_dir={args.output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
