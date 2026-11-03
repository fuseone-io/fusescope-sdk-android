"""Copied into the backend RUM integration suite by native-acceptance CI."""

import json
import os
from pathlib import Path

from fixtures.auth import USER_ADMIN_EMAIL, USER_ADMIN_PASSWORD
from fixtures.rum import create_rum_application


def test_setup(signoz, create_user_admin, get_token):
    token = get_token(USER_ADMIN_EMAIL, USER_ADMIN_PASSWORD)
    app = create_rum_application(
        signoz,
        token,
        {"name": "android-native-release", "platform": "android", "environment": "sample", "allowedPackageNames": ["io.fuseone.fusescope.sample"]},
    )
    endpoint = signoz.self.host_configs["8080"].base()
    output = Path(os.environ["NATIVE_ACCEPTANCE_DIR"])
    output.mkdir(parents=True, exist_ok=True)
    config = output / "private.json"
    config.write_text(json.dumps({"endpoint": endpoint, "applicationId": app["id"], "key": app["key"]["key"], "token": token}))
    config.chmod(0o600)
    env = output / ".env.local"
    env.write_text(f"SIGNOZ_E2E_BASE_URL={endpoint}\nSIGNOZ_E2E_USERNAME={USER_ADMIN_EMAIL}\nSIGNOZ_E2E_PASSWORD={USER_ADMIN_PASSWORD}\nNATIVE_APPLICATION_ID={app['id']}\n")
    env.chmod(0o600)
