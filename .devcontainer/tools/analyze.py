"""
HP M175a — Codespace-side capture analyzer
===========================================
Runs anywhere tshark is installed (this devcontainer already has it).
This is the "read and make sense of a .pcapng" half of the project — the
"actually capture USB traffic" half only works on the Windows PC physically
connected to the printer (see ../windows_capture_toolkit/).

Usage:
    python tools/analyze.py --pcap captures/scan_flatbed.pcapng
    python tools/analyze.py --pcap captures/scan_flatbed.pcapng --endpoint 0x03
    python tools/analyze.py --pcap captures/scan_flatbed.pcapng --use-llm --llm-key YOUR_KEY --llm-provider gemini

--endpoint filters to one USB endpoint address (e.g. 0x03 = EP3 OUT,
0x83 = EP3 IN). Very useful for scan captures: EP3 OUT is the small command
channel (SCL commands), EP3/EP4 IN carries the (large, usually irrelevant
for protocol purposes) image data.
"""

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
REPORTS_DIR = REPO_ROOT / "reports"

KNOWN_MARKERS = {
    "1B252D3132333435": "PJL universal exit language / UEL (ESC%-12345X)",
    "40504A4C": "'@PJL' ASCII — start of a PJL command line",
    "1B45": "PCL reset (ESC E)",
    "1B26": "PCL parameterized escape sequence prefix (ESC &)",
    "1B2A": "PCL/SCL asterisk-class escape sequence prefix (ESC *)",
    "1B4F": "Possible SCL command prefix (ESC O) — seen in some HP scan protocols",
}


def find_tshark():
    path = shutil.which("tshark")
    if not path:
        sys.exit("tshark not found on PATH. If you're not in the devcontainer, "
                  "install Wireshark/tshark first.")
    return path


def convert_pcap_to_json(pcap_path):
    tshark = find_tshark()
    json_path = Path(pcap_path).with_suffix(".json")
    print(f"Converting {pcap_path} -> {json_path} ...")
    with open(json_path, "w", encoding="utf-8") as f:
        subprocess.run([tshark, "-r", str(pcap_path), "-T", "json"], stdout=f, check=True)
    return json_path


def _walk(obj, endpoint_filter, out):
    """Recursively find USB packets and their capdata, optionally filtered
    by endpoint address. tshark's JSON layer nesting varies by version, so
    we search structurally instead of assuming a fixed schema."""
    if isinstance(obj, dict):
        layers = obj.get("_source", {}).get("layers")
        if layers is not None:
            usb = layers.get("usb", {})
            ep = usb.get("usb.endpoint_address") or usb.get("usb.endpoint_address_tree", {}).get("usb.endpoint_address")
            capdata = None
            for k, v in layers.items():
                if isinstance(v, str) and "capdata" in k:
                    capdata = v
            if capdata and (endpoint_filter is None or ep == endpoint_filter):
                out.append({"endpoint": ep, "data": capdata})
        for v in obj.values():
            _walk(v, endpoint_filter, out)
    elif isinstance(obj, list):
        for item in obj:
            _walk(item, endpoint_filter, out)


def extract_payloads(json_path, endpoint_filter=None):
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    out = []
    _walk(data, endpoint_filter, out)
    return out


def annotate_locally(payloads):
    hits = []
    for i, p in enumerate(payloads):
        clean = p["data"].replace(":", "").upper()
        for marker, meaning in KNOWN_MARKERS.items():
            if marker in clean:
                hits.append((i, p.get("endpoint"), marker, meaning))
    return hits


def call_llm(prompt, api_key, provider, model):
    import requests
    if provider == "gemini":
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
        resp = requests.post(url, json={"contents": [{"parts": [{"text": prompt}]}]}, timeout=60)
        resp.raise_for_status()
        return resp.json()["candidates"][0]["content"]["parts"][0]["text"]
    elif provider == "anthropic":
        url = "https://api.anthropic.com/v1/messages"
        headers = {
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        }
        body = {"model": model, "max_tokens": 2000, "messages": [{"role": "user", "content": prompt}]}
        resp = requests.post(url, headers=headers, json=body, timeout=60)
        resp.raise_for_status()
        return resp.json()["content"][0]["text"]
    else:
        sys.exit(f"Unknown provider: {provider}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pcap", required=True)
    ap.add_argument("--endpoint", help="Filter to one endpoint address, e.g. 0x03 or 0x83")
    ap.add_argument("--max-payloads", type=int, default=200,
                     help="Cap how many payload fragments go in the report (protects against huge image-data dumps)")
    ap.add_argument("--use-llm", action="store_true")
    ap.add_argument("--llm-provider", choices=["gemini", "anthropic"], default="gemini")
    ap.add_argument("--llm-key")
    ap.add_argument("--llm-model", default="gemini-2.5-flash",
                     help="Check the provider's current docs — model names change over time")
    args = ap.parse_args()

    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    json_path = convert_pcap_to_json(args.pcap)
    payloads = extract_payloads(json_path, endpoint_filter=args.endpoint)
    print(f"Extracted {len(payloads)} payload fragments"
          + (f" on endpoint {args.endpoint}" if args.endpoint else "") + ".")

    capped = payloads[: args.max_payloads]
    hits = annotate_locally(capped)

    lines = [
        f"# Analysis: {Path(args.pcap).name}"
        + (f" (endpoint {args.endpoint})" if args.endpoint else ""),
        "",
        f"Total payloads found: {len(payloads)} (showing first {len(capped)})",
        "",
        "## Known-marker hits",
    ]
    if hits:
        for i, ep, marker, meaning in hits:
            lines.append(f"- payload #{i} (endpoint {ep}): `{marker}` -> {meaning}")
    else:
        lines.append("(no hard-coded markers matched in this slice)")

    lines.append("")
    lines.append("## Raw payloads")
    for i, p in enumerate(capped):
        lines.append(f"{i} [ep {p.get('endpoint')}]: {p['data']}")

    if args.use_llm:
        if not args.llm_key:
            sys.exit("--llm-key required with --use-llm")
        sample = "\n".join(f"[ep {p.get('endpoint')}] {p['data']}" for p in capped[:80])
        prompt = (
            "You're reverse-engineering the USB protocol of an HP LaserJet Pro 100 "
            "colorMFP M175a. Below are raw USB bulk-transfer payloads (hex), each "
            "tagged with the USB endpoint they came from. Endpoint 0x03/0x83 relates "
            "to the printer's vendor-specific 'HP SCAN' interface. Identify likely "
            "HP SCL (Scanner Control Language) commands, parameter fields (e.g. "
            "resolution, color mode, page size), status/handshake responses, and any "
            "repeating framing. Note byte offsets where useful. Say clearly when "
            "something is ambiguous rather than guessing.\n\n" + sample
        )
        try:
            annotation = call_llm(prompt, args.llm_key, args.llm_provider, args.llm_model)
            lines.append("\n## LLM annotation\n")
            lines.append(annotation)
        except Exception as e:
            lines.append(f"\n## LLM annotation\n(failed: {e})")

    suffix = f"_ep{args.endpoint}" if args.endpoint else ""
    report_path = REPORTS_DIR / f"{Path(args.pcap).stem}{suffix}_report.md"
    report_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"Report written to: {report_path}")


if __name__ == "__main__":
    main()
