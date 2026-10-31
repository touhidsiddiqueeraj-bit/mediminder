# Offline drug-name dictionary

`app/src/main/assets/drug_names.jsonl` is the packaged drug-name dictionary
used by the label scanner to correct OCR'd medicine names **fully offline**.

- One JSON object per line (JSON Lines), UTF-8:
  `{"n":"Metformin","t":"GENERIC","g":null,"s":["500 mg","850 mg"]}`
  - `n` — display name
  - `t` — `GENERIC` or `BRAND`
  - `g` — for brands, the linked generic ingredient
  - `s` — common strengths seen for that name (single-ingredient products only)
- ~10,000 entries, ~725 KB. Copied into the `drug_names` Room table on first
  use (`DrugNameSeeder`), then held in memory by `LocalDrugNameIndex` with a
  prefix + trigram index for fuzzy matching.

## Rebuilding from openFDA

The data source is the **openFDA NDC Directory**
(https://open.fda.gov/data/ndc/), published by the U.S. FDA in the public
domain (`CC0`-style; see https://open.fda.gov/license/). It lists every
drug product marketed in the US with its generic name, brand name and
active-ingredient strengths.

```bash
python3 tools/rebuild_drug_dictionary.py app/src/main/assets/drug_names.jsonl
```

- Downloads all ~138,000 NDC product records through the public openFDA API.
- The API caps pagination at `skip <= 25,000`, so the script bisects the
  `marketing_start_date` range until every partition fits, and checkpoints
  progress (resumable) into `/tmp`.
- Aggregates generic names, brand → generic links and the most common
  strengths per name (single-ingredient products only, strengths normalized
  to `500 mg` style), then writes the top 10,000 entries by market frequency.
- Runtime: ~4–6 minutes, no API key needed.

Notes and limits:

- The NDC Directory lists *currently listed US products*, so discontinued
  brands (e.g. plain "Glucophage") may be absent even though their generics
  are present. Every current brand (Lipitor, Advil, …) is included.
- Names are US-market spellings ("Acetaminophen", not "Paracetamol" — the
  dictionary keeps both when present in the source).
- The dictionary is a matching aid only: the user always confirms the name
  before anything is saved.
