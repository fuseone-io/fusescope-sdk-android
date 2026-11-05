"""Build the sample with SDK, Compose and plugin resolved from Maven, without source substitution."""
import argparse
import json
import re
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from acceptance_transport import source_sdk_projects

ROOT = Path(__file__).resolve().parents[1]
GROUP = 'io.fuseone.fusescope'
PLUGIN = GROUP + '.android.mappings'


def verify_pom(data, group, artifact, version, companion=None):
    root = ET.fromstring(data)
    # Accept both Maven's default XML namespace and minimal fixture POMs.
    for node in root.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    actual = tuple(root.findtext(name) for name in ('groupId', 'artifactId', 'version'))
    if actual != (group, artifact, version):
        raise ValueError(f'Unexpected POM coordinates: {actual}')
    if companion:
        dependencies = [
            (dep.findtext('groupId'), dep.findtext('artifactId'), dep.findtext('version'))
            for dep in root.findall('./dependencies/dependency')
        ]
        if (*companion, version) not in dependencies:
            raise ValueError(f'Missing aligned companion {companion}:{version}')


def prepare(target, version, repository):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Expected a release version')
    if not repository.startswith(('https://', 'file:///')) or any(c in repository for c in '\n\r"$\\'):
        raise ValueError('Expected an HTTPS or local file repository URL')
    target.mkdir(parents=True, exist_ok=True)
    for name in ('gradle', 'sample'):
        shutil.copytree(ROOT / name, target / name, ignore=shutil.ignore_patterns('build', '.gradle', '.cxx'), dirs_exist_ok=True)
    shutil.copyfile(ROOT / 'gradle.properties', target / 'gradle.properties')
    if (ROOT / 'local.properties').exists():
        shutil.copyfile(ROOT / 'local.properties', target / 'local.properties')
    sample = (target / 'sample/build.gradle.kts').read_text()
    sample = sample.replace(f'id("{PLUGIN}")', f'id("{PLUGIN}") version "{version}"')
    begin = sample.index('    val publishedSdk =')
    end = sample.index('    implementation(libs.androidx.fragment)', begin)
    sample = sample[:begin] + f'    implementation("{GROUP}:android:{version}")\n    implementation("{GROUP}:android-compose:{version}")\n' + sample[end:]
    (target / 'sample/build.gradle.kts').write_text(sample)
    repositories = f'''        exclusiveContent {{
            forRepository {{ maven {{ url = uri("{repository}") }} }}
            filter {{
                includeGroup("{GROUP}")
                includeGroup("{PLUGIN}")
            }}
        }}
        google()
        mavenCentral()
'''
    (target / 'settings.gradle.kts').write_text('pluginManagement {\n    repositories {\n' + repositories + '        gradlePluginPortal()\n    }\n}\ndependencyResolutionManagement {\n    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n    repositories {\n' + repositories + '    }\n}\nrootProject.name = "fusescope-release-consumer"\ninclude(":sample")\n')
    (target / 'build.gradle.kts').write_text('')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--repository', default='https://repo.maven.apache.org/maven2')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/release-consumer')
    args = parser.parse_args()
    prepare(args.output, args.version, args.repository)
    coordinates = [
        (GROUP, 'android', None),
        (GROUP, 'android-compose', (GROUP, 'android')),
        (GROUP, 'android-mappings-gradle-plugin', None),
        (PLUGIN, PLUGIN + '.gradle.plugin', (GROUP, 'android-mappings-gradle-plugin')),
    ]
    for group, artifact, companion in coordinates:
        url = f'{args.repository.rstrip("/")}/{group.replace(".", "/")}/{artifact}/{args.version}/{artifact}-{args.version}.pom'
        with urllib.request.urlopen(url, timeout=60) as response:
            verify_pom(response.read(), group, artifact, args.version, companion)
    command = [str(ROOT / 'gradlew'), '-p', str(args.output.resolve()), '--no-configuration-cache']
    subprocess.run(command + [':sample:assembleRelease'], check=True)
    for task, expected in [
        (':sample:dependencies', [f'{GROUP}:android:{args.version}', f'{GROUP}:android-compose:{args.version}']),
        (':sample:buildEnvironment', [f'{GROUP}:android-mappings-gradle-plugin:{args.version}']),
    ]:
        extra = ['--configuration', 'releaseRuntimeClasspath'] if task.endswith(':dependencies') else []
        result = subprocess.run(command + [task] + extra, check=True, text=True, stdout=subprocess.PIPE)
        report = result.stdout
        (args.output / (task.rsplit(':', 1)[-1] + '.txt')).write_text(report)
        if 'FAILED' in report or source_sdk_projects(report):
            raise ValueError(f'Unresolved or source-substituted dependencies in {task}')
        for coordinate in expected:
            if coordinate not in report or re.search(re.escape(coordinate) + r' -> (?!' + re.escape(args.version) + r'(?:\s|$))', report):
                raise ValueError(f'Consumer did not resolve the exact release: {coordinate}')
    evidence = {'version': args.version, 'repository': args.repository, 'coordinates': [f'{g}:{a}:{args.version}' for g, a, _ in coordinates], 'releaseBuild': 'passed'}
    (args.output / 'acceptance.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
