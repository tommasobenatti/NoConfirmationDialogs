#!/usr/bin/env python3
"""Report executed production code after Paper/Bungee's mandatory class remapping."""
import argparse
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jacoco-cli', type=Path, required=True)
    args = parser.parse_args()
    target = ROOT / 'target'
    classes = target / 'coverage-runtime-classes'
    shutil.copytree(target / 'classes', classes, dirs_exist_ok=True)
    # These platform-only classes are transformed by their host before JaCoCo sees them.
    # Use the exact dumped runtime bytes, preserving the original source line mapping.
    for platform, names in [('runtime-1.21.6', ['NcdPlugin', 'PaperPackets']), ('network-bungee', ['BungeePlugin', 'BungeePackets'])]:
        for name in names:
            candidates = list((target / 'runtime-dump' / platform / 'io/github/tommasobenatti/ncd').glob(name + '.*.class'))
            latest = max(candidates, key=lambda path: path.stat().st_mtime)
            shutil.copy2(latest, classes / 'io/github/tommasobenatti/ncd' / (name + '.class'))
    base = ['java', '-jar', str(args.jacoco_cli.resolve())]
    execution_files = [target / name for name in ['jacoco.exec', 'proxy-velocity.exec', 'proxy-bungee.exec', 'runtime-1.21.6.exec', 'network-velocity.exec', 'network-bungee.exec']]
    subprocess.run(base + ['merge'] + [str(path) for path in execution_files] + ['--destfile', str(target / 'combined.exec')], check=True)
    subprocess.run(base + ['report', str(target / 'combined.exec'), '--classfiles', str(classes), '--sourcefiles', str(ROOT / 'src/main/java'), '--xml', str(target / 'native-coverage.xml'), '--html', str(target / 'site/native-coverage')], check=True)
    root = ET.parse(target / 'native-coverage.xml').getroot()
    counter = root.find("counter[@type='LINE']")
    covered, missed = int(counter.get('covered')), int(counter.get('missed'))
    ratio = covered / (covered + missed)
    print(f'Executed production lines: {covered}/{covered + missed} ({ratio:.1%})')
    raise SystemExit(0 if ratio >= .8 else 1)


if __name__ == '__main__':
    main()
