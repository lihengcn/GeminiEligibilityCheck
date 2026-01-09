#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar_file="${script_dir}/app.jar"

if [[ ! -f "${jar_file}" ]]; then
  echo "No executable jar found. Please place app.jar in the current directory." >&2
  exit 1
fi

# Default to SQLite with a local db file in deploy/.
java \
  -Dspring.profiles.active=sqlite \
  -Dspring.datasource.url="jdbc:sqlite:${script_dir}/gem.db" \
  -jar "${jar_file}" "$@"
