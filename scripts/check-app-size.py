#!/usr/bin/env python3
"""Enforce the app-size budgets of implementation_plan.md §16 (OD-312).

    base APK <= 25 MB        per-split <= 8 MB

Budgets only work if they start at zero and are checked on every build, so this
runs in CI against the release bundle rather than being measured by hand when
someone remembers.

**What it measures, and why that is the honest number.** An `.aab` is not what a
user downloads: Play splits it per-device and re-signs, so the only exact figure
comes from `bundletool build-apks --connected-device`, which needs a device and
therefore cannot gate a PR. What this reads instead is the *compressed* size of
each module's entries inside the bundle. That is the same content, packed the
same way, minus the per-device pruning of density, ABI and language resources —
so it always over-estimates the real download and never under-reports a breach.
A budget that errs towards failing is the right direction for a gate; when the
number gets close, measure it properly with bundletool before relaxing anything
here.

Usage:
    python scripts/check-app-size.py [path/to/app-release.aab]
"""

from __future__ import annotations

import sys
import zipfile
from collections import defaultdict
from pathlib import Path

MB = 1024 * 1024
BASE_BUDGET_BYTES = 25 * MB
SPLIT_BUDGET_BYTES = 8 * MB

DEFAULT_BUNDLE = Path("app/build/outputs/bundle/release/app-release.aab")

# Bundle-level metadata rather than shipped content: BUNDLE-METADATA holds the
# dex-mapping and R8 files Play consumes and never delivers, and META-INF holds
# the signature. Counting them against a module's budget would charge the base
# for bytes no device receives.
NON_MODULE_ROOTS = frozenset({"BUNDLE-METADATA", "META-INF"})


def module_sizes(bundle: Path) -> dict[str, int]:
    """Compressed bytes per bundle module, keyed by module name (`base`, `notes`, …)."""
    sizes: dict[str, int] = defaultdict(int)
    with zipfile.ZipFile(bundle) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            root = info.filename.split("/", 1)[0]
            if root in NON_MODULE_ROOTS or "/" not in info.filename:
                continue
            sizes[root] += info.compress_size
    return dict(sizes)


def main() -> int:
    bundle = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_BUNDLE
    if not bundle.is_file():
        print(f"No bundle at {bundle}.")
        print("Build one first: ./gradlew :app:bundleRelease")
        return 1

    sizes = module_sizes(bundle)
    if "base" not in sizes:
        print(f"{bundle} contains no 'base' module — is this an Android App Bundle?")
        return 1

    print(f"App size budgets (implementation_plan.md §16), from {bundle}:\n")
    failures: list[str] = []
    for name in sorted(sizes, key=lambda n: (n != "base", n)):
        size = sizes[name]
        budget = BASE_BUDGET_BYTES if name == "base" else SPLIT_BUDGET_BYTES
        ok = size <= budget
        verdict = "OK" if ok else "OVER"
        print(
            f"  {name:<24} {size / MB:6.2f} MB  of {budget // MB:2d} MB"
            f"  ({size / budget:5.1%})  {verdict}"
        )
        if not ok:
            failures.append(f"{name} is {size / MB:.2f} MB against a {budget // MB} MB budget")

    print()
    if failures:
        for failure in failures:
            print(f"OVER BUDGET: {failure}")
        print(
            "\nA module over budget is a release problem, not a CI inconvenience: "
            "download size is the single biggest predictor of install abandonment. "
            "Find the growth before raising the number."
        )
        return 1

    print(f"All {len(sizes)} module(s) within budget.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
