#!/usr/bin/env python3
"""Build a deterministic, hashed review candidate. This command never grants human approval."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

from validate_artifact_provenance import validate


def build(root: Path, year: int) -> dict:
    args = argparse.Namespace(root=root, sources_dir=root / "sources/irs" / str(year), tax_year=year)
    provenance = validate(args)
    if provenance["errors"] or provenance["warnings"]:
        raise ValueError("Provenance must have zero errors and warnings: " + json.dumps(provenance))
    paths = []
    for family in ("rules", "questions", "evidence", "conflicts", "scenarios", "topics", "factgraph", "changes"):
        paths.extend(sorted((root / family / "federal" / str(year)).glob("*.yaml")))
    for required in ("rules", "questions", "evidence", "conflicts", "scenarios"):
        if not any(path.parts[-4] == required for path in paths):
            raise ValueError(f"Missing {required} artifacts for income year {year}")
    paths.extend(sorted((root / "sources/irs" / str(year)).glob("*.yaml")))
    paths.extend(sorted((root / "corpora" / f"irs-{year}").glob("*.json")))
    paths.append(root / "sources/federal/citations.yaml")
    paths.extend(sorted((root / "reviews/federal" / str(year)).glob("*.md")))
    source_manifest = root / "corpora" / f"irs-{year}" / "source-manifest.json"
    if source_manifest.exists():
        for entry in json.loads(source_manifest.read_text())["entries"]:
            snapshot = root / entry["local_path"]
            if not snapshot.resolve().is_relative_to(root.resolve()):
                raise ValueError("Source snapshot must stay within the knowledge root: " + entry["source_id"])
            if not snapshot.is_file() or hashlib.sha256(snapshot.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError("Missing or changed source snapshot: " + entry["source_id"])
            paths.append(snapshot)
    entries = [{"path": path.relative_to(root).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
               for path in sorted(set(paths))]
    graphs = sorted((root.parent / "backend/src/main/resources/tax").glob("*.xml"))
    graphs += sorted((root.parent / "tax-advisor/src/main/resources/tax-plan").glob("*.xml"))
    graph_entries = [{"path": path.relative_to(root.parent).as_posix(),
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in graphs]
    payload = json.dumps({"artifacts": entries, "runtime_graph_files": graph_entries}, sort_keys=True, separators=(",", ":"))
    version = hashlib.sha256(payload.encode()).hexdigest()
    return {"schema_version": 1, "release_id": f"federal-{year}-{version[:16]}", "tax_year": year,
            "jurisdiction": "federal", "status": "draft", "content_sha256": version,
            "human_approvals": [], "required_reviewers": ["tax_specialist", "engineering_owner", "content_owner"],
            "scope": ["single-business Schedule C", "Schedule SE", "ordinary-rate income-tax planning",
                      "simple-method QBI with active-business minimum", "estimated-payment planning"],
            "limitations": ["Form 8995-A limits and phase-in calculations are not modeled; QBI above threshold is an upper bound",
                            "Credits, preferential capital-gains rates, itemized deductions, NIIT, AMT, and special personal deductions are not modeled",
                            "Only single, MFJ, and MFS are supported", "Monthly projections assume even income within their observation periods"],
            "artifacts": entries, "runtime_graph_files": graph_entries}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--tax-year", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", type=Path)
    args = parser.parse_args(argv)
    try:
        release = build(args.root, args.tax_year)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    encoded = json.dumps(release, indent=2, sort_keys=True) + "\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(encoded)
    if args.package:
        args.package.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            contents = [(entry["path"], (args.root / entry["path"]).read_bytes()) for entry in release["artifacts"]]
            contents += [("runtime-graph/" + entry["path"], (args.root.parent / entry["path"]).read_bytes())
                         for entry in release["runtime_graph_files"]]
            contents.append((f"releases/federal/{args.tax_year}/manifest.json", encoded.encode()))
            for name, data in sorted(contents):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
    print(f"Review candidate: {release['release_id']} ({len(release['artifacts'])} artifacts; human approval pending)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
