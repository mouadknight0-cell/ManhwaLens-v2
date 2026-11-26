"""Compute a release-candidate versionCode that is installable and monotonic.

A candidate APK must be installable *over* three independent floors:

  * the newest published (non-prerelease, non-draft) release;
  * whatever build is already installed on the test phone;
  * an explicit ``min_installed_version_code`` supplied by the operator.

Every floor is required. A missing, malformed or non-positive value fails closed instead of
being treated as "no floor" -- silently dropping a constraint is exactly how a candidate ends
up un-installable, or how a test build reuses a number the eventual release needs.

The candidate itself is ``floor + 1``, never the commit's per-commit release code, so a test
build does not burn the number the tagged release will use.
"""
from __future__ import annotations

EPOCH = 1577836800  # 2020-01-01T00:00:00Z, the base of the per-commit release code.
ANDROID_MAX_VERSION_CODE = 2100000000  # 2_100_000_000, the practical Play/AAPT ceiling.
MAX_CANDIDATE = ANDROID_MAX_VERSION_CODE


class VersionFloorError(ValueError):
    """A floor is missing or unusable; the candidate must not be produced."""


def release_code(commit_epoch_seconds: int) -> int:
    """Per-commit versionCode, identical to the one tag-based releases use."""
    code = int(commit_epoch_seconds) - EPOCH
    if code <= 0:
        raise VersionFloorError(f'commit timestamp {commit_epoch_seconds} is before the release epoch')
    return code


def parse_floor(raw: object, label: str) -> int:
    """A floor must be an explicit positive integer. Anything else is a hard failure."""
    if raw is None:
        raise VersionFloorError(f'{label} is required but was not provided')
    text = str(raw).strip()
    if not text:
        raise VersionFloorError(f'{label} is empty')
    if not text.isdigit():
        raise VersionFloorError(f'{label} must be a positive integer, got {text!r}')
    value = int(text)
    if value <= 0:
        raise VersionFloorError(f'{label} must be > 0, got {value}')
    if value > MAX_CANDIDATE:
        raise VersionFloorError(f'{label} {value} exceeds the Android versionCode maximum')
    return value


def candidate_version_code(last_release_code: object, installed_version_code: object,
                           min_installed_version_code: object,
                           commit_epoch_seconds: int) -> tuple[int, int]:
    """Return ``(candidate, floor)``.

    Raises :class:`VersionFloorError` when no installable, monotonic candidate exists. The
    check is deliberately redundant: the candidate is validated against every floor
    individually as well as against the computed floor, so a future change to the floor
    selection cannot quietly admit an un-installable build.
    """
    last = parse_floor(last_release_code, 'last published release versionCode')
    installed = parse_floor(installed_version_code, 'installed_version_code')
    minimum = parse_floor(min_installed_version_code, 'min_installed_version_code')

    code = release_code(commit_epoch_seconds)
    if code <= last:
        raise VersionFloorError(
            f'this commit\'s release code {code} is not greater than the newest release {last}')

    floor = max(last, installed, minimum)
    candidate = floor + 1
    if candidate <= last or candidate <= installed or candidate <= minimum:
        raise VersionFloorError(
            f'candidate {candidate} does not clear last release {last}, '
            f'installed {installed} and min installed {minimum}')
    if candidate > MAX_CANDIDATE:
        raise VersionFloorError(
            f'candidate versionCode {candidate} exceeds the Android maximum {MAX_CANDIDATE}')
    return candidate, floor


def main(argv: list[str] | None = None) -> int:
    """CLI used by release-candidate.yml: print shell assignments, or fail closed on stderr."""
    import argparse
    import sys

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--last-release-code', required=True)
    parser.add_argument('--installed-version-code', required=True)
    parser.add_argument('--min-installed-version-code', required=True)
    parser.add_argument('--commit-epoch', required=True, type=int)
    args = parser.parse_args(argv)

    try:
        value, floor = candidate_version_code(
            args.last_release_code, args.installed_version_code,
            args.min_installed_version_code, args.commit_epoch)
    except VersionFloorError as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1

    print(f'candidate={value}')
    print(f'floor={floor}')
    print(f'installed={parse_floor(args.installed_version_code, "installed_version_code")}')
    print(f'min_installed={parse_floor(args.min_installed_version_code, "min_installed_version_code")}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
