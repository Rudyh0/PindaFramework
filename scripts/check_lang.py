#!/usr/bin/env python3
"""Controleert de taalbestanden voordat de jar gebouwd wordt.

- nl.yml en en.yml moeten dezelfde sleutels hebben;
- geen sleutels die YAML als true/false leest (on, off, yes, no, ...);
- elke berichtsleutel die in de Java-code staat, moet in nl.yml bestaan.
"""
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
LANG = ROOT / "src/main/resources/lang"
JAVA = ROOT / "src/main/java"
SECTIONS = ("general", "settings", "tips", "admin", "menu", "teleport", "homes", "tpa", "spawn",
            "back", "msg", "gamemode", "afk", "utility", "vanish", "invsee", "economy", "shop", "lock", "partner", "rank", "moderation", "panel")


def flatten(data, prefix=""):
    keys = set()
    for key, value in data.items():
        full = f"{prefix}{key}"
        keys.add(full)
        if isinstance(value, dict):
            keys |= flatten(value, full + ".")
    return keys


def leaves(data, prefix=""):
    keys = set()
    for key, value in data.items():
        full = f"{prefix}{key}"
        if isinstance(value, dict):
            keys |= leaves(value, full + ".")
        else:
            keys.add(full)
    return keys


errors = []
nl = yaml.safe_load((LANG / "nl.yml").read_text(encoding="utf-8"))
en = yaml.safe_load((LANG / "en.yml").read_text(encoding="utf-8"))

for name, data in (("nl.yml", nl), ("en.yml", en)):
    for key in flatten(data):
        if any(part in ("True", "False") for part in key.split(".")):
            errors.append(f"{name}: sleutel '{key}' wordt door YAML als true/false gelezen; kies een andere naam")

only_nl = leaves(nl) - leaves(en)
only_en = leaves(en) - leaves(nl)
for key in sorted(only_nl):
    errors.append(f"en.yml mist '{key}'")
for key in sorted(only_en):
    errors.append(f"nl.yml mist '{key}'")

known = flatten(nl)
pattern = re.compile(r'"((?:' + "|".join(SECTIONS) + r')\.[a-z0-9.-]+)"')
for file in JAVA.rglob("*.java"):
    for line in file.read_text(encoding="utf-8").splitlines():
        if "config()" in line or "cfg()" in line:
            continue  # instellingen uit een configbestand, geen berichten
        for match in pattern.finditer(line):
            key = match.group(1)
            if key.endswith(".") or key.endswith("-") or key.endswith(".yml"):
                continue
            if key not in known:
                errors.append(f"{file.relative_to(ROOT)}: bericht '{key}' bestaat niet in nl.yml")

if errors:
    print("Taalbestanden kloppen niet:")
    for error in errors:
        print("  - " + error)
    sys.exit(1)
print("Taalbestanden OK")
