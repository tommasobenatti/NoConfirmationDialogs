#!/usr/bin/env python3
"""Download checksum-verified Paper builds and probe native packets in isolated servers.

Requires a JDK 25 java on PATH and an already built `mvn verify` project.
This accepts Mojang's EULA for the disposable local test servers (--accept-eula).
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = ['1.21.6', '1.21.7', '1.21.8', '1.21.9', '1.21.10', '1.21.11', '26.1.1', '26.1.2', '26.2', '26.3']
AGENT = 'NCD-tests (https://github.com/tommasobenatti/NoConfirmationDialogs)'


def download(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': AGENT}), timeout=90) as response:
        return response.read()


def run(version, base, index):
    folder = base / ('runtime-' + version)
    folder.mkdir(parents=True, exist_ok=True)
    meta_path = base / (version + '.json')
    if not meta_path.exists():
        meta_path.write_text(json.dumps(json.loads(download('https://fill.papermc.io/v3/projects/paper/versions/' + version + '/builds'))[0]))
    metadata = json.loads(meta_path.read_text())
    artifact = metadata['downloads']['server:default']
    paper = base / ('paper-' + version + '.jar')
    if not paper.exists():
        paper.write_bytes(download(artifact['url']))
    digest = hashlib.sha256(paper.read_bytes()).hexdigest()
    expected = artifact['checksums']['sha256']
    if digest != expected:
        raise ValueError('Paper checksum mismatch: ' + version)
    plugins = folder / 'plugins'
    plugins.mkdir(exist_ok=True)
    for old in plugins.glob('NoConfirmationDialogs-*.jar'):
        if old.name != 'NoConfirmationDialogs-1.0.1-stable.jar': old.unlink()
    shutil.copy2(ROOT / 'target/NoConfirmationDialogs-1.0.1-stable.jar', plugins)
    with zipfile.ZipFile(plugins / 'NcdRuntimeProbe.jar', 'w') as jar:
        cls = ROOT / 'target/test-classes/io/github/tommasobenatti/ncd/RuntimeProbe.class'
        jar.write(cls, 'io/github/tommasobenatti/ncd/RuntimeProbe.class')
        jar.writestr('plugin.yml', '''name: NcdRuntimeProbe
version: '1'
main: io.github.tommasobenatti.ncd.RuntimeProbe
api-version: '1.21.6'
depend: [NoConfirmationDialogs]
commands:
  ncdprobe: {}
  ncdbackendblocked: {}
  ncdrestricted:
    permission: ncd.qa.restricted
permissions:
  ncd.qa.restricted:
    default: op
''')
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={25900 + index}
online-mode=false
network-compression-threshold=-1
view-distance=2
simulation-distance=2
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}}],"biome":"minecraft:the_void"}}
generate-structures=false
spawn-protection=0
max-tick-time=0
enable-status=false
''')
    log = folder / 'ncd-probe.log'
    with log.open('w') as stream:
        command = ['java', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms128M', '-Xmx512M', '-jar', str(paper), '--nogui']
        if os.environ.get('NCD_JACOCO_AGENT'):
            command.insert(1, '-javaagent:' + os.environ['NCD_JACOCO_AGENT'] + '=destfile=' + str(ROOT / 'target' / ('paper-' + version + '.exec')) + ',includes=io.github.tommasobenatti.ncd.*')
        proc = subprocess.run(command, cwd=folder, stdout=stream, stderr=subprocess.STDOUT, timeout=240)
    output = log.read_text()
    match = re.search(r'NCD_RUNTIME_PASS assertions=(\d+)', output)
    passed = proc.returncode == 0 and bool(match) and 'NCD_RUNTIME_FAIL' not in output and 'Could not' not in output
    plugin_sha = hashlib.sha256((plugins / 'NoConfirmationDialogs-1.0.1-stable.jar').read_bytes()).hexdigest()
    return {'version': version, 'build': metadata['id'], 'sha256': digest, 'candidate_sha256': plugin_sha, 'passed': passed, 'assertions': int(match[1]) if match else 0, 'exit_code': proc.returncode}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work-dir', type=Path, default=ROOT / 'qa/runtimes')
    parser.add_argument('--versions', nargs='+', default=VERSIONS)
    parser.add_argument('--accept-eula', action='store_true', required=True)
    args = parser.parse_args()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = {pool.submit(run, version, args.work_dir.resolve(), i): version for i, version in enumerate(args.versions)}
        for future in concurrent.futures.as_completed(futures):
            try:
                result = future.result()
            except Exception as error:
                result = {'version': futures[future], 'passed': False, 'error': str(error)}
            results.append(result)
            print(json.dumps(result), flush=True)
    (args.work_dir / 'paper-matrix.json').write_text(json.dumps(results, indent=2) + '\n')
    raise SystemExit(0 if all(result['passed'] for result in results) else 1)


if __name__ == '__main__':
    main()
