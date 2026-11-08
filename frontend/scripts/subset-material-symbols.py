#!/usr/bin/env python3
"""Builds the self-hosted Material Symbols Outlined subset used by the web UI.

The full font has ~4,000 icons (756 KB as WOFF2 even with only the weight axis); the UI uses a few
dozen. This script:

  1. reads the Fontsource variable font (weight axis only; FILL 0, GRAD 0, opsz 24 baked in),
  2. pins the weight axis at 400 — the only weight the UI renders,
  3. removes every icon ligature (`rlig`) except the names listed in
     src/assets/fonts/material-symbols.icons.txt,
  4. subsets to the remaining glyphs and writes WOFF2.

Icons render through ligatures: the text `inventory_2` in a <mat-icon> is substituted by the
`inventory_2` glyph. Ligature components (letters, digits, `_`) are kept, so names still shape.

The source font is licensed under the SIL Open Font License 1.1 with no Reserved Font Name, so a
subset may keep its family name. The license ships alongside the font (public/licenses/).

Requirements (developer machine only; the output is committed, CI and Docker do not run this):
    python3 -m venv .venv && .venv/bin/pip install fonttools==4.60.1 brotli==1.1.0
Usage (from frontend/):
    .venv/bin/python scripts/subset-material-symbols.py      # or: npm run icons:subset
"""

from __future__ import annotations

import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "node_modules/@fontsource-variable/material-symbols-outlined/files/material-symbols-outlined-latin-wght-normal.woff2"
MANIFEST = ROOT / "src/assets/fonts/material-symbols.icons.txt"
OUTPUT = ROOT / "src/assets/fonts/material-symbols-outlined-subset.woff2"


def read_manifest() -> list[str]:
    names = []
    for line in MANIFEST.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.append(line)
    if len(set(names)) != len(names):
        sys.exit("duplicate icon names in manifest")
    return names


def ligature_subtables(font: TTFont):
    for lookup in font["GSUB"].table.LookupList.Lookup:
        for sub in lookup.SubTable:
            real = sub.ExtSubTable if lookup.LookupType == 7 else sub
            if real.LookupType == 4:
                yield real


def main() -> None:
    wanted = set(read_manifest())
    font = TTFont(SOURCE)
    font = instancer.instantiateVariableFont(font, {"wght": 400}, inplace=False)

    char_of = {glyph: chr(code) for code, glyph in font.getBestCmap().items()}
    kept_targets: dict[str, str] = {}

    for table in ligature_subtables(font):
        pruned = {}
        for first, ligatures in table.ligatures.items():
            keep = []
            for lig in ligatures:
                try:
                    name = char_of[first] + "".join(char_of[c] for c in lig.Component)
                except KeyError:
                    continue
                if name in wanted:
                    keep.append(lig)
                    kept_targets[name] = lig.LigGlyph
            if keep:
                pruned[first] = keep
        table.ligatures = pruned

    missing = sorted(wanted - kept_targets.keys())
    if missing:
        sys.exit(f"icons not found in the font: {', '.join(missing)}")

    options = subset.Options()
    options.layout_features = ["rlig"]
    options.flavor = "woff2"
    options.hinting = False
    options.desubroutinize = True
    options.name_IDs = ["*"]  # keep copyright, license and version strings
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(
        unicodes=[ord(c) for name in wanted for c in name],
        glyphs=list(kept_targets.values()),
    )
    subsetter.subset(font)
    font.flavor = "woff2"
    font.save(OUTPUT)
    print(f"{len(wanted)} icons -> {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
