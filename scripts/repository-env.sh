#!/usr/bin/env bash

# Resolve public installer inputs from a GitHub origin while preserving explicit
# environment overrides. Source this file, then call k4a_resolve_repository_urls.
k4a_resolve_repository_urls() {
  local project_root="$1" origin repository_path
  # Transitional aliases for existing release environments.
  : "${K4A_REPOSITORY_URL:=${KAB_REPOSITORY_URL:-}}"
  : "${K4A_INSTALLER_URL:=${KAB_INSTALLER_URL:-}}"
  : "${K4A_SOURCE_REF:=${KAB_SOURCE_REF:-main}}"
  if [[ -z "${K4A_REPOSITORY_URL:-}" ]]; then
    origin="$(git -C "$project_root" remote get-url origin 2>/dev/null || true)"
    if [[ "$origin" =~ github\.com[:/]([^/]+/[^/]+)$ ]]; then
      repository_path="${BASH_REMATCH[1]%.git}"
      K4A_REPOSITORY_URL="https://github.com/$repository_path.git"
    fi
  fi
  if [[ -z "${K4A_INSTALLER_URL:-}" &&
        "${K4A_REPOSITORY_URL:-}" =~ ^https://github\.com/([^/]+/[^/]+)\.git$ ]]; then
    K4A_INSTALLER_URL="https://raw.githubusercontent.com/${BASH_REMATCH[1]}/${K4A_SOURCE_REF:-main}/installer/install.sh"
  fi
  export K4A_REPOSITORY_URL K4A_INSTALLER_URL
}
