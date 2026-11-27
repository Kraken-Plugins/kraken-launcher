#!/usr/bin/env bash
# Prints the GitHub release notes for a launcher tag as Markdown: the commits since the previous release tag (v<number>,
# skipping -SNAPSHOT tags) grouped by conventional commit type, then a table of the release files with their SHA-256
# checksums.
#
# Usage: bash scripts/release-notes.sh <tag> [file...]
# Reads GITHUB_SERVER_URL and GITHUB_REPOSITORY for links (set by GitHub Actions), and JRE_RELEASE for the runtime
# bundled in the Windows zip. When <tag> does not exist yet the notes cover the commits up to HEAD.
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <tag> [file...]" >&2
  exit 1
fi

TAG="$1"
shift
REPO_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-Kraken-Plugins/kraken-launcher}"
MAX_COMMITS=100

REF=$(git rev-parse -q --verify "refs/tags/$TAG^{commit}" || git rev-parse HEAD)
PREV_TAG=$(git describe --tags --abbrev=0 --match 'v[0-9]*' --exclude '*-SNAPSHOT' --exclude "$TAG" "$REF" \
  2>/dev/null || true)

if [ -n "$PREV_TAG" ]; then
  RANGE="$PREV_TAG..$REF"
else
  RANGE="$REF"
fi

features=()
fixes=()
performance=()
other=()
count=0
truncated=""
conventional='^([a-zA-Z]+)(\(([^)]*)\))?(!)?:[[:space:]]*(.+)$'

while IFS=$'\t' read -r short full subject; do
  if [ "$count" -ge "$MAX_COMMITS" ]; then
    truncated="yes"
    break
  fi
  count=$((count + 1))
  subject="${subject// \[skip ci\]/}"

  if [[ $subject =~ $conventional ]]; then
    type="${BASH_REMATCH[1],,}"
    scope="${BASH_REMATCH[3]}"
    breaking="${BASH_REMATCH[4]}"
    description="${BASH_REMATCH[5]}"
  else
    type=""
    scope=""
    breaking=""
    description="$subject"
  fi

  line="- "
  if [ -n "$breaking" ]; then
    line+="**Breaking:** "
  fi
  if [ -n "$scope" ]; then
    line+="**$scope:** "
  fi
  line+="$description ([\`$short\`]($REPO_URL/commit/$full))"

  case "$type" in
    feat) features+=("$line") ;;
    fix) fixes+=("$line") ;;
    perf) performance+=("$line") ;;
    *) other+=("$line") ;;
  esac
done < <(git log --no-merges --max-count="$((MAX_COMMITS + 1))" --format='%h%x09%H%x09%s' "$RANGE")

print_section() {
  local title="$1"
  shift
  if [ $# -gt 0 ]; then
    printf '### %s\n\n' "$title"
    printf '%s\n' "$@"
    printf '\n'
  fi
}

echo "## What's changed"
echo
if [ "$count" -eq 0 ]; then
  echo "No new commits${PREV_TAG:+ since $PREV_TAG}."
  echo
else
  print_section "New features" ${features[@]+"${features[@]}"}
  print_section "Bug fixes" ${fixes[@]+"${fixes[@]}"}
  print_section "Performance" ${performance[@]+"${performance[@]}"}
  print_section "Other changes" ${other[@]+"${other[@]}"}
  if [ -n "$truncated" ]; then
    echo "Only the latest $MAX_COMMITS commits are listed."
    echo
  fi
fi

if [ $# -gt 0 ]; then
  echo "## Downloads"
  echo
  echo "| File | Use | SHA-256 |"
  echo "|------|-----|---------|"
  for file in "$@"; do
    name=$(basename "$file")
    case "$name" in
      *Windows.zip) use="Windows. Unzip it and run \`KrakenInstaller.exe\` from the \`KrakenLauncher\` folder." ;;
      *.exe) use="Windows installer only. It needs the \`jre\` folder from the zip next to it." ;;
      *.jar) use="macOS, or Windows with Java 17 installed. Double click it to install." ;;
      *) use="" ;;
    esac
    sum=$(sha256sum "$file" | cut -d ' ' -f 1)
    echo "| \`$name\` | $use | \`$sum\` |"
  done
  echo
  if [ -n "${JRE_RELEASE:-}" ]; then
    echo "The Windows zip bundles the Eclipse Temurin \`$JRE_RELEASE\` runtime."
    echo
  fi
  echo "The installer is not code signed, so Windows SmartScreen may warn the first time it runs. Click **More info**, then **Run anyway**."
  echo
fi

if [ -n "$PREV_TAG" ]; then
  echo "**Full changelog:** $REPO_URL/compare/$PREV_TAG...$TAG"
fi
