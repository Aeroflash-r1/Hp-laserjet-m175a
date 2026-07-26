# HP LaserJet M175a → Android driver/app (reverse-engineering workspace)

Goal: a native Android app that talks directly to the HP LaserJet Pro 100
colorMFP M175a over USB — print, scan, ADF, manual duplex, paper settings —
with no PC or network in the loop (the printer has no WiFi/Ethernet).

**Read `AGENTS.md` first** — it has the full project context, confirmed
hardware facts, and current progress. Any AI coding agent working in this
repo (Claude Code, Copilot, etc.) should read it before doing anything else.

## Repo layout
.devcontainer/          Codespaces environment (tshark + Python analysis stack)
windows_capture_toolkit/  Windows-only scripts that drive USBPcap + trigger
print/scan jobs. Must run on a PC physically
plugged into the printer — cannot run in Codespaces.
tools/analyze.py         Cross-platform: turns a .pcapng into a readable
report. This is what runs inside the Codespace.
captures/                Drop your .pcapng files here.
reports/                 Generated analysis reports land here.
AGENTS.md                Context file for AI agents — read this first.
