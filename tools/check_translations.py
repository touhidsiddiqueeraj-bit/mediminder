#!/usr/bin/env python3
"""Translation consistency checker for MediMinder.

Verifies, for every values-<locale>/strings.xml against values/strings.xml:
  1. XML is well-formed.
  2. Key parity (locale files must translate every default key except those
     intentionally skipped, like app_name).
  3. Format specifiers (%1$s, %1$d, %%) match the default exactly per key.
  4. Apostrophes are escaped (\\') unless the whole string is double-quoted.
  5. <plurals> provide the categories required for their language
     (all six for Arabic, one/other elsewhere, many for fr/pt).

Run:  python3 tools/check_translations.py
Exits non-zero on any problem; prints a short report.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"

# Keys that are intentionally not translated (brand name).
SKIP_KEYS = {"app_name"}

# Valid Android plural categories.
VALID_CATEGORIES = {"zero", "one", "two", "few", "many", "other"}

# Categories each language must ship (CLDR rules for "N days").
REQUIRED_PLURAL_CATEGORIES = {
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "bn": {"one", "other"},
    "hi": {"one", "other"},
    "es": {"one", "other"},
    "fr": {"one", "many", "other"},
    "pt": {"one", "many", "other"},
}

FMT = re.compile(r"%\d+\$[sd]|%%")


def parse(path: Path):
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for el in root:
        if el.tag == "string":
            strings[el.get("name")] = "".join(el.itertext())
        elif el.tag == "plurals":
            plurals[el.get("name")] = {item.get("quantity") for item in el}
    return strings, plurals


def check_apostrophes(path: Path, strings: dict, problems: list):
    """Android: an unescaped ' toggles quoting unless the string starts+ends with "."""
    text = path.read_text(encoding="utf-8")
    for m in re.finditer(r'<string name="([^"]+)"(.*?)>(.*?)</string>', text, re.S):
        name, body = m.group(1), m.group(3)
        if name not in strings:
            continue
        value = body
        if value.startswith('"') and value.endswith('"'):
            continue  # whole-string double quoting: apostrophes are literal
        # Strip escaped apostrophes, then any remaining bare one is a problem.
        if value.replace("\\'", "").find("'") >= 0:
            problems.append(f"{path.name}: unescaped apostrophe in '{name}'")


def main() -> int:
    default_path = RES / "values/strings.xml"
    try:
        default_root = ET.parse(default_path).getroot()
    except ET.ParseError as e:
        print(f"FAIL: values/strings.xml is not valid XML: {e}")
        return 1
    default_strings, default_plurals = parse(default_path)
    print(f"default: {len(default_strings)} strings, {len(default_plurals)} plurals")

    problems = []
    locales = sorted(
        d.name for d in RES.iterdir()
        if d.is_dir() and d.name.startswith("values-") and (d / "strings.xml").exists()
    )
    if not locales:
        print("FAIL: no translated values-* directories found")
        return 1

    for loc in locales:
        path = RES / loc / "strings.xml"
        tag = loc.removeprefix("values-")
        try:
            locale_root = ET.parse(path).getroot()
            strings, plurals = parse(path)
        except ET.ParseError as e:
            problems.append(f"{loc}: not valid XML: {e}")
            continue

        # 2. key parity
        missing = sorted(
            k for k in default_strings if k not in strings and k not in SKIP_KEYS
        )
        extra = sorted(k for k in strings if k not in default_strings)
        for k in missing:
            problems.append(f"{loc}: missing translation for '{k}'")
        for k in extra:
            problems.append(f"{loc}: unknown key '{k}' (not in default)")

        # 3. format specifiers
        for key, value in strings.items():
            if key not in default_strings:
                continue
            want = sorted(FMT.findall(default_strings[key]))
            got = sorted(FMT.findall(value))
            if want != got:
                problems.append(
                    f"{loc}: '{key}' format args {got} != default {want}"
                )

        # 4. apostrophe escaping
        check_apostrophes(path, strings, problems)

        # 5. plurals
        for name, _default_cats in default_plurals.items():
            if name not in plurals:
                if name not in SKIP_KEYS:
                    problems.append(f"{loc}: missing plurals '{name}'")
                continue
            lcats = plurals[name]
            invalid = lcats - VALID_CATEGORIES
            if invalid:
                problems.append(f"{loc}: '{name}' invalid categories {invalid}")
            required = REQUIRED_PLURAL_CATEGORIES.get(tag, {"one", "other"})
            lacking = required - lcats
            if lacking:
                problems.append(f"{loc}: '{name}' missing categories {lacking}")
            # Plural items may use a SUBSET of the default's format args:
            # some languages naturally drop the numeral (Arabic "one day"),
            # and Android ignores unused arguments. Anything else is an error.
            default_args = set()
            for pl in default_root.iter("plurals"):
                if pl.get("name") == name:
                    for it in pl:
                        default_args.update(FMT.findall(it.text or ""))
            for pl in locale_root.iter("plurals"):
                if pl.get("name") != name:
                    continue
                for it in pl:
                    got = set(FMT.findall(it.text or ""))
                    if not got <= default_args:
                        problems.append(
                            f"{loc}: plurals '{name}'/{it.get('quantity')} "
                            f"has args {sorted(got)} not in default {sorted(default_args)}"
                        )

        print(
            f"{loc}: {len(strings)} strings, {len(plurals)} plurals "
            f"{'OK' if not any(p.startswith(loc) for p in problems) else 'ISSUES'}"
        )

    if problems:
        print(f"\n{len(problems)} problem(s):")
        for p in problems:
            print("  -", p)
        return 1
    print("\nAll translations consistent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
