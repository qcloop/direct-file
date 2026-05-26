#!/usr/bin/env python3
"""Validate source provenance for tax-knowledge artifacts.

Draft artifacts may still cite high-level source_ids. Reviewed or approved
artifacts must cite exact source_refs with chunk ids, pages, and chunk hashes.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from typing import Any


ARTIFACT_DIRS = ("topics", "rules", "questions", "evidence", "conflicts", "scenarios", "factgraph")
APPROVAL_STATUSES = {"reviewed", "approved", "compiled"}


def parse_known_chunks(sources_dir: pathlib.Path | None) -> set[str]:
    if sources_dir is None or not sources_dir.exists():
        return set()
    chunk_ids: set[str] = set()
    for path in sources_dir.glob("*.yaml"):
        for match in re.finditer(r"^\s*-?\s*chunk_id:\s*\"?([^\"\n]+)\"?", path.read_text(encoding="utf-8"), re.MULTILINE):
            chunk_ids.add(match.group(1).strip())
    return chunk_ids


def artifact_files(root: pathlib.Path) -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for dirname in ARTIFACT_DIRS:
        directory = root / dirname
        if directory.exists():
            files.extend(sorted(directory.rglob("*.yaml")))
    return files


def parse_statuses(text: str) -> list[str]:
    statuses = re.findall(r"^\s*status:\s*([a-zA-Z_]+)\s*$", text, re.MULTILINE)
    return statuses or ["draft"]


def source_ref_blocks(text: str) -> list[str]:
    blocks: list[str] = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if re.match(r"^\s*source_refs:\s*$", line):
            indent = len(line) - len(line.lstrip())
            block_lines: list[str] = []
            for next_line in lines[index + 1 :]:
                if next_line.strip() and len(next_line) - len(next_line.lstrip()) <= indent:
                    break
                block_lines.append(next_line)
            blocks.append("\n".join(block_lines))
    return blocks


def validate_file(path: pathlib.Path, known_chunks: set[str]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    text = path.read_text(encoding="utf-8")
    statuses = parse_statuses(text)
    requires_source_refs = any(status in APPROVAL_STATUSES for status in statuses)
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []

    blocks = source_ref_blocks(text)
    if requires_source_refs and not blocks:
        errors.append({"file": str(path), "message": "reviewed/approved artifacts require source_refs"})
        return errors, warnings
    if not requires_source_refs and not blocks and re.search(r"^\s*source_ids:\s*$", text, re.MULTILINE):
        warnings.append({"file": str(path), "message": "draft artifact has source_ids but no exact source_refs yet"})
        return errors, warnings

    for block in blocks:
        chunk_ids = re.findall(r"^\s*-?\s*chunk_id:\s*\"?([^\"\n]+)\"?", block, re.MULTILINE)
        if not re.search(r"^\s*-?\s*source_id:\s*", block, re.MULTILINE):
            errors.append({"file": str(path), "message": "source_ref is missing source_id"})
        if not chunk_ids:
            errors.append({"file": str(path), "message": "source_ref is missing chunk_id"})
        if not re.search(r"^\s*pages:\s*$", block, re.MULTILINE):
            errors.append({"file": str(path), "message": "source_ref is missing pages"})
        if not re.search(r"^\s*text_sha256:\s*", block, re.MULTILINE):
            errors.append({"file": str(path), "message": "source_ref is missing text_sha256"})
        for chunk_id in chunk_ids:
            if known_chunks and chunk_id not in known_chunks:
                errors.append({"file": str(path), "message": f"source_ref chunk_id not found: {chunk_id}"})
    return errors, warnings


def validate(args: argparse.Namespace) -> dict[str, Any]:
    known_chunks = parse_known_chunks(args.sources_dir)
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    files = artifact_files(args.root)
    for path in files:
        file_errors, file_warnings = validate_file(path, known_chunks)
        errors.extend(file_errors)
        warnings.extend(file_warnings)
    return {
        "root": str(args.root),
        "sources_dir": str(args.sources_dir) if args.sources_dir else None,
        "files_checked": len(files),
        "known_chunks": len(known_chunks),
        "errors": errors,
        "warnings": warnings,
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, required=True)
    parser.add_argument("--sources-dir", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--warnings-are-errors", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    result = validate(args)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    else:
        print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if result["errors"] or (args.warnings_are_errors and result["warnings"]) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
