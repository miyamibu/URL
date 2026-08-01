#!/usr/bin/env python3
"""Run the shared URL normalization JSON fixture against the Supabase SQL function."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE = REPOSITORY_ROOT / "contracts/shared-tag-sync/url-normalization-v1.json"
DEFAULT_SQL_TEST = REPOSITORY_ROOT / "supabase/tests/shared_url_normalization_vectors.sql"


def load_fixture(path: Path) -> list[dict[str, object]]:
    vectors = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(vectors, list) or not vectors:
        raise ValueError("normalization fixture must be a non-empty JSON array")
    for index, vector in enumerate(vectors):
        if not isinstance(vector, dict):
            raise ValueError(f"fixture entry {index} must be an object")
        if not isinstance(vector.get("input"), str):
            raise ValueError(f"fixture entry {index} must contain a string input")
        if "expectedNormalizedUrl" not in vector:
            raise ValueError(f"fixture entry {index} is missing expectedNormalizedUrl")
        expected = vector["expectedNormalizedUrl"]
        if expected is not None and not isinstance(expected, str):
            raise ValueError(f"fixture entry {index} expectedNormalizedUrl must be string or null")
    return vectors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--sql-test", type=Path, default=DEFAULT_SQL_TEST)
    parser.add_argument("--dsn", help="Optional psql database name or connection string")
    parser.add_argument("--psql", default="psql", help="psql executable")
    parser.add_argument(
        "--skip-sql",
        action="store_true",
        help="Validate and report the fixture without connecting to PostgreSQL",
    )
    args = parser.parse_args()

    fixture_path = args.fixture.resolve()
    vectors = load_fixture(fixture_path)
    print(f"Loaded {len(vectors)} normalization vectors from {fixture_path}")

    if args.skip_sql:
        return 0

    fixture_json = json.dumps(vectors, ensure_ascii=False, separators=(",", ":"))
    command = [args.psql]
    if args.dsn:
        command.append(args.dsn)
    command.extend(
        [
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            f"url_normalization_fixture={fixture_json}",
            "-f",
            str(args.sql_test.resolve()),
        ]
    )
    return subprocess.run(command, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
