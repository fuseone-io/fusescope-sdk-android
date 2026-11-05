"""Regression coverage for release consumers and aligned Maven coordinates."""
import tempfile
import unittest
from pathlib import Path

from release_consumer import prepare, verify_pom


class ReleaseConsumerTest(unittest.TestCase):
    def test_consumer_has_no_composite_or_project_dependencies(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            prepare(target, "1.0.0", "https://repo.maven.apache.org/maven2")
            settings = (target / "settings.gradle.kts").read_text()
            sample = (target / "sample/build.gradle.kts").read_text()
            self.assertNotIn("includeBuild", settings)
            self.assertNotIn('project(":sdk")', sample)
            self.assertNotIn('project(":compose")', sample)
            self.assertIn('version "1.0.0"', sample)
            self.assertIn('android-compose:1.0.0', sample)
            self.assertIn('includeGroup("io.fuseone.fusescope.android.mappings")', settings)

    def test_mismatched_pom_version_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_pom(b'<project><groupId>g</groupId><artifactId>a</artifactId><version>0.1.0</version></project>', 'g', 'a', '1.0.0')

    def test_mismatched_companion_is_rejected(self):
        pom = b'<project><groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version><dependencies><dependency><groupId>g</groupId><artifactId>sdk</artifactId><version>0.1.0</version></dependency></dependencies></project>'
        with self.assertRaises(ValueError):
            verify_pom(pom, 'g', 'a', '1.0.0', ('g', 'sdk'))


if __name__ == '__main__':
    unittest.main()
