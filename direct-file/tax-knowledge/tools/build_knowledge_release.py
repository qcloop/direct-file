#!/usr/bin/env python3
"""Build a deterministic, hashed review candidate. This command never grants human approval."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

from validate_artifact_provenance import validate


def compiled_graph_entries(root: Path, service_jar: Path) -> list[dict]:
    """Inventory what the classpath actually contains, then verify its checked-in source matches."""
    compiled = []
    with zipfile.ZipFile(service_jar) as outer:
        for name in outer.namelist():
            if name.startswith("BOOT-INF/classes/"):
                resource = name.removeprefix("BOOT-INF/classes/")
                if resource.startswith(("tax/", "tax-plan/")) and resource.endswith(".xml") and resource.count("/") == 1:
                    compiled.append((resource, outer.read(name)))
            elif name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(outer.read(name))) as nested:
                    for resource in nested.namelist():
                        if resource.startswith(("tax/", "tax-plan/")) and resource.endswith(".xml") and resource.count("/") == 1:
                            compiled.append((resource, nested.read(resource)))
    entries, seen = [], set()
    for resource, data in compiled:
        if resource in seen:
            raise ValueError("Duplicate runtime graph resource: " + resource)
        seen.add(resource)
        module = "tax-advisor" if resource.startswith("tax-plan/") else "backend"
        source = root.parent / module / "src/main/resources" / resource
        digest = hashlib.sha256(data).hexdigest()
        if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError("Compiled graph does not match its source; rebuild the service and dependencies: " + resource)
        entries.append({"path": source.relative_to(root.parent).as_posix(), "classpath_resource": resource, "sha256": digest})
    if not entries:
        raise ValueError("No executable graph XML in the service jar")
    return sorted(entries, key=lambda entry: entry["path"])


def build(root: Path, year: int, service_jar: Path | None = None) -> dict:
    service_jar = service_jar or root.parent / "tax-advisor/target/tax-advisor-0.0.1-SNAPSHOT.jar"
    if not service_jar.is_file():
        raise ValueError("Build the tax-advisor service before packaging its knowledge release")
    args = argparse.Namespace(root=root, sources_dir=root / "sources/irs" / str(year), tax_year=year)
    provenance = validate(args)
    if provenance["errors"] or provenance["warnings"]:
        raise ValueError("Provenance must have zero errors and warnings: " + json.dumps(provenance))
    paths = []
    for family in ("rules", "questions", "evidence", "conflicts", "scenarios", "topics", "factgraph", "changes", "relevance"):
        paths.extend(sorted((root / family / "federal" / str(year)).glob("*.yaml")))
    for required in ("rules", "questions", "evidence", "conflicts", "scenarios"):
        if not any(path.parts[-4] == required for path in paths):
            raise ValueError(f"Missing {required} artifacts for income year {year}")
    paths.extend(sorted((root / "sources/irs" / str(year)).glob("*.yaml")))
    paths.extend(sorted((root / "corpora" / f"irs-{year}").glob("*.json")))
    paths.append(root / "sources/federal/citations.yaml")
    paths.extend(sorted((root / "reviews/federal" / str(year)).glob("*.md")))
    paths.extend(sorted((root / "runs").glob(f"irs-{year}-*.md")))
    inventory_readme = root / "corpora" / f"irs-{year}" / "README.md"
    if inventory_readme.is_file():
        paths.append(inventory_readme)
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
    graph_entries = compiled_graph_entries(root, service_jar)
    jar_digest = hashlib.sha256(service_jar.read_bytes()).hexdigest()
    payload = json.dumps({"artifacts": entries, "runtime_graph_files": graph_entries, "service_jar_sha256": jar_digest}, sort_keys=True, separators=(",", ":"))
    version = hashlib.sha256(payload.encode()).hexdigest()
    return {"schema_version": 1, "release_id": f"federal-{year}-{version[:16]}", "tax_year": year,
            "jurisdiction": "federal", "status": "draft", "content_sha256": version,
            "human_approvals": [], "required_reviewers": ["tax_specialist", "engineering_owner", "content_owner"],
            "scope": ["single-business Schedule C", "Schedule SE", "ordinary-rate income-tax planning",
                      "simple-method QBI with active-business minimum", "estimated-payment planning"],
            "limitations": ["Form 8995-A limits and phase-in calculations are not modeled; QBI above threshold is an upper bound",
                            "Credits, preferential capital-gains rates, itemized deductions, NIIT, AMT, and special personal deductions are not modeled",
                            "Only single, MFJ, and MFS are supported", "Monthly projections assume even income within their observation periods"],
            "artifacts": entries, "runtime_graph_files": graph_entries, "service_jar_sha256": jar_digest}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--tax-year", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", type=Path)
    parser.add_argument("--service-jar", type=Path, help="Matching executable service build; defaults to the tax-advisor target jar")
    args = parser.parse_args(argv)
    try:
        release = build(args.root, args.tax_year, args.service_jar)
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
