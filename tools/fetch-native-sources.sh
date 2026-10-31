#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd "$(dirname "$0")/.." && pwd)
fetch_pinned() {
  local repo=$1 pin=$2 dir=$3 sparse=$4
  if [ ! -d "$dir/.git" ]; then
    mkdir -p "$dir"
    git -C "$dir" init --quiet
    git -C "$dir" remote add origin "$repo"
    git -C "$dir" sparse-checkout init --cone
    git -C "$dir" sparse-checkout set $sparse
  fi
  if ! git -C "$dir" cat-file -e "$pin^{commit}" 2>/dev/null; then
    git -C "$dir" -c protocol.version=2 fetch --depth 1 --filter=blob:none origin "$pin"
  fi
  git -C "$dir" checkout --detach "$pin"
  test "$(git -C "$dir" rev-parse HEAD)" = "$pin"
}
fetch_pinned https://github.com/drawpile/Drawpile.git 2c2a616f0e944fe86e9fd2cd420bfc3e0ee87461 "$project_root/vendor/drawpile" 'src/drawdance licenses'
fetch_pinned https://github.com/facebook/zstd.git f8745da6ff1ad1e7bab384bd1f9d742439278e99 "$project_root/vendor/zstd" 'lib'
