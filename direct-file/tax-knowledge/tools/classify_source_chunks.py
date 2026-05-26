#!/usr/bin/env python3
"""Classify source chunks by tax topic and artifact target.

This is a deterministic first-pass classifier. It is intentionally simple:
domain specialists can review the output, and AI agents can use the relevance
index as bounded context for drafting fact graph, interview, validation, and PDF
configuration artifacts.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import pathlib
import re
import sys
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ClassificationRule:
    topic_id: str
    owner: str | None
    terms: list[str]
    artifact_targets: list[str]


@dataclass(frozen=True)
class SourceChunk:
    source_id: str
    chunk_id: str
    product_id: str | None
    pages: list[int]
    heading: str | None
    text: str
    text_sha256: str | None
    source_url: str | None
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


def render_mapping(mapping: dict[str, Any], indent: int = 0) -> list[str]:
    lines: list[str] = []
    prefix = " " * indent
    for key, value in mapping.items():
        if isinstance(value, dict):
            lines.append(f"{prefix}{key}:" if value else f"{prefix}{key}: {{}}")
            if value:
                lines.extend(render_mapping(value, indent + 2))
        elif isinstance(value, list):
            lines.append(f"{prefix}{key}:" if value else f"{prefix}{key}: []")
            if value:
                lines.extend(render_list(value, indent + 2))
        else:
            lines.append(f"{prefix}{key}: {yaml_scalar(value, indent)}")
    return lines


def render_list(values: list[Any], indent: int) -> list[str]:
    lines: list[str] = []
    prefix = " " * indent
    for value in values:
        if isinstance(value, dict):
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
        else:
            lines.append(f"{prefix}- {yaml_scalar(value, indent)}")
    return lines


def unquote(value: str) -> str:
    value = value.strip()
    if value == "null":
        return ""
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        return value[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    return value


def parse_rules(path: pathlib.Path) -> list[ClassificationRule]:
    rules: list[ClassificationRule] = []
    current: dict[str, Any] | None = None
    list_key: str | None = None

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.rstrip()
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("- topic_id:"):
            if current:
                rules.append(rule_from_dict(current))
            current = {"topic_id": unquote(stripped.split(":", 1)[1])}
            list_key = None
        elif current is not None and re.match(r"^[a-zA-Z_]+:", stripped):
            key, value = stripped.split(":", 1)
            value = value.strip()
            if value:
                current[key] = unquote(value)
                list_key = None
            else:
                current[key] = []
                list_key = key
        elif current is not None and stripped.startswith("- ") and list_key:
            current[list_key].append(unquote(stripped[2:]))

    if current:
        rules.append(rule_from_dict(current))
    return rules


def rule_from_dict(values: dict[str, Any]) -> ClassificationRule:
    return ClassificationRule(
        topic_id=values["topic_id"],
        owner=values.get("owner"),
        terms=list(values.get("terms", [])),
        artifact_targets=list(values.get("artifact_targets", [])),
    )


def parse_source_chunks(path: pathlib.Path) -> list[SourceChunk]:
    lines = path.read_text(encoding="utf-8").splitlines()
    top_level: dict[str, str] = {}
    chunks: list[SourceChunk] = []
    current: dict[str, Any] | None = None
    collecting_text = False
    text_lines: list[str] = []
    collecting_pages = False

    def finish_current() -> None:
        nonlocal current, text_lines, collecting_text, collecting_pages
        if current is None:
            return
        current["text"] = "\n".join(text_lines).rstrip()
        chunks.append(
            SourceChunk(
                source_id=current.get("source_id") or top_level.get("source_id", ""),
                chunk_id=current["chunk_id"],
                product_id=current.get("product_id") or top_level.get("product_id"),
                pages=list(current.get("pages", [])),
                heading=current.get("heading") or None,
                text=current.get("text", ""),
                text_sha256=current.get("text_sha256"),
                source_url=current.get("source_url") or top_level.get("source_url"),
                pdf_sha256=current.get("pdf_sha256") or top_level.get("pdf_sha256"),
            )
        )
        current = None
        text_lines = []
        collecting_text = False
        collecting_pages = False

    for raw_line in lines:
        if raw_line.startswith("chunks:"):
            continue
        if re.match(r"^[a-zA-Z_]+:", raw_line):
            key, value = raw_line.split(":", 1)
            top_level[key] = unquote(value)
            continue
        if raw_line.startswith("  - chunk_id:"):
            finish_current()
            current = {"chunk_id": unquote(raw_line.split(":", 1)[1])}
            continue
        if current is None:
            continue
        if collecting_text:
            if raw_line.startswith("    ") and not re.match(r"^    [a-zA-Z_]+:", raw_line):
                text_lines.append(raw_line[4:])
                continue
            collecting_text = False
        if collecting_pages:
            if raw_line.startswith("      - "):
                current.setdefault("pages", []).append(int(raw_line.strip()[2:]))
                continue
            collecting_pages = False
        if raw_line.startswith("    pages:"):
            current["pages"] = []
            collecting_pages = True
        elif raw_line.startswith("    text: |"):
            text_lines = []
            collecting_text = True
        elif raw_line.startswith("    ") and ":" in raw_line:
            key, value = raw_line.strip().split(":", 1)
            current[key] = unquote(value)

    finish_current()
    return chunks


def classify_chunk(chunk: SourceChunk, rules: list[ClassificationRule], min_score: int) -> list[dict[str, Any]]:
    text = " ".join([chunk.heading or "", chunk.text]).lower()
    results: list[dict[str, Any]] = []
    for rule in rules:
        matched = [term for term in rule.terms if term.lower() in text]
        if len(matched) < min_score:
            continue
        confidence = "high" if len(matched) >= 3 else "medium" if len(matched) == 2 else "low"
        relevance_id = stable_id(chunk.chunk_id, rule.topic_id)
        results.append(
            {
                "relevance_id": relevance_id,
                "source_ref": {
                    "source_id": chunk.source_id,
                    "chunk_id": chunk.chunk_id,
                    "pages": chunk.pages,
                    "text_sha256": chunk.text_sha256 or hashlib.sha256(chunk.text.encode("utf-8")).hexdigest(),
                },
                "topic_ids": [rule.topic_id],
                "artifact_targets": rule.artifact_targets,
                "confidence": confidence,
                "matched_terms": matched,
                "reason": f"Matched {len(matched)} term(s) for topic {rule.topic_id}.",
                "owner": rule.owner,
                "status": "draft",
            }
        )
    return results


def stable_id(chunk_id: str, topic_id: str) -> str:
    digest = hashlib.sha1(f"{chunk_id}:{topic_id}".encode("utf-8")).hexdigest()[:10]
    return f"rel_{topic_id}_{digest}"


def classify(args: argparse.Namespace) -> list[dict[str, Any]]:
    rules = parse_rules(args.rules)
    results: list[dict[str, Any]] = []
    for path in sorted(args.sources_dir.glob("*.yaml")):
        for chunk in parse_source_chunks(path):
            results.extend(classify_chunk(chunk, rules, args.min_score))
    return results


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tax-year", type=int, required=True)
    parser.add_argument("--jurisdiction", default="federal")
    parser.add_argument("--sources-dir", type=pathlib.Path, required=True)
    parser.add_argument("--rules", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--min-score", type=int, default=1)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    results = classify(args)
    document = {
        "tax_year": args.tax_year,
        "jurisdiction": args.jurisdiction,
        "generated_at": dt.datetime.now(dt.UTC).isoformat(),
        "rules_path": str(args.rules),
        "sources_dir": str(args.sources_dir),
        "relevance": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(render_mapping(document)) + "\n", encoding="utf-8")
    print(f"Source relevance classification complete: classified={len(results)} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
