#!/usr/bin/env python3
"""Verify a signed release APK and write bounded, credential-free provenance."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from release_version import version


def fingerprint(value: str) -> str:
    normalized = value.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized, re.ASCII):
        raise ValueError("Expected a SHA-256 certificate fingerprint")
    return normalized


def signer_digest(output: str) -> str:
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$", output, re.MULTILINE)
    if len(digests) != 1:
        raise ValueError("Expected exactly one verified APK signer")
    return fingerprint(digests[0])


def verify_manifest(output: str, name: str, code: int) -> None:
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", output, re.MULTILINE)
    if not package or package.groups() != ("io.github.drolevar.audiotales", str(code), name):
        raise ValueError("APK package/version differs from the release identity")
    minimum = re.findall(r"^(?:minSdkVersion|sdkVersion):'([^']+)'$", output, re.MULTILINE)
    target = re.findall(r"^targetSdkVersion:'([^']+)'$", output, re.MULTILINE)
    if minimum != ['26'] or target != ['37']:
        raise ValueError("APK minimum/target SDK differs from the accepted platform")
    if re.search(r"^application-debuggable(?:\s|$)", output, re.MULTILINE):
        raise ValueError("Release APK must not be debuggable")


def elf_load_alignments(data: bytes) -> list[int]:
    if len(data) < 16 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("Native library is not a supported ELF file")
    endian = "<" if data[5] == 1 else ">"
    bits = data[4]
    header_size = 52 if bits == 1 else 64
    if len(data) < header_size:
        raise ValueError("Truncated ELF header")
    phoff = struct.unpack_from(endian + ("I" if bits == 1 else "Q"), data, 28 if bits == 1 else 32)[0]
    phentsize, phnum = struct.unpack_from(endian + "HH", data, 42 if bits == 1 else 54)
    minimum = 32 if bits == 1 else 56
    if phnum == 0 or phentsize < minimum or phoff + phentsize * phnum > len(data):
        raise ValueError("Invalid/truncated ELF program-header table")
    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        kind = struct.unpack_from(endian + "I", data, offset)[0]
        if kind != 1:  # PT_LOAD
            continue
        if bits == 1:
            file_offset, virtual = struct.unpack_from(endian + "II", data, offset + 4)
            file_size = struct.unpack_from(endian + "I", data, offset + 16)[0]
            alignment = struct.unpack_from(endian + "I", data, offset + 28)[0]
        else:
            file_offset, virtual = struct.unpack_from(endian + "QQ", data, offset + 8)
            file_size = struct.unpack_from(endian + "Q", data, offset + 32)[0]
            alignment = struct.unpack_from(endian + "Q", data, offset + 48)[0]
        if (alignment < 16384 or alignment & (alignment - 1) or
                file_offset % 16384 != virtual % 16384 or file_offset + file_size > len(data)):
            raise ValueError("ELF PT_LOAD segment is not compatible with 16 KiB pages")
        alignments.append(alignment)
    if not alignments:
        raise ValueError("ELF contains no loadable segment")
    return alignments


def native_libraries(apk: Path) -> list[dict]:
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        if len({entry.filename for entry in entries}) != len(entries):
            raise ValueError("APK contains duplicate ZIP entries")
        for entry in entries:
            if not entry.is_dir() and entry.filename.endswith(".so"):
                libraries.append({"path": entry.filename, "pt_load_alignments": elf_load_alignments(archive.read(entry))})
    return libraries


def run(*args: str) -> str:
    completed = subprocess.run(args, text=True, capture_output=True, check=True)
    return completed.stdout + completed.stderr


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--expected-signer", required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--partner-configured", choices=("true", "false"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        name, code = version(args.tag)
        expected = fingerprint(args.expected_signer)
        if not re.fullmatch(r"[0-9a-f]{40}", args.commit, re.ASCII):
            raise ValueError("Expected the full tagged Git commit SHA")
        if not args.mapping.is_file() or args.mapping.stat().st_size == 0:
            raise ValueError("Missing release R8 mapping")
        signature = run(str(args.build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk))
        actual = signer_digest(signature)
        if actual != expected:
            raise ValueError("APK signer differs from the configured release identity")
        verify_manifest(run(str(args.build_tools / "aapt2"), "dump", "badging", str(args.apk)), name, code)
        run(str(args.build_tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(args.apk))
        libraries = native_libraries(args.apk)
        digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        provenance = {"tag": args.tag, "commit": args.commit, "version_name": name, "version_code": code,
                      "application_id": "io.github.drolevar.audiotales", "min_sdk": 26, "target_sdk": 37,
                      "debuggable": False, "apk": args.apk.name, "apk_sha256": digest,
                      "signing_certificate_sha256": actual, "native_libraries": libraries,
                      "partner_configuration_present": args.partner_configured == "true",
                      "mapping_sha256": hashlib.sha256(args.mapping.read_bytes()).hexdigest()}
        args.output.write_text(json.dumps(provenance, indent=2) + "\n")
        print(json.dumps({"verified": True, "apk": args.apk.name, "version": name, "native_library_count": len(libraries)}))
    except (ValueError, subprocess.CalledProcessError, zipfile.BadZipFile, struct.error) as error:
        # Do not print arbitrary subprocess output or build-time configuration.
        parser.exit(1, f"Release verification failed: {error}\n")


if __name__ == "__main__":
    main()
