"""Pure release-boundary tests; no keys, Android tools, builds or network needed."""
import struct
import unittest

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


if __name__ == '__main__':
    unittest.main()
