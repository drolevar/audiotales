#!/usr/bin/env python3
"""Publish an exact verified build through a draft; never replace an existing release."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

from release_version import version
from release_verify import fingerprint


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_bundle(directory: Path, tag: str, commit: str, expected_signer: str) -> list[Path]:
    name, code = version(tag)
    if not re.fullmatch(r'[0-9a-f]{40}', commit, re.ASCII):
        raise ValueError('Expected the full built Git commit SHA')
    signer = fingerprint(expected_signer)
    names = [f'audiotales-{name}.apk', 'mapping.txt', 'provenance.json', 'SHA256SUMS']
    if {path.name for path in directory.iterdir()} != set(names):
        raise ValueError('Expected exactly the four release assets')
    files = [directory / name for name in names]
    if any(path.is_symlink() or not path.is_file() or not path.stat().st_size for path in files):
        raise ValueError('Release assets must be nonempty regular files')
    hashes = {path.name: digest(path) for path in files}
    checksum_lines = files[-1].read_text().splitlines()
    expected_lines = {f'{hashes[name]}  {name}' for name in names[:-1]}
    if len(checksum_lines) != 3 or set(checksum_lines) != expected_lines:
        raise ValueError('Release checksums differ from the exact assets')
    provenance = json.loads(files[2].read_text())
    expected = dict(tag=tag, commit=commit, version_name=name, version_code=code,
                    application_id='io.github.drolevar.audiotales', min_sdk=26, target_sdk=37,
                    debuggable=False, apk=names[0], apk_sha256=hashes[names[0]],
                    mapping_sha256=hashes['mapping.txt'], signing_certificate_sha256=signer)
    if not isinstance(provenance, dict) or any(provenance.get(key) != value for key, value in expected.items()):
        raise ValueError('Release provenance differs from the built identity')
    return files


def gh(*args: str) -> str:
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True).stdout


def remote_commit(run, repo: str, tag: str) -> str:
    obj = json.loads(run('api', f'repos/{repo}/git/ref/tags/{tag}'))['object']
    # Support annotated tags as well as lightweight tags, with a bounded walk.
    for _ in range(5):
        if obj['type'] == 'commit':
            return obj['sha']
        if obj['type'] != 'tag' or not re.fullmatch(r'[0-9a-f]{40}', obj['sha'], re.ASCII):
            break
        obj = json.loads(run('api', f'repos/{repo}/git/tags/{obj["sha"]}'))['object']
    raise ValueError('Remote tag does not resolve to a commit')


def release_state(run, repo: str, tag: str, files: list[Path], draft: bool, release_id=None) -> dict:
    state = json.loads(run('release', 'view', tag, '--repo', repo, '--json',
                           'databaseId,tagName,isDraft,assets,url'))
    assets = state['assets']
    if (state['tagName'] != tag or state['isDraft'] is not draft or
            (release_id is not None and state['databaseId'] != release_id) or
            len(assets) != len(files) or
            {asset['name']: asset['size'] for asset in assets} !=
            {path.name: path.stat().st_size for path in files}):
        raise ValueError('Remote release identity, draft state or assets differ')
    return state


def publish(directory: Path, tag: str, commit: str, repo: str, expected_signer: str, run=gh) -> str:
    files = verify_bundle(directory, tag, commit, expected_signer)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo, re.ASCII):
        raise ValueError('Expected an owner/repository identity')
    # Listing must succeed: auth/network errors must never mean "no release".
    pages = json.loads(run('api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    if any(release['tag_name'] == tag for page in pages for release in page):
        raise ValueError('Release already exists; inspect it manually, never clobber or move its tag')
    if remote_commit(run, repo, tag) != commit:
        raise ValueError('Remote tag differs from the built commit')
    with tempfile.TemporaryDirectory(prefix='audiotales-publish-') as temporary:
        temporary = Path(temporary)
        notes = temporary / 'notes.md'
        notes.write_text(
            f'AudioTales {tag[1:]} for Android 8.0 (API 26) and newer.\n\n'
            'Install the signed APK below. SHA256SUMS, provenance.json and the R8 mapping '
            'accompany this exact build.\n\n'
            'Android Auto and physical headset/Bluetooth qualification are still pending. '
            'This build is separate from the .debug app and has its own library and listening progress.\n')
        run('release', 'create', tag, '--repo', repo, '--draft', '--verify-tag',
            '--target', commit, '--title', f'AudioTales {tag[1:]}', '--notes-file', str(notes))
        draft = release_state(run, repo, tag, [], True)
        run('release', 'upload', tag, *(str(path) for path in files), '--repo', repo)
        release_state(run, repo, tag, files, True, draft['databaseId'])
        downloaded = temporary / 'downloaded'
        run('release', 'download', tag, '--repo', repo, '--dir', str(downloaded))
        remote_files = verify_bundle(downloaded, tag, commit, expected_signer)
        if {path.name: digest(path) for path in files} != {path.name: digest(path) for path in remote_files}:
            raise ValueError('Uploaded assets differ from the build artifact')
        if remote_commit(run, repo, tag) != commit:
            raise ValueError('Remote tag changed before publication')
        release_state(run, repo, tag, files, True, draft['databaseId'])
        run('release', 'edit', tag, '--repo', repo, '--draft=false', '--verify-tag')
        return release_state(run, repo, tag, files, False, draft['databaseId'])['url']


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--expected-signer', required=True)
    args = parser.parse_args()
    try:
        print(publish(args.artifacts, args.tag, args.commit, args.repo, args.expected_signer))
    except (ValueError, OSError, KeyError, TypeError, subprocess.CalledProcessError):
        # Do not print arbitrary subprocess output or environment details.
        parser.exit(1, 'Release publication failed; inspect the run and any draft before retrying. '
                    'Existing releases and assets are never replaced.\n')


if __name__ == '__main__':
    main()
