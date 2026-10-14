#!/usr/bin/env python3
"""
OSIRIS Android Provenance & Secret Scanner (AT-BLD-02, AT-SEC-05, AT-SEC-08, AT-REL-03).
Dynamically detects Node Alpha (300.0s) vs Node Beta (1800.0s) substrate without hardcoded paths.
"""
import hashlib
import json
import os
import platform
import re
import sys
from pathlib import Path


def detect_substrate() -> dict:
    arch = platform.machine().lower()
    is_alpha = arch in ("aarch64", "arm64") or "com.termux" in os.environ.get("PREFIX", "")
    return {
        "node_role": "Alpha" if is_alpha else "Beta",
        "arch": arch,
        "timeout_horizon_sec": 300.0 if is_alpha else 1800.0,
    }


SECRET_PATTERNS = [
    re.compile(r"AIza[0-9A-Za-z\-_]{35}"),
    re.compile(r"ghp_[0-9A-Za-z]{36}"),
    re.compile(r"-----BEGIN (?:RSA |EC )?PRIVATE KEY-----"),
    re.compile(r"sk-[0-9A-Za-z]{40,}"),
]

PROHIBITED_PERMISSIONS = [
    "READ_SMS",
    "RECEIVE_SMS",
    "READ_CONTACTS",
    "READ_CALL_LOG",
    "ACCESS_FINE_LOCATION",
    "SYSTEM_ALERT_WINDOW",
    "WRITE_EXTERNAL_STORAGE",
]


def scan_repository(repo_root: Path) -> dict:
    findings = []
    scanned_files = 0
    for path in repo_root.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(repo_root).as_posix()
        if any(part.startswith(".") or part in ("build", ".gradle") for part in path.parts):
            continue
        if path.suffix in (".kt", ".kts", ".xml", ".json", ".yml", ".yaml", ".properties", ".md"):
            scanned_files += 1
            text = path.read_text(encoding="utf-8", errors="ignore")
            for pat in SECRET_PATTERNS:
                if pat.search(text):
                    findings.append(f"Secret pattern {pat.pattern} matched in {rel}")

    manifest_path = repo_root / "app" / "src" / "main" / "AndroidManifest.xml"
    manifest_text = manifest_path.read_text(encoding="utf-8") if manifest_path.exists() else ""
    for perm in PROHIBITED_PERMISSIONS:
        if perm in manifest_text:
            findings.append(f"Prohibited permission {perm} present in AndroidManifest.xml")

    apk_path = repo_root / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    apk_sha256 = None
    if apk_path.exists():
        apk_sha256 = hashlib.sha256(apk_path.read_bytes()).hexdigest()

    return {
        "substrate": detect_substrate(),
        "scanned_files": scanned_files,
        "findings": findings,
        "apk_exists": apk_path.exists(),
        "apk_sha256": apk_sha256,
        "passed": len(findings) == 0,
    }


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    report = scan_repository(root)
    print(json.dumps(report, indent=2))
    sys.exit(0 if report["passed"] else 1)
