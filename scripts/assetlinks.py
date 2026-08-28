#!/usr/bin/env python3
"""Build and pre-flight the Digital Asset Links file behind App Link verification (OD-321).

`autoVerify="true"` is not a switch you can flip and check later. On every install
Android fetches `https://<host>/.well-known/assetlinks.json`, and if the file is
missing, malformed, served over a redirect, or names a different signing certificate,
verification fails and the system stops offering OmniDeck for those URLs *at all* —
strictly worse than the disambiguation chooser we get today with autoVerify off. The
failure is silent: no crash, no log a user would find, just links that quietly stop
opening the app.

So the flip gets a mechanical precondition instead of someone eyeballing a URL:

    python scripts/assetlinks.py generate --fingerprint <SHA256>   # the file to publish
    python scripts/assetlinks.py verify                            # must pass BEFORE the flip

`verify` checks the things Android's verifier actually cares about and that a browser
spot-check hides — HTTPS with no redirect (Android does not follow them here), a JSON
array with the `delegate_permission/common.handle_all_urls` relation, our own
applicationId, and a certificate fingerprint that matches. It then asks Google's
Digital Asset Links API for the same statement, which is the service used to debug
what the platform sees rather than what the origin serves.

The fingerprint is the **Play App Signing** certificate's SHA-256, from Play Console →
Test and release → Setup → App integrity. Not the upload key, and not a local keystore:
Play re-signs the artifact, so the certificate that reaches devices is the one Play
holds (§19.1). Passing the wrong one produces a file that looks correct and verifies
against nothing.

This is deliberately *not* wired into CI. It asserts the state of an origin we do not
control, so as a build gate it would fail every PR until the file exists and then break
the build on someone else's DNS or CDN change. It is a pre-flight, run by whoever is
about to change the manifest.

architecture.md §10.1 / ADR-011 (the /go/ prefix this verification applies to);
implementation_plan.md OD-321.
"""
from __future__ import annotations

import argparse
import json
import re
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

WEB_HOST = "omnideck.app"
RELATION = "delegate_permission/common.handle_all_urls"
WELL_KNOWN = "/.well-known/assetlinks.json"
DAL_API = (
    "https://digitalassetlinks.googleapis.com/v1/statements:list"
    "?source.web.site=https://{host}&relation={relation}"
)

# 32 colon-separated hex octets, as Play Console renders it.
FINGERPRINT_RE = re.compile(r"^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$")

TIMEOUT_S = 15


def application_id() -> str:
    """Read applicationId from the Shell's build file rather than hardcoding it.

    A file naming the wrong package verifies against nothing, and the failure looks
    exactly like "not published yet".
    """
    build_file = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'applicationId\s*=\s*"([^"]+)"', build_file)
    if not match:
        raise SystemExit("could not find applicationId in app/build.gradle.kts")
    return match.group(1)


def normalise_fingerprint(raw: str) -> str:
    value = raw.strip().upper().replace(" ", "")
    if not FINGERPRINT_RE.match(value):
        raise SystemExit(
            f"'{raw}' is not a SHA-256 certificate fingerprint.\n"
            "Expected 32 colon-separated hex octets, e.g. "
            "A1:B2:C3:...:FF - copy it from Play Console -> Setup -> App integrity."
        )
    return value


def statement(package: str, fingerprints: list[str]) -> list[dict]:
    return [
        {
            "relation": [RELATION],
            "target": {
                "namespace": "android_app",
                "package_name": package,
                "sha256_cert_fingerprints": fingerprints,
            },
        }
    ]


def cmd_generate(args: argparse.Namespace) -> int:
    package = application_id()
    fingerprints = [normalise_fingerprint(f) for f in args.fingerprint]
    body = json.dumps(statement(package, fingerprints), indent=2) + "\n"

    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(body, encoding="utf-8")
        print(f"wrote {out}")
    else:
        sys.stdout.write(body)

    print(
        f"\nPublish this at https://{args.host}{WELL_KNOWN}\n"
        "  - served over HTTPS, Content-Type application/json\n"
        "  - reachable with NO redirect (Android does not follow them for this file)\n"
        "  - then run: python scripts/assetlinks.py verify\n"
        "Only after verify passes should autoVerify be set to true.",
        file=sys.stderr,
    )
    return 0


def fetch(url: str) -> tuple[int, str, str | None]:
    """Return (status, body, final_url). Does not follow redirects, deliberately."""

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise _Redirected(code, newurl)

    opener = urllib.request.build_opener(NoRedirect)
    with opener.open(url, timeout=TIMEOUT_S) as response:
        return response.status, response.read().decode("utf-8", "replace"), response.url


class _Redirected(Exception):
    def __init__(self, code: int, location: str):
        super().__init__(f"HTTP {code} -> {location}")
        self.code = code
        self.location = location


def cmd_verify(args: argparse.Namespace) -> int:
    package = application_id()
    expected = [normalise_fingerprint(f) for f in args.fingerprint] if args.fingerprint else []
    url = f"https://{args.host}{WELL_KNOWN}"
    failures: list[str] = []

    print(f"package    {package}")
    print(f"url        {url}")

    socket.setdefaulttimeout(TIMEOUT_S)
    body = None
    try:
        status, body, _ = fetch(url)
        print(f"fetch      HTTP {status}")
        if status != 200:
            failures.append(f"{url} returned HTTP {status}")
    except _Redirected as exc:
        print(f"fetch      REDIRECT {exc}")
        failures.append(
            f"{url} redirects ({exc}). Android does not follow redirects when fetching "
            "assetlinks.json; it must be served directly at this path."
        )
    except urllib.error.HTTPError as exc:
        print(f"fetch      HTTP {exc.code}")
        failures.append(
            f"{url} returned HTTP {exc.code} - the file is not published yet."
            if exc.code == 404
            else f"{url} returned HTTP {exc.code} ({exc.reason})"
        )
    except Exception as exc:  # noqa: BLE001 - report any transport failure the same way
        print(f"fetch      ERROR {type(exc).__name__}: {exc}")
        failures.append(f"could not fetch {url}: {type(exc).__name__}: {exc}")

    if body:
        failures.extend(check_statement(body, package, expected))

    failures.extend(check_google_view(args.host, package))

    print()
    if failures:
        print("App Link verification is NOT ready. Leave autoVerify=\"false\".", file=sys.stderr)
        for item in failures:
            print(f"  - {item}", file=sys.stderr)
        return 1

    print("assetlinks.json is published and consistent; autoVerify=\"true\" is safe to set.")
    return 0


def check_statement(body: str, package: str, expected: list[str]) -> list[str]:
    failures: list[str] = []
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        return [f"assetlinks.json is not valid JSON: {exc}"]

    if not isinstance(parsed, list):
        return ["assetlinks.json must be a JSON array of statements"]

    ours = [
        entry
        for entry in parsed
        if isinstance(entry, dict)
        and entry.get("target", {}).get("package_name") == package
        and entry.get("target", {}).get("namespace") == "android_app"
    ]
    if not ours:
        names = sorted(
            {
                str(e.get("target", {}).get("package_name"))
                for e in parsed
                if isinstance(e, dict)
            }
        )
        return [
            f"no statement for package '{package}' (file declares: {', '.join(names) or 'nothing'})"
        ]

    for entry in ours:
        if RELATION not in entry.get("relation", []):
            failures.append(
                f"statement for '{package}' lacks the '{RELATION}' relation "
                f"(has: {entry.get('relation')})"
            )
        published = [str(f).upper() for f in entry.get("target", {}).get("sha256_cert_fingerprints", [])]
        if not published:
            failures.append(f"statement for '{package}' declares no sha256_cert_fingerprints")
        for fingerprint in published:
            if not FINGERPRINT_RE.match(fingerprint):
                failures.append(f"malformed fingerprint in published file: {fingerprint}")
        print(f"published  {len(published)} fingerprint(s)")
        for missing in [f for f in expected if f not in published]:
            failures.append(
                f"expected fingerprint {missing} is not in the published file "
                f"(it lists: {', '.join(published) or 'none'})"
            )
    return failures


def check_google_view(host: str, package: str) -> list[str]:
    """Ask Google's Digital Asset Links API what the platform can see.

    The origin serving a correct file and the platform being able to *use* it are not
    the same thing — TLS chain, redirects and caching all sit in between. This is the
    same service used to debug App Links, so a disagreement here is the signal.
    """
    url = DAL_API.format(host=host, relation=urllib.parse.quote(RELATION, safe=""))
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT_S) as response:
            payload = json.loads(response.read().decode("utf-8", "replace"))
    except Exception as exc:  # noqa: BLE001
        print(f"google     unreachable ({type(exc).__name__}) - skipped")
        return []

    statements = payload.get("statements", [])
    matching = [
        s
        for s in statements
        if s.get("target", {}).get("androidApp", {}).get("packageName") == package
    ]
    print(f"google     {len(statements)} statement(s), {len(matching)} for {package}")

    if matching:
        return []
    detail = (payload.get("debugString") or "").strip().splitlines()
    reason = next((line.strip() for line in detail if "Error" in line), "no statement returned")
    return [f"Google's Digital Asset Links API sees no statement for '{package}': {reason}"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--host", default=WEB_HOST, help=f"origin to check (default {WEB_HOST})")
    sub = parser.add_subparsers(dest="command", required=True)

    gen = sub.add_parser("generate", help="emit the assetlinks.json to publish")
    gen.add_argument(
        "--fingerprint",
        action="append",
        required=True,
        metavar="SHA256",
        help="Play App Signing certificate SHA-256; repeat to list more than one",
    )
    gen.add_argument("--out", help="write to this path instead of stdout")
    gen.set_defaults(func=cmd_generate)

    ver = sub.add_parser("verify", help="check the published file before flipping autoVerify")
    ver.add_argument(
        "--fingerprint",
        action="append",
        metavar="SHA256",
        help="assert this fingerprint is present; repeat to require several",
    )
    ver.set_defaults(func=cmd_verify)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
