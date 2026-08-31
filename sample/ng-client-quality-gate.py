#!/usr/bin/env python3
"""Static quality gates for generated yaver-ng-client packages.

Checks are run over the BUILT dist artifact (declarations + FESM bundles).
Comments are stripped before pattern checks so that documentation examples
do not cause false positives.

Exit code 0 = all gates green; 1 = gate violation (always a hard failure).
"""
import json
import re
import sys
from pathlib import Path

DIST = Path(sys.argv[1]).resolve()

VIOLATIONS: list[str] = []


def fail(msg: str) -> None:
    VIOLATIONS.append(msg)


def strip_comments(source: str) -> str:
    """Removes /* */ and // comments (string-literal aware enough for gates)."""
    out = []
    i, n = 0, len(source)
    in_string = False
    quote = ""
    while i < n:
        c = source[i]
        if in_string:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(source[i + 1])
                i += 2
                continue
            if c == quote:
                in_string = False
            i += 1
            continue
        if c in ("'", '"', "`"):
            in_string = True
            quote = c
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and source[i + 1] == "*":
            end = source.find("*/", i + 2)
            i = n if end == -1 else end + 2
            out.append(" ")
            continue
        if c == "/" and i + 1 < n and source[i + 1] == "/":
            end = source.find("\n", i)
            i = n if end == -1 else end
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def main() -> int:
    if not DIST.is_dir():
        fail(f"dist directory not found: {DIST}")
        print_report()
        return 1

    dts = sorted(DIST.rglob("*.d.ts"))
    mjs = sorted(DIST.rglob("*.mjs"))
    if not dts or not mjs:
        fail("dist must contain .d.ts declarations and .mjs bundles (APF)")

    # ---------------------------------------------------------------
    # 1. No public `any`, no @ts-ignore, no untyped observe overloads
    # ---------------------------------------------------------------
    forbidden = [
        (r"@ts-ignore|@ts-expect-error", "ts-ignore/expect-error comment"),
        (r":\s*any\b", "explicit `: any`"),
        (r"<any>", "explicit `<any>`"),
        (r"\bany\[\]", "explicit `any[]`"),
        (r"Observable<any>", "Observable<any>"),
        (r"observe\s*:\s*any", "observe: any"),
        (r"as\s+any\b", "cast `as any`"),
    ]
    for p in dts + mjs:
        code = strip_comments(read(p))
        for pattern, label in forbidden:
            for match in re.finditer(pattern, code):
                line = code[: match.start()].count("\n") + 1
                fail(f"{p.relative_to(DIST)}:{line}: {label}")

    # ---------------------------------------------------------------
    # 2. No zone.js, no NgZone, no generated provideHttpClient
    # ---------------------------------------------------------------
    for p in dts + mjs:
        code = strip_comments(read(p))
        if re.search(r"['\"]zone\.js['\"]", code):
            fail(f"{p.relative_to(DIST)}: references zone.js")
        if re.search(r"\bNgZone\b", code):
            fail(f"{p.relative_to(DIST)}: uses NgZone")
        if re.search(r"\bprovideHttpClient\s*\(", code):
            fail(f"{p.relative_to(DIST)}: calls provideHttpClient (app owns the HTTP stack)")
        if re.search(r"\.subscribe\s*\(", code) and p.suffix == ".d.ts":
            fail(f"{p.relative_to(DIST)}: generated .subscribe() call in declarations")

    # ---------------------------------------------------------------
    # 3. Package manifest: peers, no runtime build tooling, no zone.js
    # ---------------------------------------------------------------
    manifest_path = DIST / "package.json"
    if manifest_path.exists():
        manifest = json.loads(read(manifest_path))
        deps = manifest.get("dependencies", {}) or {}
        peers = manifest.get("peerDependencies", {}) or {}
        if "zone.js" in {**deps, **peers}:
            fail("package.json declares zone.js")
        if "ng-packagr" in {**deps, **peers}:
            fail("package.json declares a runtime dependency on ng-packagr")
        for pkg in ("@angular/core", "@angular/common"):
            if pkg not in peers:
                fail(f"package.json is missing the {pkg} peer dependency")
        if manifest.get("sideEffects") is not False:
            fail("package.json must declare sideEffects: false")
        exports = manifest.get("exports", {})
        for key, value in exports.items():
            for variant in value.values() if isinstance(value, dict) else [value]:
                if isinstance(variant, str) and "/src/" in variant:
                    fail(f"exports['{key}'] points at generated source: {variant}")
        for entry in ("./forms", "./testing"):
            if entry not in exports:
                fail(f"package.json is missing the {entry} secondary entrypoint")

    # ---------------------------------------------------------------
    # 4. No compatibility/legacy directories anywhere in the artifact
    # ---------------------------------------------------------------
    for banned in ("legacy", "rxjs-compat", "v1", "v2"):
        if (DIST / banned).exists() or (DIST / "src" / banned).exists():
            fail(f"forbidden compatibility directory '{banned}' present")

    print_report()
    return 1 if VIOLATIONS else 0


def print_report() -> None:
    if VIOLATIONS:
        print(f"QUALITY GATE FAILED — {len(VIOLATIONS)} violation(s):")
        for v in VIOLATIONS:
            print(f"  - {v}")
    else:
        print("QUALITY GATE PASSED: no `any`, no @ts-ignore, no zone.js, "
              "no provideHttpClient, no source-path exports.")


if __name__ == "__main__":
    sys.exit(main())
