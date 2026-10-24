"""Run the fixture-only Groovy/shell checks; no Jenkins or Docker daemon is used."""

import os
import shutil
import subprocess
from pathlib import Path


def test_jev_deploy_contract():
    root = Path(__file__).resolve().parents[2]
    groovy = os.environ.get("MCD_GROOVY") or shutil.which("groovy")
    assert groovy, "Set MCD_GROOVY to a Groovy 2.4+ launcher to execute this contract"
    result = subprocess.run(
        [groovy, "test/groovy/mcd_jev_proxy_behaviour.groovy"],
        cwd=root,
        capture_output=True,
        text=True,
        timeout=180,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Jev contract: " in result.stdout
    assert "0 failed" in result.stdout
    assert "E1 env bytes identical" in result.stdout
    assert "E2 exactly one added line on all three paths" in result.stdout
