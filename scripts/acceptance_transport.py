"""Transport settings for the acceptance sample; production SDK TLS stays unchanged."""
from contextlib import contextmanager
import os
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def backend_route_removed(status, content_type, body):
    # With the UI enabled, the backend's catch-all serves HTML even for unknown POSTs.
    return status in (404, 405) or (
        status == 200 and content_type.lower().startswith('text/html')
        and body.lstrip().lower().startswith(('<!doctype html', '<html'))
    )


def emulator_endpoint(config):
    return config.get('ingestionEndpoint', config['endpoint']).replace('localhost', '10.0.2.2').replace('127.0.0.1', '10.0.2.2')


@contextmanager
def sample_tls(config, root=ROOT):
    if not config.get('caCertFile'):
        yield
        return
    path = root / 'sample/src/main/res/xml/network_security_config.xml'
    resource = root / 'sample/src/main/res/raw/fusescope_acceptance_ca.pem'
    original = path.read_bytes()
    previous_ca = resource.read_bytes() if resource.exists() else None
    document = ET.fromstring(original)
    base = document.find('base-config')
    if base is None:
        base = ET.SubElement(document, 'base-config', {'cleartextTrafficPermitted': 'false'})
    anchors = ET.SubElement(base, 'trust-anchors')
    ET.SubElement(anchors, 'certificates', {'src': 'system'})
    ET.SubElement(anchors, 'certificates', {'src': '@raw/fusescope_acceptance_ca'})
    resource.parent.mkdir(parents=True, exist_ok=True)
    try:
        resource.write_bytes(Path(config['caCertFile']).read_bytes())
        path.write_bytes(ET.tostring(document, encoding='utf-8', xml_declaration=True))
        yield
    finally:
        path.write_bytes(original)
        if previous_ca is None:
            resource.unlink(missing_ok=True)
        else:
            resource.write_bytes(previous_ca)


def sample_sdk_properties(version):
    return [] if os.environ.get('SDK_SOURCE') == 'true' else [f'-Pfusescope.sample.sdkVersion={version}']


def source_sdk_projects(report):
    return set(re.findall(r"\bproject\s+['\"]?:(sdk|compose)\b", report))


def verify_sdk_origin(report, version, compose=False):
    artifacts = ('android', 'android-compose') if compose else ('android',)
    projects = source_sdk_projects(report)
    if os.environ.get('SDK_SOURCE') == 'true':
        assert 'sdk' in projects, 'candidate acceptance must consume SDK source'
        if compose:
            assert 'compose' in projects
    else:
        for artifact in artifacts:
            assert f'io.fuseone.fusescope:{artifact}:{version}' in report
        assert not projects, 'release acceptance must consume published artifacts'
