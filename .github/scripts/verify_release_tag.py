"""Resolve a release tag to the commit it points at, and prove it is the build commit.

A release is only evidence if its tag names the code that produced the attached APK.
GitHub's ``gh release create`` without ``--target`` puts the tag on the repository's
*default branch*, not on the commit being built, so a candidate can be published with
a tag that has nothing to do with its own contents. That happened: rc4's tag and APK
disagreed, and nothing in the workflow noticed, because a release title and its notes
are free text and prove nothing about a ref.

This module answers one question -- *which commit does this tag resolve to?* -- for
both tag shapes, and compares it to the build commit. Anything it cannot prove is a
failure, never a pass.
"""
from __future__ import annotations

import json
import subprocess

__all__ = ['TagResolution', 'TagError', 'resolve_tag_commit', 'assert_tag_matches', 'TAG_REF_PREFIX']

TAG_REF_PREFIX = 'refs/tags/'


class TagError(RuntimeError):
    """The tag could not be resolved, or it does not name the commit under test."""


class TagResolution:
    """The commit a tag resolves to, and how it got there."""

    def __init__(self, tag: str, commit: str, kind: str):
        #: Tag name without the ``refs/tags/`` prefix.
        self.tag = tag
        #: The 40-hex commit the tag ultimately names.
        self.commit = commit
        #: ``lightweight`` (ref points at a commit) or ``annotated`` (ref points at a
        #: tag object, which points at -- possibly another -- object).
        self.kind = kind

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f'TagResolution({self.tag!r}, {self.commit!r}, {self.kind!r})'

    def __eq__(self, other: object) -> bool:
        return (
            isinstance(other, TagResolution)
            and (self.tag, self.commit, self.kind) == (other.tag, other.commit, other.kind)
        )


def _api(path: str) -> dict:
    """One ``gh api`` GET, returning parsed JSON.

    ``path`` starts with ``repos/{owner}/{repo}/``. ``gh`` fills those placeholders in
    from ``$GITHUB_REPOSITORY`` inside Actions, and from the current checkout's remote
    otherwise, so this module never has to name the repository itself.
    """
    try:
        proc = subprocess.run(
            ['gh', 'api', path], capture_output=True, text=True, timeout=60,
        )
    except (OSError, subprocess.SubprocessError) as error:  # pragma: no cover - env dependent
        raise TagError(f'could not run gh api {path}: {error}') from error
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or '').strip()
        raise TagError(f'gh api {path} failed: {detail}')
    try:
        return json.loads(proc.stdout)
    except (TypeError, ValueError) as error:
        raise TagError(f'gh api {path} did not return JSON: {error}') from error


def resolve_tag_commit(tag: str, api=_api, max_depth: int = 4) -> TagResolution:
    """Resolve ``tag`` to a commit SHA, dereferencing annotated tags.

    A lightweight tag's ref points straight at a commit. An annotated tag's ref points at
    a tag object, which has its own ``object``; that object is normally a commit but may
    itself be a tag. The loop follows the chain and refuses to guess: a chain that does
    not end at a commit, or that goes deeper than ``max_depth``, is an error.
    """
    if not tag or not tag.strip():
        raise TagError('a tag name is required')
    name = tag[len(TAG_REF_PREFIX):] if tag.startswith(TAG_REF_PREFIX) else tag

    ref = api(f'repos/{{owner}}/{{repo}}/git/ref/tags/{name}')
    obj = ref.get('object') or {}
    kind = obj.get('type')
    sha = obj.get('sha')
    if not isinstance(sha, str) or not sha:
        raise TagError(f'tag {name} ref has no object sha: {ref!r}')

    if kind == 'commit':
        return TagResolution(name, sha, 'lightweight')

    depth = 0
    while kind == 'tag':
        depth += 1
        if depth > max_depth:
            raise TagError(f'tag {name} nests deeper than {max_depth} tag objects')
        annotated = api(f'repos/{{owner}}/{{repo}}/git/tags/{sha}')
        obj = annotated.get('object') or {}
        kind = obj.get('type')
        sha = obj.get('sha')
        if not isinstance(sha, str) or not sha:
            raise TagError(f'tag object {sha!r} for {name} has no target sha')

    if kind != 'commit':
        raise TagError(f'tag {name} resolves to a {kind}, not a commit')

    return TagResolution(name, sha, 'annotated')


def assert_tag_matches(tag: str, expected_commit: str, api=_api) -> TagResolution:
    """Prove ``tag`` names ``expected_commit``; return the resolution when it does.

    Comparison is exact and case-insensitive only because Git SHAs are hex. An
    abbreviated SHA is never accepted as a match, since it does not identify a commit.
    """
    expected = (expected_commit or '').strip().lower()
    if len(expected) != 40 or any(c not in '0123456789abcdef' for c in expected):
        raise TagError(f'expected commit {expected_commit!r} is not a full 40-hex SHA')
    resolution = resolve_tag_commit(tag, api=api)
    actual = resolution.commit.strip().lower()
    if actual != expected:
        raise TagError(
            f'tag {resolution.tag} resolves to {actual} ({resolution.kind}), '
            f'but this build is {expected}')
    return resolution


def main(argv: list[str] | None = None, api=_api) -> int:
    """CLI for release-candidate.yml.

    Two modes:

    * ``--tag T --exists`` — exit 0 when the tag exists, 3 when it does not. Used to
      refuse a name that is already taken instead of overwriting a published tag.
    * ``--tag T --expect COMMIT`` — prove the tag names ``COMMIT``; print the resolved
      commit and its kind, or fail with the reason.
    """
    import argparse
    import sys

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--tag', required=True)
    parser.add_argument('--expect', default=None)
    parser.add_argument('--exists', action='store_true')
    args = parser.parse_args(argv)

    if args.exists:
        try:
            resolution = resolve_tag_commit(args.tag, api=api)
        except TagError as error:
            message = str(error)
            if 'failed' in message and ('Not Found' in message or '404' in message):
                print(f'tag {args.tag} does not exist')
                return 3
            print(f'::error::{message}', file=sys.stderr)
            return 1
        print(f'tag {args.tag} exists at {resolution.commit} ({resolution.kind})')
        return 0

    if not args.expect:
        print('::error::--expect COMMIT is required unless --exists is given', file=sys.stderr)
        return 1
    try:
        resolution = assert_tag_matches(args.tag, args.expect, api=api)
    except TagError as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1
    print(f'tag={resolution.tag} commit={resolution.commit} kind={resolution.kind}'
          f' matches_expected=true')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
