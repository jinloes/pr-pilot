#!/usr/bin/env bash
# Gives Gradle owner-only copies of a GitHub Ubuntu runner's JDKs.
#
# runner-images runs `chmod -R 777` over /opt/hostedtoolcache and /usr/lib/jvm, so every
# preinstalled or setup-java JDK sits under a world-writable directory. SemanticRuntime refuses to
# launch an executable with a group- or other-writable ancestor, which is right for users and fatal
# for the review-engine tests that launch java.home. See docs/architecture/ci.md.
#
# Usage: trusted-jdks.sh <jdk-home-for-gradle> [<toolchain-jdk-home> ...]
# Exports JAVA_HOME (the first JDK) through $GITHUB_ENV and limits Gradle toolchain discovery to the
# copies through $GRADLE_USER_HOME/gradle.properties, so every later Gradle invocation uses them.
set -euo pipefail

if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
  echo "trusted-jdks.sh edits the Gradle user home; it only runs on GitHub Actions." >&2
  exit 2
fi
if [ "$#" -eq 0 ]; then
  echo "usage: $0 <jdk-home-for-gradle> [<toolchain-jdk-home> ...]" >&2
  exit 2
fi

uid="$(id -u)"

writable_by_others() {
  [ -n "$(find "$1" -maxdepth 0 -perm /022)" ]
}

# Clears group/other write on every ancestor this user owns, then fails on any it cannot fix.
harden_ancestors() {
  local dir="$1"
  while :; do
    local owner
    owner="$(stat -c %u "$dir")"
    if [ "$owner" = "$uid" ] && writable_by_others "$dir"; then
      chmod go-w "$dir"
    fi
    if [ "$owner" != "$uid" ] && [ "$owner" != 0 ]; then
      echo "::error::$dir is owned by uid $owner, so SemanticRuntime will not trust paths under it" >&2
      namei -l "$1" >&2
      exit 1
    fi
    if writable_by_others "$dir"; then
      echo "::error::$dir is group- or other-writable, so SemanticRuntime will not trust paths under it" >&2
      namei -l "$1" >&2
      exit 1
    fi
    [ "$dir" = / ] && break
    dir="$(dirname "$dir")"
  done
}

dest="$HOME/.trusted-jdks"
rm -rf "$dest"
mkdir -p "$dest"
# Canonical, like the java.home the JVM reports and SemanticRuntime checks.
dest="$(realpath -e "$dest")"

homes=()
index=0
for source in "$@"; do
  if [ ! -x "$source/bin/java" ]; then
    echo "::error::$source is not a JDK home (no bin/java)" >&2
    exit 1
  fi
  # setup-java can link a preinstalled JDK into the tool cache (x64 -> /usr/lib/jvm/...), and
  # cp -a copies such a link as a link, so copy the directory it resolves to.
  real="$(realpath -e "$source")"
  target="$dest/jdk$index"
  mkdir "$target"
  cp -a "$real/." "$target/"
  homes+=("$target")
  index=$((index + 1))
done
chmod -R go-w "$dest"
harden_ancestors "$dest"

# A JVM reports its canonical location as java.home, which is the path SemanticRuntime checks, so
# each copy must resolve to itself rather than to a world-writable original.
for home in "${homes[@]}"; do
  if ! settings="$("$home/bin/java" -XshowSettings:properties -version 2>&1)"; then
    echo "::error::$home/bin/java does not start" >&2
    printf '%s\n' "$settings" >&2
    exit 1
  fi
  reported="$(printf '%s\n' "$settings" | sed -n 's/^ *java\.home = //p')"
  if [ "$reported" != "$home" ]; then
    echo "::error::$home/bin/java reports java.home=$reported, outside the trusted copy" >&2
    namei -l "$home/bin/java" >&2
    exit 1
  fi
done

# The engine jar and test fixtures live in the checkout, so it must be trusted as well.
if [ -n "${GITHUB_WORKSPACE:-}" ]; then
  chmod -R go-w "$GITHUB_WORKSPACE"
  harden_ancestors "$GITHUB_WORKSPACE"
fi

paths="$(IFS=,; echo "${homes[*]}")"
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$gradle_home"
{
  echo "org.gradle.java.installations.auto-detect=false"
  echo "org.gradle.java.installations.paths=$paths"
} >> "$gradle_home/gradle.properties"

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "JAVA_HOME=${homes[0]}" >> "$GITHUB_ENV"
fi
if [ -n "${GITHUB_PATH:-}" ]; then
  echo "${homes[0]}/bin" >> "$GITHUB_PATH"
fi
echo "JAVA_HOME=${homes[0]}; Gradle toolchains: $paths"
