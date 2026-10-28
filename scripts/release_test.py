"""Pure release-boundary tests; no keys, Android tools, builds or network needed."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest

from release_publish import publish, remote_commit, verify_bundle
from release_version import validate_order, version
from release_verify import elf_load_alignments, fingerprint, signer_digest, verify_manifest


def elf(bits=2, alignment=16384, file_offset=0, virtual=0, kind=1, endian='<'):
    header_size, phsize = (52, 32) if bits == 1 else (64, 56)
    data = bytearray(header_size + phsize)
    data[:6] = b'\x7fELF' + bytes([bits, 1 if endian == '<' else 2])
    struct.pack_into(endian + ('I' if bits == 1 else 'Q'), data, 28 if bits == 1 else 32, header_size)
    struct.pack_into(endian + 'HH', data, 42 if bits == 1 else 54, phsize, 1)
    struct.pack_into(endian + 'I', data, header_size, kind)
    if bits == 1:
        struct.pack_into(endian + 'II', data, header_size + 4, file_offset, virtual)
        struct.pack_into(endian + 'I', data, header_size + 28, alignment)
    else:
        struct.pack_into(endian + 'QQ', data, header_size + 8, file_offset, virtual)
        struct.pack_into(endian + 'Q', data, header_size + 48, alignment)
    return bytes(data)


class VersionTests(unittest.TestCase):
    def test_version_identity_and_ordering(self):
        self.assertEqual(version('v0.2.0'), ('0.2.0', 2000))
        self.assertEqual(version('v2099.999.999'), ('2099.999.999', 2099999999))
        tags = ['v0.1.999', 'v0.2.0', 'v0.2.0-rc1', 'unrelated']
        self.assertEqual(validate_order('v0.2.0', tags), ('0.2.0', 2000))
        with self.assertRaises(ValueError): validate_order('v0.1.999', tags)
        self.assertLess(version('v0.999.999')[1], version('v1.0.0')[1])

    def test_rejects_invalid_tags_and_ranges(self):
        for tag in ['0.2.0', 'v0.02.0', 'v01.2.0', 'v0.2.0-rc1', 'v0.2.0+build',
                    'v0.2.0\n', 'v0.2.0;echo secret', 'v０.2.0', 'v0.0.0',
                    'v2100.0.0', 'v1.1000.0', 'v1.0.1000']:
            with self.subTest(tag=tag), self.assertRaises(ValueError): version(tag)


class ArtifactTests(unittest.TestCase):
    def manifest(self, **changes):
        values = dict(package='io.github.drolevar.audiotales', code=2000, name='0.2.0', minimum=26, target=37)
        values.update(changes)
        return ("package: name='{package}' versionCode='{code}' versionName='{name}' platformBuildVersionName='37'\n"
                "minSdkVersion:'{minimum}'\ntargetSdkVersion:'{target}'\n").format(**values)

    def test_manifest_identity_and_not_debuggable(self):
        verify_manifest(self.manifest(), '0.2.0', 2000)
        verify_manifest(self.manifest().replace('minSdkVersion:', 'sdkVersion:'), '0.2.0', 2000)
        for values in [dict(package='io.github.drolevar.audiotales.debug'), dict(code=1), dict(name='dev'), dict(minimum=25), dict(target=36)]:
            with self.subTest(values=values), self.assertRaises(ValueError): verify_manifest(self.manifest(**values), '0.2.0', 2000)
        with self.assertRaises(ValueError): verify_manifest(self.manifest() + 'application-debuggable\n', '0.2.0', 2000)
        with self.assertRaises(ValueError): verify_manifest(self.manifest() + "minSdkVersion:'25'\n", '0.2.0', 2000)

    def test_exactly_one_signer_fingerprint(self):
        digest = 'ab' * 32
        self.assertEqual(fingerprint(':'.join(['AB'] * 32)), digest)
        output = 'Signer #1 certificate SHA-256 digest: ' + digest + '\n'
        self.assertEqual(signer_digest(output), digest)
        for invalid in ['', output + output.replace('#1', '#2'), 'Signer #1 certificate SHA-256 digest: ab\n']:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError): signer_digest(invalid)

    def test_elf_supports_both_classes_and_endianness(self):
        for bits in (1, 2):
            for endian in ('<', '>'):
                self.assertEqual(elf_load_alignments(elf(bits=bits, endian=endian)), [16384])
                self.assertEqual(elf_load_alignments(elf(bits=bits, endian=endian, alignment=65536)), [65536])

    def test_rejects_bad_alignment_missing_load_and_truncated_elf(self):
        cases = [b'not an ELF', elf()[:20], elf()[:-1], elf(alignment=4096),
                 elf(alignment=32769), elf(virtual=1), elf(file_offset=999999), elf(kind=2)]
        for data in cases:
            with self.subTest(data=data), self.assertRaises(ValueError): elf_load_alignments(data)


class PublicationTests(unittest.TestCase):
    tag, commit, signer, repo = 'v0.2.0', 'a' * 40, 'b' * 64, 'example/audiotales'

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / 'assets'
        self.directory.mkdir()
        (self.directory / 'audiotales-0.2.0.apk').write_bytes(b'verified apk fixture')
        (self.directory / 'mapping.txt').write_bytes(b'verified mapping fixture')
        self.provenance = dict(tag=self.tag, commit=self.commit, version_name='0.2.0', version_code=2000,
                               application_id='io.github.drolevar.audiotales', min_sdk=26, target_sdk=37,
                               debuggable=False, apk='audiotales-0.2.0.apk',
                               apk_sha256=self.hash('audiotales-0.2.0.apk'),
                               mapping_sha256=self.hash('mapping.txt'), signing_certificate_sha256=self.signer)
        self.write_provenance()
        self.calls = []
        self.draft = True
        self.uploaded = False

    def hash(self, name):
        return hashlib.sha256((self.directory / name).read_bytes()).hexdigest()

    def write_provenance(self):
        (self.directory / 'provenance.json').write_text(json.dumps(self.provenance))
        (self.directory / 'SHA256SUMS').write_text(''.join(
            f'{self.hash(name)}  {name}\n' for name in ['audiotales-0.2.0.apk', 'mapping.txt', 'provenance.json']))

    def fake_gh(self, *args):
        self.calls.append(args)
        if args[:3] == ('api', '--paginate', '--slurp'):
            return '[[]]'
        if args[0] == 'api':
            return json.dumps(dict(object=dict(type='commit', sha=self.commit)))
        command = args[1]
        if command == 'create':
            self.assertIn('--draft', args)
            self.assertIn('--verify-tag', args)
            notes = Path(args[args.index('--notes-file') + 1]).read_text()
            self.assertIn('Android Auto', notes)
            self.assertIn('pending', notes)
            self.assertIn('.debug', notes)
        elif command == 'upload':
            self.assertNotIn('--clobber', args)
            self.uploaded = True
        elif command == 'view':
            assets = [dict(name=path.name, size=path.stat().st_size)
                      for path in self.directory.iterdir()] if self.uploaded else []
            return json.dumps(dict(databaseId=42, tagName=self.tag, isDraft=self.draft,
                                   assets=assets, url='https://github.com/example/audiotales/releases/tag/v0.2.0'))
        elif command == 'download':
            shutil.copytree(self.directory, args[args.index('--dir') + 1])
        elif command == 'edit':
            self.assertIn('--draft=false', args)
            self.draft = False
        else:
            self.fail(f'Unexpected command: {args}')
        return ''

    def publish(self, run=None, **changes):
        values = dict(directory=self.directory, tag=self.tag, commit=self.commit,
                      repo=self.repo, expected_signer=self.signer, run=run or self.fake_gh)
        values.update(changes)
        return publish(**values)

    def assert_not_published(self):
        self.assertFalse(any(call[:2] == ('release', 'edit') for call in self.calls))

    def test_publishes_only_after_exact_download_checks(self):
        self.assertIn('/releases/tag/v0.2.0', self.publish())
        commands = [call[1] for call in self.calls if call[0] == 'release']
        self.assertEqual(commands, ['create', 'view', 'upload', 'view', 'download', 'view', 'edit', 'view'])
        self.assertEqual(sum(call[0] == 'api' for call in self.calls), 3)

    def test_workflow_upload_selection_excludes_signing_sidecars(self):
        # Exercise the upload allowlist against a signer-shaped output directory.
        # A directory upload or an APK-prefix glob must fail this boundary.
        root = Path(self.temporary.name)
        signed = root / 'release-artifacts'
        shutil.copytree(self.directory, signed)
        (signed / 'audiotales-0.2.0.apk.idsig').write_bytes(b'v4 signing sidecar')
        (signed / 'unexpected.txt').write_text('not part of the verified bundle')
        workflow = (Path(__file__).resolve().parent.parent / '.github/workflows/release.yml').read_text()
        upload = workflow.split('        id: artifact\n', 1)[1].split('\n  publish:', 1)[0]
        match = re.search(r'(?m)^          path: (.+)\n((?:            .+\n)*)', upload)
        self.assertIsNotNone(match, 'Missing build artifact upload paths')
        patterns = (match[2].splitlines() if match[1] == '|' else [match[1]])
        selected = set()
        for pattern in patterns:
            pattern = pattern.strip().replace('${{ steps.version.outputs.version_name }}', self.tag[1:])
            for path in root.glob(pattern):
                selected.update(path.rglob('*') if path.is_dir() else [path])
        staged = root / 'uploaded'
        staged.mkdir()
        for path in selected:
            if path.is_file(): shutil.copy2(path, staged / path.name)
        verify_bundle(staged, self.tag, self.commit, self.signer)
        self.assertEqual({path.name for path in staged.iterdir()}, {path.name for path in self.directory.iterdir()})

    def test_invalid_inputs_never_contact_github(self):
        for values in [dict(tag='v0.2.0;echo bad'), dict(commit='main'), dict(repo='owner/repo/extra'),
                       dict(expected_signer='c' * 64)]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                self.publish(**values)
        self.assertEqual(self.calls, [])

    def test_bundle_rejects_wrong_identity_even_with_valid_checksums(self):
        for key, wrong in [('tag', 'v0.3.0'), ('commit', 'c' * 40), ('version_code', 1),
                           ('application_id', 'io.github.drolevar.audiotales.debug'), ('debuggable', True)]:
            original = self.provenance[key]
            self.provenance[key] = wrong
            self.write_provenance()
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.publish()
            self.provenance[key] = original
        self.assertEqual(self.calls, [])

    def test_bundle_rejects_corruption_extra_missing_and_symlink_assets(self):
        apk = self.directory / 'audiotales-0.2.0.apk'
        original = apk.read_bytes()
        apk.write_bytes(b'corrupt')
        with self.assertRaises(ValueError): self.publish()
        apk.write_bytes(original)
        extra = self.directory / 'extra'
        extra.write_text('unexpected')
        with self.assertRaises(ValueError): self.publish()
        extra.unlink()
        apk.unlink()
        with self.assertRaises(ValueError): self.publish()
        apk.symlink_to(self.directory / 'mapping.txt')
        with self.assertRaises(ValueError): self.publish()
        self.assertEqual(self.calls, [])

    def test_existing_draft_or_published_release_is_never_changed(self):
        for draft in (True, False):
            def existing(*args):
                self.calls.append(args)
                return json.dumps([[dict(tag_name=self.tag, draft=draft)]])
            with self.subTest(draft=draft), self.assertRaises(ValueError): self.publish(existing)
        self.assertTrue(all(call[0] == 'api' for call in self.calls))

    def test_api_failure_is_not_treated_as_missing_release(self):
        def failing(*args):
            raise subprocess.CalledProcessError(1, 'gh')
        with self.assertRaises(subprocess.CalledProcessError): self.publish(failing)
        self.assert_not_published()

    def test_wrong_or_moved_remote_tag_is_not_published(self):
        for moved_after_upload in (False, True):
            self.setUp()
            def moved(*args):
                if args[0] == 'api' and 'git/ref/' in args[-1] and (self.uploaded or not moved_after_upload):
                    return json.dumps(dict(object=dict(type='commit', sha='c' * 40)))
                return self.fake_gh(*args)
            with self.subTest(moved_after_upload=moved_after_upload), self.assertRaises(ValueError):
                self.publish(moved)
            self.assert_not_published()

    def test_upload_failure_or_wrong_remote_state_never_calls_publication(self):
        for failure in ('upload', 'asset_names', 'release_identity', 'published_early', 'download'):
            self.setUp()
            def failing(*args):
                result = self.fake_gh(*args)
                if args[:2] == ('release', 'upload') and failure == 'upload':
                    raise subprocess.CalledProcessError(1, 'gh')
                if args[:2] == ('release', 'view') and self.uploaded:
                    state = json.loads(result)
                    if failure == 'asset_names': state['assets'][0]['name'] = 'unexpected.apk'
                    if failure == 'release_identity': state['databaseId'] = 99
                    if failure == 'published_early': state['isDraft'] = False
                    return json.dumps(state)
                if args[:2] == ('release', 'download') and failure == 'download':
                    (Path(args[args.index('--dir') + 1]) / 'mapping.txt').write_text('corrupt')
                return result
            with self.subTest(failure=failure), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.publish(failing)
            self.assert_not_published()

    def test_internally_consistent_replacement_download_is_not_published(self):
        def replaced(*args):
            result = self.fake_gh(*args)
            if args[:2] == ('release', 'download'):
                downloaded = Path(args[args.index('--dir') + 1])
                (downloaded / 'mapping.txt').write_bytes(b'replacement mapping')
                provenance = json.loads((downloaded / 'provenance.json').read_text())
                provenance['mapping_sha256'] = hashlib.sha256((downloaded / 'mapping.txt').read_bytes()).hexdigest()
                (downloaded / 'provenance.json').write_text(json.dumps(provenance))
                (downloaded / 'SHA256SUMS').write_text(''.join(
                    f'{hashlib.sha256((downloaded / name).read_bytes()).hexdigest()}  {name}\n'
                    for name in ['audiotales-0.2.0.apk', 'mapping.txt', 'provenance.json']))
            return result
        with self.assertRaises(ValueError): self.publish(replaced)
        self.assert_not_published()

    def test_annotated_remote_tag_is_dereferenced(self):
        responses = [dict(object=dict(type='tag', sha='c' * 40)),
                     dict(object=dict(type='commit', sha=self.commit))]
        self.assertEqual(remote_commit(lambda *args: json.dumps(responses.pop(0)), self.repo, self.tag), self.commit)


if __name__ == '__main__':
    unittest.main()
