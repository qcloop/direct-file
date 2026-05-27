#!/usr/bin/env python3
"""Discover and optionally download IRS PDFs for a tax-year corpus.

The IRS static PDF directory is paginated HTML. This tool keeps ingestion
deterministic and CI-friendly by using the Python standard library instead of a
browser runtime. If the IRS page becomes JavaScript-rendered later, this script
is the seam where a Playwright fetcher can be added without changing the
manifest format consumed by downstream pipeline stages.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html.parser
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass, replace
from typing import Any


DEFAULT_SOURCE_URL = "https://www.irs.gov/downloads/irs-pdf"
DEFAULT_USER_AGENT = "direct-file-tax-knowledge-corpus-ingester/0.1"
PDF_RE = re.compile(r"\.pdf(?:$|[?#])", re.IGNORECASE)
SIZE_RE = re.compile(r"^\d+(?:\.\d+)?\s*(?:B|KB|MB|GB)$", re.IGNORECASE)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2}:\d{2})?$")
REVISION_RE = re.compile(r"^(?P<revision>(?:20\d{2})|(?:0[1-9]|1[0-2])\d{2})\b")


@dataclass(frozen=True)
class Anchor:
    href: str
    text: str


@dataclass(frozen=True)
class Cell:
    text: str
    href: str | None = None


@dataclass(frozen=True)
class PdfEntry:
    filename: str
    url: str
    product_id: str
    product_kind: str | None
    revision: str | None
    revision_year: int | None
    modified_at: str | None
    size: str | None
    description: str | None
    page_url: str
    local_path: str | None = None
    sha256: str | None = None
    bytes_downloaded: int | None = None


class IRSDirectoryParser(html.parser.HTMLParser):
    """Extract rows and anchors from the IRS directory listing."""

    def __init__(self, page_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.page_url = page_url
        self.rows: list[list[Cell]] = []
        self.anchors: list[Anchor] = []
        self._in_row = False
        self._in_cell = False
        self._in_anchor = False
        self._row: list[Cell] = []
        self._cell_text: list[str] = []
        self._cell_href: str | None = None
        self._anchor_href: str | None = None
        self._anchor_text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_map = dict(attrs)
        if tag == "tr":
            self._in_row = True
            self._row = []
        elif tag in {"td", "th"} and self._in_row:
            self._in_cell = True
            self._cell_text = []
            self._cell_href = None
        elif tag == "a":
            href = attrs_map.get("href")
            if href:
                absolute = urllib.parse.urljoin(self.page_url, href)
                self._in_anchor = True
                self._anchor_href = absolute
                self._anchor_text = []
                if self._in_cell and PDF_RE.search(absolute):
                    self._cell_href = absolute

    def handle_data(self, data: str) -> None:
        if self._in_cell:
            self._cell_text.append(data)
        if self._in_anchor:
            self._anchor_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._in_anchor:
            self.anchors.append(Anchor(self._anchor_href or "", normalize_text("".join(self._anchor_text))))
            self._in_anchor = False
            self._anchor_href = None
            self._anchor_text = []
        elif tag in {"td", "th"} and self._in_cell:
            self._row.append(Cell(normalize_text("".join(self._cell_text)), self._cell_href))
            self._in_cell = False
            self._cell_text = []
            self._cell_href = None
        elif tag == "tr" and self._in_row:
            if self._row:
                self.rows.append(self._row)
            self._in_row = False
            self._row = []


def normalize_text(value: str) -> str:
    return " ".join(value.split())


def parse_entries(html: str, page_url: str) -> tuple[list[PdfEntry], list[Anchor]]:
    parser = IRSDirectoryParser(page_url)
    parser.feed(html)
    entries = entries_from_rows(parser.rows, page_url)
    if not entries:
        entries = entries_from_anchor_fallback(html, page_url)
    return entries, parser.anchors


def entries_from_rows(rows: list[list[Cell]], page_url: str) -> list[PdfEntry]:
    entries: list[PdfEntry] = []
    for row in rows:
        pdf_index = next((i for i, cell in enumerate(row) if cell.href and PDF_RE.search(cell.href)), None)
        if pdf_index is None:
            continue
        pdf_cell = row[pdf_index]
        after = row[pdf_index + 1 :]
        modified_at = first_matching(after, DATE_RE)
        size = first_matching(after, SIZE_RE)
        description = infer_description(after, modified_at, size)
        entries.append(build_entry(pdf_cell.href or "", pdf_cell.text, modified_at, size, description, page_url))
    return entries


def entries_from_anchor_fallback(html: str, page_url: str) -> list[PdfEntry]:
    pattern = re.compile(
        r'<a[^>]+href=["\'](?P<href>[^"\']+\.pdf)["\'][^>]*>(?P<name>[^<]+)</a>'
        r"(?P<trailing>.*?)(?=<a[^>]+href=|</pre>|</tr>|$)",
        re.IGNORECASE | re.DOTALL,
    )
    entries: list[PdfEntry] = []
    for match in pattern.finditer(html):
        trailing = normalize_text(re.sub(r"<[^>]+>", " ", match.group("trailing")))
        modified_at = None
        size = None
        description = trailing
        date_match = re.search(r"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}", trailing)
        if date_match:
            modified_at = date_match.group(0)
            description = trailing[date_match.end() :].strip()
        size_match = re.search(r"\d+(?:\.\d+)?\s*(?:B|KB|MB|GB)", description, re.IGNORECASE)
        if size_match:
            size = size_match.group(0)
            description = description[size_match.end() :].strip()
        href = urllib.parse.urljoin(page_url, match.group("href"))
        entries.append(build_entry(href, normalize_text(match.group("name")), modified_at, size, description, page_url))
    return entries


def first_matching(cells: list[Cell], pattern: re.Pattern[str]) -> str | None:
    for cell in cells:
        if pattern.match(cell.text):
            return cell.text
    return None


def infer_description(cells: list[Cell], modified_at: str | None, size: str | None) -> str | None:
    for cell in reversed(cells):
        if not cell.text or cell.text in {modified_at, size}:
            continue
        if DATE_RE.match(cell.text) or SIZE_RE.match(cell.text):
            continue
        return cell.text
    return None


def build_entry(
    href: str,
    link_text: str,
    modified_at: str | None,
    size: str | None,
    description: str | None,
    page_url: str,
) -> PdfEntry:
    filename = pathlib.PurePosixPath(urllib.parse.urlparse(href).path).name or link_text
    product_id = pathlib.PurePosixPath(filename).stem.lower()
    revision, revision_year = parse_revision(description)
    return PdfEntry(
        filename=filename,
        url=href,
        product_id=product_id,
        product_kind=parse_product_kind(description, filename),
        revision=revision,
        revision_year=revision_year,
        modified_at=modified_at,
        size=size,
        description=description,
        page_url=page_url,
    )


def parse_revision(description: str | None) -> tuple[str | None, int | None]:
    if not description:
        return None, None
    match = REVISION_RE.match(description)
    if not match:
        return None, None
    revision = match.group("revision")
    if len(revision) == 4 and revision.startswith("20"):
        return revision, int(revision)
    return revision, 2000 + int(revision[-2:])


def parse_product_kind(description: str | None, filename: str) -> str | None:
    text = description or ""
    if re.search(r"\bPubl\b", text):
        return "publication"
    if re.search(r"\bInst\b", text):
        return "instruction"
    if re.search(r"\bForm\b", text):
        return "form"
    if re.search(r"\bNotc\b", text):
        return "notice"
    if filename.lower().startswith("p"):
        return "publication"
    if filename.lower().startswith("i"):
        return "instruction"
    if filename.lower().startswith("f"):
        return "form"
    if filename.lower().startswith("n"):
        return "notice"
    return None


def next_page_count(anchors: list[Anchor]) -> int | None:
    pages: list[int] = []
    for anchor in anchors:
        parsed = urllib.parse.urlparse(anchor.href)
        query = urllib.parse.parse_qs(parsed.query)
        for raw_page in query.get("page", []):
            if raw_page.isdigit():
                pages.append(int(raw_page))
    return max(pages) + 1 if pages else None


def page_url(source_url: str, page: int) -> str:
    if page == 0:
        return source_url
    parsed = urllib.parse.urlparse(source_url)
    query = urllib.parse.parse_qs(parsed.query)
    query["page"] = [str(page)]
    return urllib.parse.urlunparse(parsed._replace(query=urllib.parse.urlencode(query, doseq=True)))


def load_product_filter(path: pathlib.Path | None) -> set[str]:
    if path is None:
        return set()
    products: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        clean = line.split("#", 1)[0].strip().lower()
        if not clean:
            continue
        products.add(pathlib.PurePosixPath(clean).stem)
    return products


def matches_filters(
    entry: PdfEntry,
    args: argparse.Namespace,
    product_filter: set[str],
    carry_forward_filter: set[str] | None = None,
) -> bool:
    carry_forward_filter = carry_forward_filter or set()
    if args.kind != "all" and entry.product_kind != args.kind:
        return False
    if args.revision_year and entry.revision_year != args.revision_year and entry.product_id not in carry_forward_filter:
        return False
    if product_filter and entry.product_id not in product_filter:
        return False
    if args.filename_regex and not re.search(args.filename_regex, entry.filename, re.IGNORECASE):
        return False
    return True


def fetch_text(url: str, args: argparse.Namespace) -> str:
    return fetch_bytes(url, args).decode("utf-8", errors="replace")


def fetch_bytes(url: str, args: argparse.Namespace) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": args.user_agent})
    last_error: Exception | None = None
    for attempt in range(args.retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                return response.read()
        except (urllib.error.URLError, TimeoutError) as exc:
            last_error = exc
            if attempt < args.retries:
                time.sleep(args.retry_delay)
    raise RuntimeError(f"Failed to fetch {url}: {last_error}") from last_error


def download_entry(entry: PdfEntry, output_dir: pathlib.Path, args: argparse.Namespace) -> PdfEntry:
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / entry.filename
    payload = fetch_bytes(entry.url, args)
    sha256 = hashlib.sha256(payload).hexdigest()
    if not args.no_clobber or not target.exists():
        temporary = target.with_suffix(target.suffix + ".part")
        temporary.write_bytes(payload)
        temporary.replace(target)
    return replace(
        entry,
        local_path=str(target.relative_to(pathlib.Path.cwd()) if target.is_relative_to(pathlib.Path.cwd()) else target),
        sha256=sha256,
        bytes_downloaded=len(payload),
    )


def discover(args: argparse.Namespace) -> tuple[list[PdfEntry], int]:
    product_filter = load_product_filter(args.product_list)
    carry_forward_filter = load_product_filter(args.carry_forward_product_list)
    selected: list[PdfEntry] = []
    seen_urls: set[str] = set()

    first_html = fetch_text(args.source_url, args)
    first_entries, first_anchors = parse_entries(first_html, args.source_url)
    discovered_page_count = next_page_count(first_anchors)
    page_count = args.max_pages or discovered_page_count or 200

    for page in range(page_count):
        current_url = page_url(args.source_url, page)
        if page == 0:
            entries = first_entries
        else:
            entries, _ = parse_entries(fetch_text(current_url, args), current_url)
        if not entries:
            break
        for entry in entries:
            if entry.url in seen_urls:
                continue
            seen_urls.add(entry.url)
            if matches_filters(entry, args, product_filter, carry_forward_filter):
                selected.append(entry)
                if args.limit and len(selected) >= args.limit:
                    return selected, page + 1
    return selected, page_count


def write_manifest(entries: list[PdfEntry], pages_scanned: int, args: argparse.Namespace) -> None:
    manifest = {
        "schema_version": 1,
        "source_url": args.source_url,
        "tax_year": args.tax_year,
        "retrieved_at": dt.datetime.now(dt.UTC).isoformat(),
        "pages_scanned": pages_scanned,
        "downloaded": bool(args.download),
        "filters": {
            "kind": args.kind,
            "revision_year": args.revision_year,
            "filename_regex": args.filename_regex,
            "product_list": str(args.product_list) if args.product_list else None,
            "carry_forward_product_list": str(args.carry_forward_product_list)
            if args.carry_forward_product_list
            else None,
            "limit": args.limit,
        },
        "entries": [asdict(entry) for entry in entries],
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tax-year", type=int, required=True)
    parser.add_argument("--source-url", default=DEFAULT_SOURCE_URL)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--kind", choices=["all", "publication", "form", "instruction", "notice"], default="publication")
    parser.add_argument("--revision-year", type=int, help="Defaults to --tax-year. Use 0 to disable revision filtering.")
    parser.add_argument("--product-list", type=pathlib.Path, help="Text file of product ids or filenames to include.")
    parser.add_argument(
        "--carry-forward-product-list",
        type=pathlib.Path,
        help=(
            "Text file of product ids allowed through the revision-year filter because "
            "the latest older revision is still treated as authoritative for this corpus."
        ),
    )
    parser.add_argument("--filename-regex", help="Optional regex applied to PDF filenames.")
    parser.add_argument("--max-pages", type=int, help="Maximum listing pages to scan. Defaults to listing's last page.")
    parser.add_argument("--limit", type=int, help="Stop after N matching PDFs.")
    parser.add_argument("--download", action="store_true", help="Download PDFs. Without this, only writes a manifest.")
    parser.add_argument("--no-clobber", action="store_true", help="Do not replace existing PDFs, but still hash fetched bytes.")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--retry-delay", type=float, default=1.0)
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    args = parser.parse_args(argv)
    if args.revision_year is None:
        args.revision_year = args.tax_year
    if args.revision_year == 0:
        args.revision_year = None
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    entries, pages_scanned = discover(args)
    if args.download:
        entries = [download_entry(entry, args.output_dir, args) for entry in entries]
    write_manifest(entries, pages_scanned, args)
    print(
        f"IRS PDF ingestion complete: matched={len(entries)} downloaded={args.download} "
        f"manifest={args.manifest}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
