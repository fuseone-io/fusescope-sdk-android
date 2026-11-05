"""Copied into the backend RUM integration suite by native-acceptance CI."""

import json
import os
from pathlib import Path

from fixtures.auth import USER_ADMIN_EMAIL, USER_ADMIN_PASSWORD
from fixtures.rum import create_rum_application
from fixtures.serviceaccount import create_service_account_with_key


def test_setup(signoz, create_user_admin, get_token, request):
    token = get_token(USER_ADMIN_EMAIL, USER_ADMIN_PASSWORD)
    app = create_rum_application(
        signoz,
        token,
        {"name": "android-native-release", "platform": "android", "environment": "sample", "allowedPackageNames": ["io.fuseone.fusescope.sample"]},
    )
    endpoint = signoz.self.host_configs["8080"].base()
    collector = request.getfixturevalue("fusescope_collector") if os.environ.get("COLLECTOR_INGRESS") == "true" else None
    ingestion_endpoint = collector.self.host_configs["4319"].base() if collector else endpoint
    output = Path(os.environ["NATIVE_ACCEPTANCE_DIR"])
    output.mkdir(parents=True, exist_ok=True)
    config = output / "private.json"
    data = {"endpoint": endpoint, "applicationId": app["id"], "key": app["key"]["key"], "token": token}
    data["ingestionEndpoint"] = ingestion_endpoint
    if collector:
        data["caCertFile"] = collector.ca_cert_path
        import docker  # available in the backend integration environment
        container = docker.from_env().containers.get(collector.self.id)
        image = container.image
        manifest = {"mode": "collector", "backendIngestRemoved": True, "collectorImageId": image.id,
                    "collectorRepoDigests": image.attrs.get("RepoDigests", []),
                    "collectorRevision": image.labels.get("org.opencontainers.image.revision"), "tls": "1.3"}
        # The backend must no longer offer a second, accidentally working ingest path.
        import requests
        for signal in ("traces", "logs"):
            response = requests.post(endpoint + "/rum/v1/" + signal, json={}, timeout=10)
            assert response.status_code == 404, (signal, response.status_code)
        (output / "evidence" / "collector-transport.json").write_text(json.dumps(manifest, indent=2))
    scenarios = {}
    if os.environ.get("FULL_ACCEPTANCE") == "true":
        for name in ("full", "consent", "unsampled"):
            scenario = create_rum_application(
                signoz, token,
                {"name": f"android-acceptance-{name}", "platform": "android", "environment": "sample", "allowedPackageNames": ["io.fuseone.fusescope.sample"]},
            )
            scenarios[name] = {"ingestionEndpoint": ingestion_endpoint, "endpoint": endpoint, "applicationId": scenario["id"], "key": scenario["key"]["key"], "token": token}
        _, editor_key = create_service_account_with_key(signoz, token, "android-acceptance-uploader", role="signoz-editor")
        scenarios["full"]["editorKey"] = editor_key
        store = signoz.telemetrystore
        host = store.container.host_configs["8123"]
        scenarios["full"]["clickhouse"] = {
            "endpoint": f"http://{host.address}:{host.port}",
            "user": store.env["SIGNOZ_TELEMETRYSTORE_CLICKHOUSE_USERNAME"],
            "password": store.env["SIGNOZ_TELEMETRYSTORE_CLICKHOUSE_PASSWORD"],
        }
        data["scenarios"] = scenarios
    config.write_text(json.dumps(data))
    config.chmod(0o600)
    env = output / ".env.local"
    env.write_text(f"SIGNOZ_E2E_BASE_URL={endpoint}\nSIGNOZ_E2E_USERNAME={USER_ADMIN_EMAIL}\nSIGNOZ_E2E_PASSWORD={USER_ADMIN_PASSWORD}\nNATIVE_APPLICATION_ID={app['id']}\n")
    if scenarios:
        with env.open("a") as stream:
            stream.write(f"FULL_APPLICATION_ID={scenarios['full']['applicationId']}\n")
    env.chmod(0o600)
