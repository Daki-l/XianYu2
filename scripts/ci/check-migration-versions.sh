#!/usr/bin/env bash
set -Eeuo pipefail

readonly migration_dir='src/main/resources/db/migration'
readonly version_pattern='^V([0-9]+([._][0-9]+)*)__.*\.sql$'

base_ref="${1:?Usage: $0 <base-ref> [head-ref]}"
head_ref="${2:-HEAD}"

if ! git rev-parse --verify --quiet "${base_ref}^{commit}" >/dev/null; then
  echo "Base revision does not exist: ${base_ref}" >&2
  exit 2
fi

if ! git rev-parse --verify --quiet "${head_ref}^{commit}" >/dev/null; then
  echo "Head revision does not exist: ${head_ref}" >&2
  exit 2
fi

migration_version() {
  local migration_path="$1"
  local filename="${migration_path##*/}"
  if [[ "$filename" =~ $version_pattern ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi

  echo "Invalid Flyway migration filename: ${migration_path}" >&2
  exit 1
}

mapfile -t base_migrations < <(git ls-tree -r --name-only "$base_ref" -- "$migration_dir" | sort)
mapfile -t head_migrations < <(git ls-tree -r --name-only "$head_ref" -- "$migration_dir" | sort)

declare -A base_migration_paths=()
for migration_path in "${base_migrations[@]}"; do
  base_migration_paths["$migration_path"]=1
done

# 已发布迁移属于数据库历史记录的一部分；即使仅改注释也会改变 Flyway checksum。
# 新增迁移可以继续走下面的版本校验，但基线中存在的迁移不得修改、重命名或删除。
immutable_failed=0
for migration_path in "${base_migrations[@]}"; do
  if ! git cat-file -e "${head_ref}:${migration_path}" 2>/dev/null; then
    echo "Migration immutability check failed: existing migration was removed or renamed: ${migration_path}" >&2
    immutable_failed=1
    continue
  fi
  base_blob="$(git rev-parse "${base_ref}:${migration_path}")"
  head_blob="$(git rev-parse "${head_ref}:${migration_path}")"
  if [[ "$base_blob" != "$head_blob" ]]; then
    echo "Migration immutability check failed: existing migration was modified: ${migration_path}" >&2
    immutable_failed=1
  fi
done

if [[ "$immutable_failed" -ne 0 ]]; then
  echo 'Existing Flyway migrations are immutable. Add a new higher-version migration instead.' >&2
  exit 1
fi

declare -a base_versions=()
declare -a head_versions=()
declare -a new_migrations=()

for migration_path in "${base_migrations[@]}"; do
  base_versions+=("$(migration_version "$migration_path")")
done

for migration_path in "${head_migrations[@]}"; do
  version="$(migration_version "$migration_path")"
  head_versions+=("$version")
  if [[ -z "${base_migration_paths[$migration_path]+present}" ]]; then
    new_migrations+=("$migration_path")
  fi
done

duplicate_versions="$(printf '%s\n' "${head_versions[@]}" | sort -V | uniq -d)"
if [[ -n "$duplicate_versions" ]]; then
  echo 'Migration version check failed: duplicate Flyway versions found.' >&2
  printf '%s\n' "$duplicate_versions" | sed 's/^/  V/' >&2
  exit 1
fi

if [[ "${#new_migrations[@]}" -eq 0 ]]; then
  echo 'Migration version check passed: no new migrations.'
  exit 0
fi

if [[ "${#base_versions[@]}" -eq 0 ]]; then
  echo 'Migration version check passed: no existing migrations in the base revision.'
  exit 0
fi

base_max_version="$(printf '%s\n' "${base_versions[@]}" | sort -V | tail -n 1)"
failed=0
for migration_path in "${new_migrations[@]}"; do
  version="$(migration_version "$migration_path")"
  greatest_version="$(printf '%s\n%s\n' "$base_max_version" "$version" | sort -V | tail -n 1)"
  if [[ "$version" == "$base_max_version" || "$greatest_version" != "$version" ]]; then
    echo "Migration version check failed: ${migration_path} is V${version}, but the base revision already reaches V${base_max_version}." >&2
    failed=1
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo 'New migrations must use versions greater than every migration in the base revision.' >&2
  exit 1
fi

echo "Migration version check passed: ${#new_migrations[@]} new migration(s), all newer than V${base_max_version}."
