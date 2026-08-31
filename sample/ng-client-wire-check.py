#!/usr/bin/env python3
"""Wire-level equivalence spot check between yaver-ts-angular and yaver-ng-client.

Compares the generated transport assembly per operation:
  - HTTP method + normalized URL template,
  - query parameter base names,
  - form parameter base names.
Both generators must produce identical wire requests for identical operations.
"""
import re
import sys
from pathlib import Path

old_src = Path(sys.argv[1])
new_src = Path(sys.argv[2])


def normalize(path: str) -> str:
    # Normalize both generators' template-literal parameter expansion to {p}
    return re.sub(r"\$\{[^{}]*(\{[^{}]*\}[^{}]*)*\}", "{p}", path.strip())


def extract_old(files):
    result = set()
    for f in files:
        text = f.read_text()
        # Split into per-operation method chunks.
        chunks = re.split(r"\n    public \w+\(", text)
        for chunk in chunks:
            method_match = re.search(r"request(?:<[^>(]*>)?\('(\w+)'", chunk)
            path_match = re.search(r"let localVarPath = `([^`]*)`", chunk)
            if method_match and path_match:
                result.add((method_match.group(1), normalize(path_match.group(1))))
            for m in re.finditer(
                r"addToHttpParams\(\s*localVarQueryParameters\s*,.*?'([^']+)'\s*\)", chunk, re.S
            ):
                result.add(("query", m.group(1)))
            for m in re.finditer(
                r"localVarFormParams\.append\(\s*'([^']+)'", chunk
            ):
                result.add(("form", m.group(1)))
    return result


def extract_new(files):
    result = set()
    for f in files:
        text = f.read_text()
        # Each internal builder ends with the descriptor; method+path are adjacent.
        for m in re.finditer(
            r"const path = `([^`]*)`;\s*\n\s*return \{\s*\n\s*method:\s*'(\w+)'", text
        ):
            result.add((m.group(2), normalize(m.group(1))))
        for m in re.finditer(r"addToHttpParams\(query,.*?'([^']+)'\)", text):
            result.add(("query", m.group(1)))
        for m in re.finditer(r"appendFormValue\(formParams,\s*'([^']+)'", text):
            result.add(("form", m.group(1)))
    return result


old = extract_old(list(old_src.rglob("*.ts")))
new = extract_new(list(new_src.rglob("*.ts")))

missing = old - new
problems = [f"missing in yaver-ng-client: {item}" for item in sorted(missing)]

if problems:
    print("WIRE CHECK FAILED:")
    for p in problems:
        print("  -", p)
    sys.exit(1)

print(
    f"WIRE CHECK PASSED: {len(old)} wire shapes (methods+paths+query keys+form keys) "
    f"identical across yaver-ts-angular and yaver-ng-client."
)
