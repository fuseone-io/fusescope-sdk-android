"""Real-device TLS evidence for the disposable acceptance emulator only."""
import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import pathlib
import ssl
import subprocess
import threading
import time
import urllib.request
import urllib.error

from acceptance_transport import emulator_endpoint
from native_smoke import ACTIVITY, PACKAGE, adb, wait_for


def retryable_transport_failure(result):
    return result.get('error') in ('ConnectException', 'SocketTimeoutException')


def collector_probe(url, directory):
    attempts = []
    deadline = time.monotonic() + 45
    while True:
        result = probe(url)
        attempts.append(result)
        (directory / 'evidence' / 'device-tls-attempts.json').write_text(json.dumps(attempts, indent=2))
        if not retryable_transport_failure(result) or time.monotonic() >= deadline:
            return result
        time.sleep(2)


def verify_host_collector(config):
    context = ssl.create_default_context(cafile=config['caCertFile'])
    url = config['ingestionEndpoint'].rstrip('/') + '/rum/v1/traces'
    try:
        with urllib.request.urlopen(url, context=context, timeout=15) as response:
            raise AssertionError(f'host RUM route returned unexpected status {response.status}')
    except urllib.error.HTTPError as failure:
        try:
            assert failure.code == 405, f'host RUM route returned {failure.code}'
        finally:
            failure.close()


def validate_probe(probe, api):
    assert 'error' not in probe, f'trusted collector TLS handshake failed: {probe}'
    assert probe['status'] == 405, 'probe must reach the RUM route'
    assert probe['protocol'] == ('TLSv1.2' if api < 29 else 'TLSv1.3'), probe
    assert any(name in probe['cipher'] for name in ('GCM', 'CHACHA20_POLY1305')), probe


def read_probe_result():
    try:
        process = subprocess.run(['adb', 'shell', 'run-as', PACKAGE, 'cat', 'files/tls-probe.json'], capture_output=True, text=True, timeout=5)
        return json.loads(process.stdout)
    except (json.JSONDecodeError, subprocess.TimeoutExpired):
        return None


def probe(url):
    adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/tls-probe.json')
    started = adb('shell', 'am', 'start', '-W', '--activity-single-top', '-n', ACTIVITY, '--es', 'action', 'tls', '--es', 'probeUrl', url)
    print(started, flush=True)
    return wait_for(read_probe_result, 'device TLS probe did not complete', 60)


def framework_ready():
    try:
        process = subprocess.run(['adb', 'shell', 'pm', 'path', 'android'], capture_output=True, text=True, timeout=10)
        return process.returncode == 0 and 'package:' in process.stdout
    except subprocess.TimeoutExpired:
        return False


def install_ca(config):
    # API 23 predates network-security-config. Trust this fixture CA in the
    # disposable emulator; production trust and hostname verification stay on.
    cert = pathlib.Path(config['caCertFile'])
    name = subprocess.check_output(['openssl', 'x509', '-in', str(cert), '-subject_hash_old', '-noout'], text=True).strip() + '.0'
    adb('root')
    adb('wait-for-device')
    adb('remount')
    adb('push', str(cert), '/system/etc/security/cacerts/' + name)
    adb('shell', 'chmod', '644', '/system/etc/security/cacerts/' + name)
    # A full reboot avoids PackageInstaller deadlocks after restarting only
    # the framework on Marshmallow. No application has been installed yet.
    adb('reboot')
    time.sleep(3)
    adb('wait-for-device', timeout=120)
    wait_for(lambda: adb('shell', 'getprop', 'sys.boot_completed') == '1' and framework_ready(), 'emulator did not finish rebooting', 180)


@contextmanager
def invalid_server(config, directory, trusted):
    private = directory / 'private-tls'
    private.mkdir(exist_ok=True)
    stem = private / ('wrong-host' if trusted else 'untrusted')
    csr, key, cert = (str(stem) + suffix for suffix in ('.csr', '.key', '.pem'))
    subprocess.run(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', csr, '-subj', '/CN=wrong.example'], check=True, capture_output=True)
    extensions = private / 'extensions.cnf'
    extensions.write_text('subjectAltName=DNS:wrong.example\nextendedKeyUsage=serverAuth\n' if trusted else 'subjectAltName=IP:10.0.2.2\nextendedKeyUsage=serverAuth\n')
    args = ['openssl', 'x509', '-req', '-in', csr, '-out', cert, '-days', '1', '-extfile', str(extensions)]
    if trusted:
        args += ['-CA', config['caCertFile'], '-CAkey', str(pathlib.Path(config['caCertFile']).with_name('ca.key')), '-set_serial', '337']
    else:
        args += ['-signkey', key]
    subprocess.run(args, check=True, capture_output=True)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.end_headers()
        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(('0.0.0.0', 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield f'https://10.0.2.2:{server.server_port}/'
    finally:
        server.shutdown()
        server.server_close()


def verify(config, directory, api):
    actual = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
    assert actual == api, f'wrong emulator: {actual}, expected {api}'
    verify_host_collector(config)
    adb('install', '-r', 'sample/build/outputs/apk/debug/sample-debug.apk')
    adb('shell', 'pm', 'clear', PACKAGE)
    try:
        result = {'api': actual, 'collector': collector_probe(emulator_endpoint(config).rstrip('/') + '/rum/v1/traces', directory)}
    except Exception:
        crash = adb('logcat', '-d', '-s', 'AndroidRuntime:E')
        (directory / 'evidence' / 'device-crash.txt').write_text(crash)
        print(crash, flush=True)
        raise
    (directory / 'evidence' / 'device-tls.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)
    validate_probe(result['collector'], api)
    for name, trusted in [('wrongHostname', True), ('untrustedCertificate', False)]:
        with invalid_server(config, directory, trusted) as url:
            result[name] = probe(url)
        assert result[name].get('error') in ('SSLHandshakeException', 'SSLPeerUnverifiedException'), result[name]
    (directory / 'evidence' / 'device-tls.json').write_text(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['trust', 'verify'])
    parser.add_argument('--config', type=pathlib.Path, required=True)
    parser.add_argument('--api', type=int, choices=[23, 28, 35], required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.mode == 'trust':
        if args.api == 23:
            install_ca(config)
    else:
        verify(config, args.config.parent, args.api)


if __name__ == '__main__':
    main()
