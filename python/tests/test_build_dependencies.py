"""Keep the native build classpaths aligned across supported host systems."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def test_native_builders_include_the_same_compilation_bundles():
    powershell = (ROOT / 'scripts/build-nomaven.ps1').read_text(encoding='utf-8')
    shell = (ROOT / 'scripts/build-nomaven.sh').read_text(encoding='utf-8')
    ps_array = re.search(r'\$need\s*=\s*@\((.*?)^\)', powershell, re.S | re.M)
    sh_array = re.search(r'^need=\((.*?)^\)', shell, re.S | re.M)
    assert ps_array and sh_array, 'Native builder dependency arrays were not found'
    ps_bundles = set(re.findall(r"^\s*'([a-zA-Z0-9_.-]+)'", ps_array.group(1), re.M))
    sh_bundles = set(re.findall(r'^\s*([a-zA-Z0-9_.-]+)\s*$', sh_array.group(1), re.M))
    # SWT has one platform-specific fragment: Bash resolves the host's fragment separately.
    ps_bundles = {name for name in ps_bundles if not name.startswith('org.eclipse.swt.')}
    assert ps_bundles == sh_bundles, (
        f'Only in PowerShell: {sorted(ps_bundles - sh_bundles)}; '
        f'only in Bash: {sorted(sh_bundles - ps_bundles)}'
    )
