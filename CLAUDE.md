# ugs-bridge: notes for Claude

This repo is a **fork of Universal Gcode Sender** (upstream `winder/Universal-G-Code-Sender`, GPLv3) with the ugs-bridge additions. Spec: `bridge/SPEC.md`. Plan and status: `bridge/PLAN.md`.

## Layout

- Upstream UGS tree at the root. Branch `bridge` is based on tag `v2.1.30`; rebase onto new release tags (`git fetch upstream --tags`). Remotes: `origin` = user01/Universal-G-Code-Sender (push here), `upstream` = winder (read only).
- Our Java code: `ugs-platform/ugs-platform-plugin-bridge/` (NetBeans module). Keep upstream edits minimal and listed in `bridge/PLAN.md` § Upstream patches, so rebases stay easy.
- Everything non-Java lives under `bridge/`: `mcp/` (Python MCP server, uv), `viewer/` (Three.js web app), `sim/` (GRBL simulator over TCP), `profiles/`, `docs/`, `scripts/`.

## Build and run

- Java 25 is required (Homebrew `openjdk@25`, keg-only). Always go through `bridge/scripts/dev.sh` (sets `JAVA_HOME`): `build`, `build-bridge`, `run`, `test <module>`.
- The dev app (`dev.sh run`) and the installed UGS 2.1.23 can't both hold the serial port. Quit one first.
- Opening the serial port resets the controller (DTR): it comes up in alarm and must be re-homed.
- For testing without hardware, connect UGS with the **TCP** driver to the simulator in `bridge/sim` (no pty needed).

## Rules

- Never send motion to the real machine from tests or scripts. Hardware steps only via the checklist in `bridge/docs/hardware-validation.md` with the operator present.
- Python: uv for everything; `uv run pytest`, `uv run ruff check`, `uv run mypy` before committing. Reuse the GRBL parser/simulator from `../resurfacer` rather than copying it.
- Secrets (bridge token, Telegram/Slack credentials) live in `~/Library/Application Support/ugs-bridge/` with mode 0600, never in the repo.
- Don't push or open PRs upstream unless asked. Commits end with the Co-Authored-By line from the session.
