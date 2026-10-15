#!/usr/bin/env python3
"""Resolve the app version from ``version.properties``, the single source of truth.

Used by CI (prints the version so drift is visible) and by both jobs of the release
workflow, which each re-check the tag themselves. Same model as the desktop add-on's
``.github/scripts/resolve_version.py``. Standard library only, runnable locally from the
repository root::

    .github/scripts/resolve_version.py                      # print the version
    .github/scripts/resolve_version.py --expect-tag v1.2.3  # ...and fail unless the tag matches
    .github/scripts/resolve_version.py --self-test          # check the parser itself

When ``$GITHUB_OUTPUT`` is set, ``version=<value>`` is written to it so callers can use it as
a step output. The versionCode formula (major*10000 + minor*100 + patch) lives in
app/build.gradle.kts; this script only enforces the MAJOR.MINOR.PATCH shape it relies on.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys

PROPERTIES_PATH = pathlib.Path("version.properties")

VERSION_RE = re.compile(r"^[ \t]*versionName[ \t]*[=:][ \t]*(\S+)[ \t]*$", re.M)
# Same shape as the regex in app/build.gradle.kts.
SHAPE_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def parse_version(source: str) -> str | None:
    """Return the last ``versionName`` in ``source`` (java.util.Properties keeps the last
    duplicate key, and Gradle reads the file with Properties), or None if absent."""
    matches = VERSION_RE.findall(source)
    return matches[-1] if matches else None


def shape_error(version: str) -> str | None:
    """Return why ``version`` cannot be a versionName, or None if it is valid."""
    match = SHAPE_RE.match(version)
    if match is None or int(match.group(2)) > 99 or int(match.group(3)) > 99:
        return (
            f"versionName {version} must be MAJOR.MINOR.PATCH without leading zeros, minor "
            "and patch in 0..99 (versionCode is derived from it)"
        )
    return None


def read_version(path: pathlib.Path) -> str:
    try:
        source = path.read_text(encoding="utf-8")
    except OSError as exc:
        sys.exit(f"::error::Could not read {path}: {exc}")

    version = parse_version(source)
    if version is None:
        sys.exit(f"::error::Could not find `versionName=...` in {path}")
    error = shape_error(version)
    if error is not None:
        sys.exit(f"::error::{error} ({path})")
    return version


def self_test() -> int:
    for good in ("0.1.0", "1.2.3", "10.99.99", "0.0.0"):
        assert shape_error(good) is None, good
    for bad in ("0.01.0", "01.1.0", "0.1.00", "0.100.0", "0.1", "0.1.0-rc1", "v0.1.0"):
        assert shape_error(bad) is not None, bad
    assert parse_version("versionName=0.1.0\nversionName=0.2.0\n") == "0.2.0"
    assert parse_version("# versionName=9.9.9\nversionName = 0.1.0\n") == "0.1.0"
    assert parse_version("other=1\n") is None
    print("self-test ok")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--expect-tag",
        default=None,
        help="Require the version to equal this tag minus its leading 'v'.",
    )
    parser.add_argument(
        "--properties-path",
        type=pathlib.Path,
        default=PROPERTIES_PATH,
        help=f"Path to the properties file (default: {PROPERTIES_PATH}).",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the parser and shape checks against built-in cases, then exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    version = read_version(args.properties_path)

    if args.expect_tag is not None and args.expect_tag != f"v{version}":
        sys.exit(
            f"::error::Tag {args.expect_tag} does not match versionName {version} -- "
            f"bump {args.properties_path} or retag"
        )

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            handle.write(f"version={version}\n")

    print(version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
