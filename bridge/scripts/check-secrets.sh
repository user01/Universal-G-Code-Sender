#!/usr/bin/env bash
# Refuse commits that add credential-shaped text. Installed as .git/hooks/pre-commit (shared by all worktrees):
#   ln -sf ../../bridge/scripts/check-secrets.sh .git/hooks/pre-commit
# Usage: check-secrets.sh            scan the staged diff (pre-commit)
#        check-secrets.sh <rev-range> scan added lines in a commit range, e.g. v2.1.30..HEAD
# Scanners match the SHAPE of a secret, so fake-but-realistic test values are refused too: build test values
# at runtime from fragments instead (see NotifyConfigTest). Never commit real or realistic tokens.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if [ $# -gt 0 ]; then
  added=$(git log -p --no-color "$1" | grep -E '^\+' | grep -vE '^\+\+\+ ' || true)
else
  added=$(git diff --cached -U0 --no-color | grep -E '^\+' | grep -vE '^\+\+\+ ' || true)
fi

patterns=(
  '[0-9]{8,10}:[A-Za-z0-9_-]{30,}'                       # Telegram bot token
  'hooks\.slack\.com/services/T[A-Za-z0-9]+/B[A-Za-z0-9]+/[A-Za-z0-9]+'  # Slack incoming webhook
  'xox[abprs]-[A-Za-z0-9-]{10,}'                          # Slack API token
  'gh[pousr]_[A-Za-z0-9]{30,}'                            # GitHub token
  'github_pat_[A-Za-z0-9_]{30,}'
  'AKIA[0-9A-Z]{16}'                                      # AWS access key id
  'sk-[A-Za-z0-9_-]{20,}'                                 # API keys (OpenAI/Anthropic style)
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
)

fail=0
for p in "${patterns[@]}"; do
  hits=$(printf '%s\n' "$added" | grep -nE -- "$p" | grep -v 'REPLACE' || true)
  if [ -n "$hits" ]; then
    echo "check-secrets: credential-shaped text matches /$p/:" >&2
    printf '%s\n' "$hits" | cut -c1-160 >&2
    fail=1
  fi
done

# The operator's real notification credentials, if they have been filled in, must never appear anywhere.
notify="${UGS_BRIDGE_HOME:-$HOME/Library/Application Support/ugs-bridge}/notify.json"
if [ -f "$notify" ]; then
  while IFS= read -r secret; do
    [ -n "$secret" ] || continue
    if printf '%s\n' "$added" | grep -qF -- "$secret"; then
      echo "check-secrets: a value from $notify appears in the changes (not shown)" >&2
      fail=1
    fi
  done < <(python3 -I -c '
import json,sys
c=json.load(open(sys.argv[1]))
vals=[c.get("telegram",{}).get("bot_token",""), c.get("slack",{}).get("webhook_url","")]
for v in vals:
    if v and "REPLACE" not in v and len(v) >= 12:
        print(v.rsplit("/",1)[-1] if v.startswith("http") else v.split(":",1)[-1])
' "$notify" 2>/dev/null || true)
fi

if [ "$fail" -ne 0 ]; then
  echo "check-secrets: commit refused. Remove the value, or build test fixtures at runtime from fragments." >&2
  exit 1
fi
