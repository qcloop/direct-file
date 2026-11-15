#!/usr/bin/env python3
"""Validate exact source identity, pages, and text hashes before artifact promotion.

Install tools/requirements.txt. Drafts may omit citations; every provided citation is
validated. Reviewed, approved, published, and compiled artifacts require source_refs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any

import yaml

ARTIFACT_DIRS = ("topics", "rules", "questions", "evidence", "conflicts", "scenarios", "factgraph", "changes")
APPROVAL_STATUSES = {"reviewed", "approved", "published", "compiled"}


def load_source_index(root: pathlib.Path | None) -> tuple[dict[str, dict[str, Any]], list[dict[str, str]]]:
    index: dict[str, dict[str, Any]] = {}
    errors: list[dict[str, str]] = []
    if root is None or not root.exists():
        return index, errors
    for path in sorted(root.rglob("*.yaml")):
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            for chunk in doc.get("chunks", []):
                record = {**chunk, "applicable_tax_years": doc.get("applicable_tax_years", [doc.get("tax_year")])}
                record["source_id"] = chunk.get("source_id", doc.get("source_id"))
                key = record.get("chunk_id")
                if not key:
                    continue
                if key in index and index[key].get("text_sha256") != record.get("text_sha256"):
                    errors.append({"file": str(path), "message": f"conflicting source chunk identity: {key}"})
                index[key] = record
        except (OSError, yaml.YAMLError, AttributeError, TypeError) as exc:
            errors.append({"file": str(path), "message": f"invalid source YAML: {exc}"})
    return index, errors


def artifact_files(root: pathlib.Path, tax_year: int | None = None) -> list[pathlib.Path]:
    return [path for directory in ARTIFACT_DIRS for path in sorted((root / directory).rglob("*.yaml"))
            if tax_year is None or str(tax_year) in path.relative_to(root / directory).parts[:-1]]


def validate_ref(ref: Any, index: dict[str, dict[str, Any]], year: Any) -> list[str]:
    if not isinstance(ref, dict):
        return ["source_ref must be a mapping"]
    required = ("source_id", "chunk_id", "pages", "text_sha256")
    missing = [field for field in required if not ref.get(field)]
    if missing:
        return ["source_ref is missing " + ", ".join(missing)]
    if any(not isinstance(ref[field], str) for field in ("source_id", "chunk_id", "text_sha256")):
        return ["source_id, chunk_id, and text_sha256 must be strings"]
    if not isinstance(ref["pages"], list) or any(type(page) is not int or page < 1 for page in ref["pages"]):
        return ["source_ref pages must be a list of positive page numbers"]
    chunk = index.get(ref["chunk_id"])
    if chunk is None:
        return [f"source_ref chunk_id not found: {ref['chunk_id']}"]
    problems = []
    if ref["source_id"] != chunk.get("source_id"):
        problems.append(f"source_id does not own chunk {ref['chunk_id']}")
    if not set(ref["pages"]).issubset(set(chunk.get("pages", []))):
        problems.append(f"source_ref pages do not match chunk {ref['chunk_id']}")
    # The v1 YAML writer used a literal block with the default trailing newline;
    # its original chunk hash was calculated before that serialization newline.
    text = chunk.get("text", "").rstrip("\n")
    actual_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if ref["text_sha256"] != chunk.get("text_sha256") or ref["text_sha256"] != actual_hash:
        problems.append(f"source_ref text hash does not match chunk {ref['chunk_id']}")
    if year is not None and year not in chunk.get("applicable_tax_years", []) and not ref.get("carry_forward_reason"):
        problems.append(f"source_ref requires an explicit carry_forward_reason for income year {year}")
    return problems


def validate_file(path: pathlib.Path, index: dict[str, dict[str, Any]]) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    errors, warnings = [], []
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as exc:
        return [{"file": str(path), "message": f"invalid artifact YAML: {exc}"}], warnings
    if not isinstance(doc, dict):
        return [{"file": str(path), "message": "artifact must be a YAML mapping"}], warnings
    year = doc.get("tax_year")

    def visit(node: Any, inherited_refs: list[Any]) -> None:
        if isinstance(node, list):
            for child in node:
                visit(child, inherited_refs)
        elif isinstance(node, dict):
            refs = node.get("source_refs", inherited_refs)
            if node.get("status", "draft") in APPROVAL_STATUSES and not refs:
                errors.append({"file": str(path), "message": "reviewed/approved/published artifacts require source_refs"})
            if "source_refs" in node:
                if not isinstance(node["source_refs"], list):
                    errors.append({"file": str(path), "message": "source_refs must be a list"})
                    refs = []
                else:
                    for ref in node["source_refs"]:
                        errors.extend({"file": str(path), "message": problem} for problem in validate_ref(ref, index, year))
            elif node.get("source_ids") and not refs and node.get("status", "draft") not in APPROVAL_STATUSES:
                warnings.append({"file": str(path), "message": "draft artifact has source_ids but no exact source_refs yet"})
            for key, child in node.items():
                if key != "source_refs":
                    visit(child, refs)
    visit(doc, [])
    return errors, warnings


def validate(args: argparse.Namespace) -> dict[str, Any]:
    index, errors = load_source_index(args.sources_dir)
    warnings: list[dict[str, str]] = []
    files = artifact_files(args.root, getattr(args, "tax_year", None))
    for path in files:
        file_errors, file_warnings = validate_file(path, index)
        errors.extend(file_errors)
        warnings.extend(file_warnings)
    return {"root": str(args.root), "sources_dir": str(args.sources_dir) if args.sources_dir else None,
            "files_checked": len(files), "known_chunks": len(index), "errors": errors, "warnings": warnings}


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, required=True)
    parser.add_argument("--sources-dir", type=pathlib.Path)
    parser.add_argument("--tax-year", type=int)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--warnings-are-errors", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    result = validate(args)
    output = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        print(output, end="")
    return int(bool(result["errors"] or (args.warnings_are_errors and result["warnings"])))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
