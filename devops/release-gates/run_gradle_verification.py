#!/usr/bin/env python3
"""
Dual-Substrate Hardware-Aware Gradle Verification Wrapper.
Probes Node Alpha (300.0s) vs Node Beta (1800.0s), configures JDK/Android SDK paths dynamically
via Path.home(), runs './gradlew test assembleDebug --no-daemon', and executes verify_acceptance_suite.py.
"""
import os
import platform
import subprocess
import sys
from pathlib import Path


def main() -> int:
    arch = platform.machine().lower()
    is_alpha = arch in ("aarch64", "arm64") or "com.termux" in os.environ.get("PREFIX", "")
    node_name = "Alpha" if is_alpha else "Beta"
    timeout_limit = 300.0 if is_alpha else 1800.0
    print(f"[Node {node_name}] Initializing OSIRIS Android Gradle build & test suite with {timeout_limit}s horizon...")

    home = Path.home()
    repo_root = Path(__file__).resolve().parents[2]
    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    env["PYTHONIOENCODING"] = "utf-8"
    env["JAVA_HOME"] = str(home / ".local" / "jdk-21")
    env["ANDROID_HOME"] = str(home / "Android" / "Sdk")
    env["PATH"] = f"{home / '.local' / 'bin'}:{home / '.local' / 'jdk-21' / 'bin'}:{env.get('PATH', '')}"

    gradle_cmd = [str(repo_root / "gradlew"), "test", "assembleDebug", "--no-daemon"]
    try:
        res = subprocess.run(gradle_cmd, cwd=repo_root, env=env, timeout=timeout_limit)
        if res.returncode != 0:
            return res.returncode
        verify_cmd = [sys.executable, str(repo_root / "devops" / "release-gates" / "verify_acceptance_suite.py")]
        vres = subprocess.run(verify_cmd, cwd=repo_root, env=env, timeout=timeout_limit)
        return vres.returncode
    except subprocess.TimeoutExpired:
        print(f"\n[Node {node_name}] Execution halted: {timeout_limit}s thermal/temporal horizon reached.")
        return 124


if __name__ == "__main__":
    sys.exit(main())
