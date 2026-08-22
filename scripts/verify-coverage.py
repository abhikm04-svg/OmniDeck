#!/usr/bin/env python3
"""Assert the coverage numbers CI *publishes* meet the floors CI *enforces*.

This exists because those two turned out not to be the same thing. Over three CI
runs, `koverVerify` passed on :platform:kernel while the report.xml uploaded by the
same job showed 75.8% against an 80% floor, and koverHtmlReport disagreed with
koverXmlReport from the same invocation — all three tasks executing fresh, no cache
hits, same configuration. Whatever the cause inside Kover, a gate whose verdict
cannot be reconciled with the artifact a human reads is not a gate anyone can trust.

So the published artifact is checked here, independently of Kover, against the same
floors in gradle.properties. If the report and the gate ever disagree again, this
fails the build and names the number, instead of the disagreement passing silently.

architecture.md §5.1 / implementation_plan.md §17 — the Phase 1 exit gate.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def floors() -> dict[str, int]:
    props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    out: dict[str, int] = {}
    for line in props.splitlines():
        line = line.strip()
        if line.startswith("omnideck.coverage.platform.min="):
            out["platform"] = int(line.split("=", 1)[1])
        elif line.startswith("omnideck.coverage.module.min="):
            out["modules"] = int(line.split("=", 1)[1])
    return out


def line_counter(report: Path) -> tuple[int, int] | None:
    """Covered/total from the report-level LINE counter (direct child of <report>)."""
    root = ET.parse(report).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            covered = int(counter.get("covered", 0))
            missed = int(counter.get("missed", 0))
            return covered, covered + missed
    return None


def main() -> int:
    limits = floors()
    if not limits:
        print("no coverage floors found in gradle.properties", file=sys.stderr)
        return 1

    failures: list[str] = []
    checked = 0

    for area, floor in sorted(limits.items()):
        for report in sorted((ROOT / area).glob("*/build/reports/kover/report.xml")):
            project = f":{area}:{report.parents[3].name}"
            result = line_counter(report)
            if result is None:
                failures.append(f"{project}: report.xml has no report-level LINE counter")
                continue
            covered, total = result
            if total == 0:
                print(f"  {project:<30} no measurable lines, skipped")
                continue
            pct = covered * 100.0 / total
            checked += 1
            status = "OK" if pct >= floor else f"BELOW {floor}%"
            print(f"  {project:<30} {covered:>5}/{total:<5} = {pct:5.1f}%  {status}")
            if pct < floor:
                failures.append(f"{project}: published coverage {pct:.1f}% is below the {floor}% floor")

    if checked == 0:
        print("no kover reports found — run koverXmlReport first", file=sys.stderr)
        return 1

    if failures:
        print("\nPublished coverage does not meet the floors:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print(f"\nAll {checked} measured projects meet their floor.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
