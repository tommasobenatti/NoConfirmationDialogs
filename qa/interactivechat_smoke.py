#!/usr/bin/env python3
"""Isolated, actual InteractiveChat/PacketEvents distribution integration check.

Supply official IC 2026.1.3.0, addon 1.3.1, PE 2.14.0, Vault and PlaceholderAPI
JARs in WORK/plugins-source, plus compiled InteractiveChatProbe in WORK/probe-classes.
Host bootstrap and its prepared cache are supplied explicitly; no production files are read.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--host-jar', type=Path, required=True)
    parser.add_argument('--host-cache', type=Path, required=True)
    parser.add_argument('--ncd-jar', type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--expect-red', action='store_true')
    parser.add_argument('--accept-eula', action='store_true', required=True)
    args = parser.parse_args()
    if not args.label or any(not (c.isascii() and (c.isalnum() or c in '_-')) for c in args.label):
        parser.error('--label must be a simple directory name')
    work = args.work_dir.resolve()
    folder = work / args.label
    plugins = folder / 'plugins'; plugins.mkdir(parents=True, exist_ok=True)
    for name in ['libraries', 'versions', 'cache']:
        source = args.host_cache.resolve() / name
        destination = folder / name
        if source.exists() and not destination.exists(): destination.symlink_to(source, target_is_directory=True)
    for old in plugins.glob('NoConfirmationDialogs-*.jar'): old.unlink()
    shutil.copy2(args.ncd_jar.resolve(), plugins / args.ncd_jar.name)
    for source in (work / 'plugins-source').glob('*.jar'): shutil.copy2(source, plugins / source.name)
    with zipfile.ZipFile(plugins / 'NcdInteractiveChatProbe.jar', 'w') as jar:
        path = 'io/github/tommasobenatti/ncd/qa/InteractiveChatProbe.class'
        jar.write(work / 'probe-classes' / path, path)
        jar.writestr('plugin.yml', '''name: NcdInteractiveChatProbe
version: '1'
main: io.github.tommasobenatti.ncd.qa.InteractiveChatProbe
api-version: '1.21.6'
depend: [NoConfirmationDialogs, InteractiveChat, InteractiveChat-PacketEvents, packetevents]
''')
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text('''server-ip=127.0.0.1
server-port=26091
online-mode=false
network-compression-threshold=-1
view-distance=2
simulation-distance=2
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:the_void"}
generate-structures=false
max-tick-time=0
enable-status=false
''')
    log = folder / 'compat.log'
    command = ['java', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms128M', '-Xmx768M']
    if args.expect_red: command.append('-Dncd.compat.expect-red=true')
    command += ['-jar', str(args.host_jar.resolve()), '--nogui']
    with log.open('w') as output:
        process = subprocess.run(command, cwd=folder, stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, timeout=240)
    content = log.read_text(errors='replace')
    marker = 'NCD_ICPE_REPRODUCED' if args.expect_red else 'NCD_ICPE_PASS'
    passed = process.returncode == 0 and marker in content and 'NCD_ICPE_FAIL' not in content
    if not args.expect_red:
        passed = passed and not any(text in content for text in ['EndBinaryTag', 'Error occurred', 'OutOfMemoryError'])
    report = {'label': args.label, 'expected_original_failure': args.expect_red, 'passed': passed,
              'exit_code': process.returncode, 'ncd_sha256': hashlib.sha256(args.ncd_jar.read_bytes()).hexdigest(),
              'host_sha256': hashlib.sha256(args.host_jar.read_bytes()).hexdigest(),
              'plugins': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (work / 'plugins-source').glob('*.jar')},
              'evidence': [line for line in content.splitlines() if 'NCD_ICPE_' in line or 'NCD_COMPAT_VERSION' in line]}
    (folder / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
