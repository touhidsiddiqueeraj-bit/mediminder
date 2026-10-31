#!/usr/bin/env python3
"""
Builds the offline drug-name dictionary asset for MediMinder.

Source: openFDA NDC Directory (https://open.fda.gov/data/ndc/), U.S. FDA,
public domain. The openFDA API caps `skip` at 25,000 records, so this script
partitions the dataset by `marketing_start_date` ranges (recursive bisection)
and paginates inside every partition.

Output: JSON Lines, one entry per line:
    {"n": "Metformin", "t": "GENERIC", "g": null,      "s": ["500 mg", "850 mg"]}
    {"n": "Glucophage", "t": "BRAND",  "g": "Metformin", "s": ["500 mg"]}

n = display name, t = GENERIC | BRAND, g = linked generic (brands only),
s = most common strengths seen for that name (single-ingredient products only).

Usage:  python3 rebuild_drug_dictionary.py [output.jsonl] [--max-entries 10000]
"""

import datetime as dt
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict

API = "https://api.fda.gov/drug/ndc.json"
CHECKPOINT = "/home/z/drug_dict_checkpoint.json"
MAX_SKIP = 25000          # openFDA hard limit
SAFE_TOTAL = 24000        # stay below it
PAGE = 1000               # openFDA max limit
# Real calendar dates: bisecting raw yyyymmdd integers would create invalid
# dates (e.g. 20000667) that the API silently drops.
MIN_ORD = dt.date(1900, 1, 1).toordinal()
MAX_ORD = dt.date(2032, 12, 31).toordinal()


def fmt_date(ordinal):
    return dt.date.fromordinal(ordinal).strftime("%Y%m%d")


def get(url, retries=5):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "mediminder-dictionary-builder/1.0"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (404,):  # openFDA returns 404 for empty result sets
                return {"meta": {"results": {"total": 0}}, "results": []}
            if attempt == retries - 1:
                raise
            wait = 3 * (attempt + 1)
            print(f"  retry in {wait}s ({e})", file=sys.stderr)
            time.sleep(wait)
        except Exception as e:  # noqa: BLE001 - retry any network error
            if attempt == retries - 1:
                raise
            wait = 3 * (attempt + 1)
            print(f"  retry in {wait}s ({e})", file=sys.stderr)
            time.sleep(wait)


def build_url(search, limit, skip=None):
    # openFDA expects '+' as the space separator inside search values.
    encoded = urllib.parse.quote(search).replace("%20", "+")
    url = f"{API}?search={encoded}&limit={limit}"
    if skip is not None:
        url += f"&skip={skip}"
    return url


def query_total(lo, hi, extra=""):
    search = f"marketing_start_date:[{fmt_date(lo)} TO {fmt_date(hi)}]{extra}"
    data = get(build_url(search, limit=1))
    return data.get("meta", {}).get("results", {}).get("total", 0)


def iter_records(lo, hi, extra=""):
    """Yield every record of one date partition."""
    search = f"marketing_start_date:[{fmt_date(lo)} TO {fmt_date(hi)}]{extra}"
    base = build_url(search, limit=PAGE)
    total = query_total(lo, hi, extra)
    if total == 0:
        return
    pages = (total + PAGE - 1) // PAGE
    for page in range(pages):
        skip = page * PAGE
        if skip >= MAX_SKIP:
            print(f"  !! partition too big ({total}) - increase bisection", file=sys.stderr)
            return
        data = get(f"{base}&skip={skip}")
        yield from data.get("results", [])
        time.sleep(0.12)  # stay well under the 240 req/min limit


def walk(lo, hi, extra=""):
    """Bisect date ranges until every partition fits under MAX_SKIP."""
    total = query_total(lo, hi, extra)
    print(f"[range {fmt_date(lo)}-{fmt_date(hi)}] {total} records", file=sys.stderr)
    if total == 0:
        return
    if total <= SAFE_TOTAL:
        yield from iter_records(lo, hi, extra)
        return
    if lo == hi:  # single day still too big: sub-bisect by dosage form
        print(f"  !! single-day overflow ({total}); splitting by dosage_form", file=sys.stderr)
        for form in OVERFLOW_FORMS:
            yield from walk(lo, hi, f'{extra} AND dosage_form:"{form}"')
        return
    mid = lo + (hi - lo) // 2
    yield from walk(lo, mid, extra)
    yield from walk(mid + 1, hi, extra)


def leaf_partitions(lo, hi, extra=""):
    """Bisect real calendar dates until every partition fits under MAX_SKIP."""
    total = query_total(lo, hi, extra)
    if total == 0:
        return
    if total <= SAFE_TOTAL:
        yield (lo, hi)
        return
    if lo == hi:  # single day still too big: sub-bisect by dosage form
        print(f"  !! single-day overflow ({total}); splitting by dosage_form", file=sys.stderr)
        for form in OVERFLOW_FORMS:
            yield from leaf_partitions(lo, hi, f'{extra} AND dosage_form:"{form}"')
        return
    mid = lo + (hi - lo) // 2
    yield from leaf_partitions(lo, mid, extra)
    yield from leaf_partitions(mid + 1, hi, extra)


def save_checkpoint(done, generics, brands, records):
    state = {
        "done": [list(r) for r in done],
        "records": records,
        "generics": {
            key: {"display": v["display"], "count": v["count"],
                  "strengths": dict(v["strengths"])}
            for key, v in generics.items()
        },
        "brands": {
            key: {"display": v["display"], "count": v["count"],
                  "generic": dict(v["generic"]), "strengths": dict(v["strengths"])}
            for key, v in brands.items()
        },
    }
    tmp = CHECKPOINT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(state, fh)
    os.replace(tmp, CHECKPOINT)


def restore_counters(raw_generics):
    generics = {}
    for key, value in raw_generics.items():
        generics[key] = {
            "display": value["display"],
            "count": value["count"],
            "strengths": Counter(value.get("strengths", {})),
        }
    return generics


def restore_brands(raw_brands):
    brands = {}
    for key, value in raw_brands.items():
        brands[key] = {
            "display": value["display"],
            "count": value["count"],
            "generic": Counter(value.get("generic", {})),
            "strengths": Counter(value.get("strengths", {})),
        }
    return brands


OVERFLOW_FORMS = [
    "TABLET", "CAPSULE", "SOLUTION", "INJECTION", "CREAM", "OINTMENT", "GEL",
    "SYRUP", "SUSPENSION", "POWDER", "SPRAY", "LOTION", "PATCH", "SUPPOSITORY",
    "GRANULE", "FOAM", "EMULSION", "KIT", "TABLET, FILM COATED", "TABLET, EXTENDED RELEASE",
    "CAPSULE, LIQUID FILLED", "SOLUTION, CONCENTRATE", "GAS", "PASTE", "SHAMPOO",
    "LOTION, SUSPENSION", "AEROSOL", "LIQUID", "SUSPENSION, EXTENDED RELEASE",
]

STRENGTH_RX = re.compile(r"^(\d+(?:\.\d+)?)\s*(mg|mcg|g|ml|iu|units?)\b", re.IGNORECASE)


def normalize_strength(raw):
    """'500 mg/1' -> '500 mg'; drop anything not a simple strength."""
    if not raw:
        return None
    value = raw.strip()
    if value.endswith("/1"):
        value = value[:-2].strip()
    match = STRENGTH_RX.match(value)
    if not match:
        return None
    number, unit = match.group(1), match.group(2).lower()
    if unit == "units":
        unit = "units"
    return f"{number} {unit}"


CONNECTORS = {"and", "of", "the", "with", "for", "in", "to", "de"}


def propercase(name):
    words = []
    for index, word in enumerate(name.split()):
        if index > 0 and word.lower() in CONNECTORS:
            words.append(word.lower())
        else:
            words.append(word[:1].upper() + word[1:].lower() if word else word)
    return " ".join(words)



# Fix the display casing of FDA names for the UI ("ATORVASTATIN" or
# "atorvastatin" -> "Atorvastatin"), keeping real acronyms ("HCG") intact.
UPPER_TOKENS = {"hcl": "HCl", "hfa": "HFA", "mdi": "MDI", "xr": "XR", "er": "ER",
                "cr": "CR", "sr": "SR", "dr": "DR", "la": "LA", "xl": "XL",
                "xs": "XS", "sr": "SR"}


def fix_display(name):
    if name.isupper() and len(name) <= 5:
        return name  # acronyms like HCG, DDAVP
    if name.isupper() or name.islower():
        fixed = propercase(name)
        words = [UPPER_TOKENS.get(w.lower(), w) for w in fixed.split()]
        return " ".join(words)
    return name

def clean_display(name):
    if not name:
        return None
    display = name.strip().strip(",;.")
    if len(display) < 4 or len(display) > 60:
        return None
    if not re.search(r"[A-Za-z]{3}", display):
        return None  # numbers/symbols only
    if re.search(r"^\d+$", display):
        return None
    return display


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "drug_names.jsonl"
    max_entries = 10000
    if "--max-entries" in sys.argv:
        max_entries = int(sys.argv[sys.argv.index("--max-entries") + 1])

    generics = {}  # lower -> {display, count, strengths: Counter}
    brands = {}    # lower -> {display, count, generic: Counter, strengths: Counter}
    records = 0

    # 0) Leaf partitions (date-range bisection), computed first so the run
    #    can resume after an interruption.
    partitions = list(leaf_partitions(MIN_ORD, MAX_ORD))
    print(f"[plan] {len(partitions)} partitions: {partitions[:6]} ...", file=sys.stderr)

    done = set()
    if os.path.exists(CHECKPOINT):
        try:
            with open(CHECKPOINT, encoding="utf-8") as fh:
                state = json.load(fh)
            done = {tuple(r) for r in state["done"]}
            generics = restore_counters(state["generics"])
            brands = restore_brands(state["brands"])
            records = state.get("records", 0)
            print(f"[resume] {len(done)} partitions already scanned", file=sys.stderr)
        except Exception as exc:  # noqa: BLE001 - start over on a bad checkpoint
            print(f"[resume] ignoring bad checkpoint ({exc})", file=sys.stderr)
            done, generics, brands, records = set(), {}, {}, 0

    for lo, hi in partitions:
        if (lo, hi) in done:
            continue
        for rec in iter_records(lo, hi):
            records += 1
            absorb(rec, generics, brands)
        done.add((lo, hi))
        save_checkpoint(done, generics, brands, records)
        print(f"[checkpoint] {len(done)}/{len(partitions)} partitions, "
              f"{records} records", file=sys.stderr)

    # 1) Records without a marketing_start_date (small tail).
    if (-1, -1) not in done:
        print("[missing date] sweeping", file=sys.stderr)
        search = "_missing_:marketing_start_date"
        data = get(build_url(search, limit=1))
        total = data.get("meta", {}).get("results", {}).get("total", 0)
        if 0 < total <= SAFE_TOTAL:
            base = build_url(search, limit=PAGE)
            for page in range((total + PAGE - 1) // PAGE):
                if page * PAGE >= MAX_SKIP:
                    break
                data = get(f"{base}&skip={page * PAGE}")
                for rec in data.get("results", []):
                    records += 1
                    absorb(rec, generics, brands)
                time.sleep(0.12)
        done.add((-1, -1))
        save_checkpoint(done, generics, brands, records)

    print(f"scanned {records} records", file=sys.stderr)
    print(f"unique generics={len(generics)} brands={len(brands)}", file=sys.stderr)

    # ---- Build ranked entry list -----------------------------------------
    entries = []

    def entry_from_generic(lower, info):
        return {
            "n": fix_display(info["display"]), "t": "GENERIC", "g": None,
            "s": [s for s, _ in info["strengths"].most_common(5)],
        }

    def entry_from_brand(lower, info):
        generic = info["generic"].most_common(1)[0][0] if info["generic"] else None
        return {
            "n": fix_display(info["display"]), "t": "BRAND", "g": generic,
            "s": [s for s, _ in info["strengths"].most_common(5)],
        }

    generic_items = sorted(generics.items(), key=lambda kv: -kv[1]["count"])
    brand_items = sorted(brands.items(), key=lambda kv: -kv[1]["count"])
    keep_generic = [kv for kv in generic_items if kv[1]["count"] >= 2]
    keep_brand = [
        kv for kv in brand_items
        if kv[1]["count"] >= 2 and kv[1]["generic"]  # brands need a linked generic
    ]
    # Also restore brands' generic Counter after a checkpoint restore.
    for lower, info in brands.items():
        if not isinstance(info["generic"], Counter):
            info["generic"] = Counter(info["generic"])
        if not isinstance(info["strengths"], Counter):
            info["strengths"] = Counter(info["strengths"])
    print(f"after frequency filter: generics={len(keep_generic)} brands={len(keep_brand)}",
          file=sys.stderr)

    for lower, info in keep_generic:
        entries.append((info["count"], entry_from_generic(lower, info)))
    for lower, info in keep_brand:
        entries.append((info["count"], entry_from_brand(lower, info)))

    entries.sort(key=lambda pair: -pair[0])
    final = [payload for _, payload in entries[:max_entries]]

    with open(out_path, "w", encoding="utf-8") as fh:
        for payload in final:
            fh.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")

    print(f"wrote {len(final)} entries to {out_path}", file=sys.stderr)


def absorb(rec, generics, brands):
    product_type = rec.get("product_type") or ""
    if "ANIMAL" in product_type.upper():
        return
    generic_raw = (rec.get("generic_name") or "").strip()
    brand_raw = (rec.get("brand_name") or "").strip()
    ingredients = rec.get("active_ingredients") or []
    single_strength = None
    if len(ingredients) == 1:
        single_strength = normalize_strength(ingredients[0].get("strength"))

    generic_display = clean_display(generic_raw)
    if generic_display:
        lower = generic_raw.lower()
        info = generics.setdefault(
            lower, {"display": generic_display, "count": 0, "strengths": Counter()})
        info["count"] += 1
        # Prefer the most "word-shaped" casing as display (FDA mixes cases).
        if has_mixed_case(generic_raw) and not has_mixed_case(info["display"]):
            info["display"] = generic_raw
        if single_strength:
            info["strengths"][single_strength] += 1

    brand_display = clean_display(brand_raw)
    if brand_display and generic_display and brand_raw.lower() != generic_raw.lower():
        lower = brand_raw.lower()
        info = brands.setdefault(
            lower,
            {"display": brand_display, "count": 0,
             "generic": Counter(), "strengths": Counter()})
        info["count"] += 1
        info["generic"][generic_display] += 1
        if single_strength:
            info["strengths"][single_strength] += 1


def has_mixed_case(text):
    return any(c.islower() for c in text)


if __name__ == "__main__":
    main()
