#!/usr/bin/env python3
"""Validate stable release tags and their deterministic Android version identity."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", re.ASCII)


def version(tag: str) -> tuple[str, int]:
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError("Release tag must be stable vMAJOR.MINOR.PATCH without leading zeroes")
    major, minor, patch = map(int, match.groups())
    if not (0 <= major <= 2099 and 0 <= minor <= 999 and 0 <= patch <= 999):
        raise ValueError("Release version components exceed the supported range")
    code = major * 1_000_000 + minor * 1_000 + patch
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("Android versionCode must be positive and at most 2100000000")
    return tag[1:], code


def validate_order(tag: str, other_tags: list[str]) -> tuple[str, int]:
    name, code = version(tag)
    for other in other_tags:
        if other == tag or not TAG.fullmatch(other):
            continue
        _, other_code = version(other)
        if other_code > code:
            raise ValueError(f"Release tag {tag} is older than existing stable tag {other}")
    return name, code


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--check-tags", action="store_true")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    tags = subprocess.run(["git", "tag", "--list"], text=True, capture_output=True, check=True).stdout.splitlines() if args.check_tags else []
    try:
        name, code = validate_order(args.tag, tags)
    except ValueError as error:
        parser.error(str(error))
    if args.github_output:
        with args.github_output.open("a") as stream:
            stream.write(f"version_name={name}\nversion_code={code}\n")
    print(json.dumps({"tag": args.tag, "version_name": name, "version_code": code}))


if __name__ == "__main__":
    main()
