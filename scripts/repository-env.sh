#!/usr/bin/env bash

# Resolve public installer inputs from a GitHub origin while preserving explicit
# environment overrides. Source this file, then call kab_resolve_repository_urls.
kab_resolve_repository_urls() {
  local project_root="$1" origin repository_path
  if [[ -z "${KAB_REPOSITORY_URL:-}" ]]; then
    origin="$(git -C "$project_root" remote get-url origin 2>/dev/null || true)"
    if [[ "$origin" =~ github\.com[:/]([^/]+/[^/]+)$ ]]; then
      repository_path="${BASH_REMATCH[1]%.git}"
      KAB_REPOSITORY_URL="https://github.com/$repository_path.git"
    fi
  fi
  if [[ -z "${KAB_INSTALLER_URL:-}" &&
        "${KAB_REPOSITORY_URL:-}" =~ ^https://github\.com/([^/]+/[^/]+)\.git$ ]]; then
    KAB_INSTALLER_URL="https://raw.githubusercontent.com/${BASH_REMATCH[1]}/${KAB_SOURCE_REF:-main}/installer/install.sh"
  fi
  export KAB_REPOSITORY_URL KAB_INSTALLER_URL
}
