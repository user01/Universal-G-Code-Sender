# Running UGS with the bridge

```bash
bridge/scripts/ugs.sh          # real machine: connect in the UGS window as usual
bridge/scripts/ugs.sh sim      # simulated Shapeoko, no machine needed (auto-connects over local TCP)
bridge/scripts/ugs.sh build    # rebuild only (runs also rebuild automatically when sources changed)
bridge/scripts/ugs.sh mcp      # prints the command that registers Claude's MCP server
```

- **Settings:** this build keeps its own UGS settings in `~/Library/Application Support/ugs-bridge/ugs-home`. The first run copies the installed UGS's settings (port, baud, macros, tool library). After that, neither build changes the other's settings. Only one of them can hold the serial port at a time.
- **Claude starts locked** every launch. Locked, Claude can still read everything (status, console, logs) but can't send anything. Click the padlock in the toolbar (or press Cmd+Shift+L) to allow commands, then choose a level in the Claude panel. Motion and Stream expire after 15 minutes unused and on any alarm, reset or disconnect.
- **BitSetter tool changes** are on (Claude panel checkbox). Measure the reference once after zeroing Z with the BitZero; each `M6` then parks, asks for the tool, measures it and sets Z.
- **Files:** session logs, origins, the job journal, `notify.json` (Telegram/Slack) and the API token are in `~/Library/Application Support/ugs-bridge/`.
- **Claude:** run the `mcp` command once in a terminal (`claude mcp add …`), then start Claude Code. Its read tools work whenever UGS is running; anything that sends needs the padlock unlocked.
- **Before relying on it on the machine,** work through `bridge/docs/machine-ledger.md`.
