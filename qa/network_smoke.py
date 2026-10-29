#!/usr/bin/env python3
"""Loopback-only integration: direct Paper, Velocity -> Paper, Bungee -> Paper.

First run paper_matrix.py and compile qa/*ProbePlugin.java against the proxy JARs.
All fixtures and offline identities are disposable. No production configuration is read.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import time
import zipfile
import json
import os

ROOT = Path(__file__).resolve().parents[1]


def wait_log(proc, log, needle):
    for _ in range(1200):
        if needle in log.read_text(errors='replace'):
            return
        if proc.poll() is not None:
            raise RuntimeError('Process stopped: ' + str(log))
        time.sleep(.1)
    raise TimeoutError(str(log))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--node', required=True)
    args = parser.parse_args()
    base = args.work_dir.resolve()
    jar = ROOT / 'target/NoConfirmationDialogs-0.1.0-SNAPSHOT.jar'
    backend = base / 'runtime-1.21.6'
    shutil.copy2(jar, backend / 'plugins' / jar.name)
    processes = []
    results = []

    def start(folder, command, logfile):
        if os.environ.get('NCD_JACOCO_AGENT'):
            command.insert(1, '-javaagent:' + os.environ['NCD_JACOCO_AGENT'] + '=destfile=' + str(ROOT / 'target' / (folder.name + '.exec')) + ',includes=io.github.tommasobenatti.ncd.*,classdumpdir=' + str(ROOT / 'target/runtime-dump' / folder.name))
        log = folder / logfile
        stream = log.open('w')
        proc = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT, text=True)
        processes.append((proc, stream))
        return proc, log

    def client(port, mode):
        try:
            proc = subprocess.run([args.node, str(ROOT / 'qa/client.cjs'), str(base), str(port), mode], capture_output=True, text=True, timeout=35)
        except subprocess.TimeoutExpired as error:
            print(error.stdout, error.stderr, flush=True)
            raise
        print(proc.stdout, flush=True)
        if proc.returncode != 0 or 'NCD_CLIENT_PASS' not in proc.stdout:
            raise AssertionError(proc.stdout + proc.stderr)

    try:
        proc, backend_log = start(backend, ['java', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dncd.qa.keep-running=true', '-Xmx512M', '-jar', str(base / 'paper-1.21.6.jar'), '--nogui'], 'network-probe.log')
        wait_log(proc, backend_log, 'NCD_RUNTIME_PASS')
        client(25900, 'paper')
        for name in ['velocity', 'bungee']:
            folder = base / ('network-' + name)
            plugins = folder / 'plugins'; plugins.mkdir(parents=True, exist_ok=True)
            shutil.copy2(jar, plugins / jar.name)
            cls = 'VelocityProbePlugin' if name == 'velocity' else 'BungeeProbePlugin'
            with zipfile.ZipFile(plugins / 'NcdProxyProbe.jar', 'w') as fixture:
                for item in (base / 'proxy-qa-classes/io/github/tommasobenatti/ncd/qa').glob(cls + '*.class'):
                    fixture.write(item, 'io/github/tommasobenatti/ncd/qa/' + item.name)
                if name == 'velocity':
                    fixture.writestr('velocity-plugin.json', json.dumps({'id': 'ncdprobe', 'name': 'NcdProxyProbe', 'version': '1', 'main': 'io.github.tommasobenatti.ncd.qa.' + cls, 'dependencies': [{'id': 'ncd', 'optional': False}]}))
                else:
                    fixture.writestr('bungee.yml', 'name: NcdProxyProbe\nversion: "1"\nmain: io.github.tommasobenatti.ncd.qa.' + cls + '\ndepends: [NoConfirmationDialogs]\n')
            if name == 'velocity':
                (folder / 'velocity.toml').write_text('''config-version = "2.7"
bind = "127.0.0.1:25950"
motd = "NCD isolated tests"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "none"
[forced-hosts]
localhost = ["lobby"]
[servers]
lobby = "127.0.0.1:25900"
try = ["lobby"]
[advanced]
compression-threshold = -1
''')
                cmd = ['java', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xmx256M', '-jar', str(base / 'velocity.jar')]
                ready = 'Done ('
            else:
                (folder / 'config.yml').write_text('''online_mode: false
ip_forward: false
enforce_secure_profile: false
network_compression_threshold: -1
listeners:
- host: 127.0.0.1:25950
  motd: NCD isolated tests
  priorities: [lobby]
  max_players: 10
  tab_size: 10
  query_enabled: false
  force_default_server: true
  bind_local_address: true
  ping_passthrough: false
  forced_hosts: {}
servers:
  lobby:
    address: 127.0.0.1:25900
    motd: NCD isolated backend
    restricted: false
''')
                cmd = ['java', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xmx256M', '-jar', str(base / 'BungeeCord.jar')]
                ready = 'Listening on '
            proxy, log = start(folder, cmd, 'network-probe.log')
            wait_log(proxy, log, ready)
            client(25950, 'proxy')
            output = log.read_text()
            assert 'NCD_PROXY_COMMAND_EXECUTED' in output, str(log)
            assert 'NCD_PROXY_EVENT_CANCELLED' in output, str(log)
            assert 'NCD_PROXY_EVENT ' + ('/' if name == 'bungee' else '') + 'ncdprobe' in output, 'Backend click skipped proxy event'
            assert 'Could not' not in output, output[-3000:]
            proxy.stdin.write('end\n' if name == 'bungee' else 'shutdown\n'); proxy.stdin.flush()
            proxy.wait(timeout=25)
            results.append({'platform': name, 'passed': True, 'proxy_command': True, 'backend_command_event': True, 'cancelled_event': True})
        output = backend_log.read_text()
        assert output.count('NCD_COMMAND_EXECUTED NcdProbe') == 3, 'Expected exactly one backend command per connection'
        assert 'NCD_PERMISSION_BYPASSED' not in output
        assert 'Could not' not in output
        results.append({'platform': 'paper', 'passed': True, 'native_commands': 3, 'permissions_preserved': True, 'forged_payload_ignored': True})
        (base / 'network-results.json').write_text(json.dumps(results, indent=2) + '\n')
        print('NCD_NETWORK_PASS', json.dumps(results), flush=True)
    finally:
        for proc, stream in reversed(processes):
            if proc.poll() is None:
                try:
                    proc.stdin.write('stop\n'); proc.stdin.flush(); proc.wait(timeout=20)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    proc.terminate(); proc.wait(timeout=10)
            stream.close()


if __name__ == '__main__':
    main()
