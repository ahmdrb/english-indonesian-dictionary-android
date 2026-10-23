from pathlib import Path
from zipfile import ZipFile
import gzip
import sqlite3
import struct
import re

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "dictionary-source"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
DB_PATH = ASSETS / "dictionary.db"
NOTICE_PATH = ASSETS / "DICTIONARY-LICENSES.txt"

sources = [
    ("en-id.zip", "en-id", "en-id", "en-id"),
    ("id-en.zip", "id-en", "id-en", "id-en"),
]

def read_index(raw):
    records = []
    pos = 0
    while pos < len(raw):
        end = raw.index(b"\0", pos)
        word = raw[pos:end].decode("utf-8", errors="replace")
        offset, size = struct.unpack(">II", raw[end + 1:end + 9])
        records.append((word, offset, size))
        pos = end + 9
    return records

if DB_PATH.exists():
    DB_PATH.unlink()

db = sqlite3.connect(DB_PATH)
db.execute("PRAGMA journal_mode=OFF")
db.execute("PRAGMA synchronous=OFF")
db.execute("PRAGMA temp_store=MEMORY")

db.executescript("""
CREATE TABLE entries (
    id INTEGER PRIMARY KEY,
    direction TEXT NOT NULL,
    word TEXT NOT NULL,
    definition TEXT NOT NULL
);

CREATE TABLE terms (
    id INTEGER PRIMARY KEY,
    direction TEXT NOT NULL,
    term TEXT NOT NULL,
    normalized TEXT NOT NULL,
    entry_id INTEGER NOT NULL,
    is_alias INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(entry_id) REFERENCES entries(id)
);

CREATE INDEX terms_lookup
ON terms(direction, normalized, id);

CREATE INDEX terms_entry
ON terms(entry_id);
""")

notices = [
    "English ↔ Indonesian offline dictionary data",
    "",
    "Dictionary data is redistributed with its original license and attribution notices.",
    "The dictionary compilations are licensed under Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0), with additional source-specific terms.",
    "License: https://creativecommons.org/licenses/by-sa/4.0/",
    "Source project: https://github.com/DanielGregorini/koreader-dicts",
    "",
]

for zip_name, folder, stem, direction in sources:
    print(f"\nProcessing {stem} ...")
    with ZipFile(SOURCE / zip_name) as archive:
        index = read_index(archive.read(f"{folder}/{stem}.idx"))
        data = gzip.decompress(archive.read(f"{folder}/{stem}.dict.dz"))
        synonyms = archive.read(f"{folder}/{stem}.syn")
        attribution = archive.read(f"{folder}/ATTRIBUTION").decode(
            "utf-8", errors="replace"
        )
        license_text = archive.read(f"{folder}/LICENSE").decode(
            "utf-8", errors="replace"
        )
        readme = archive.read(f"{folder}/README.txt").decode(
            "utf-8", errors="replace"
        )

    notices.extend([
        "",
        "=" * 68,
        f"{direction.upper()} ORIGINAL README",
        "=" * 68,
        readme,
        "",
        f"{direction.upper()} ORIGINAL ATTRIBUTION",
        attribution,
        "",
        f"{direction.upper()} ORIGINAL LICENSE",
        license_text,
        "",
    ])

    entry_ids = []
    for start in range(0, len(index), 1000):
        batch = index[start:start + 1000]
        for word, offset, size in batch:
            definition = data[offset:offset + size].decode(
                "utf-8", errors="replace"
            )
            cursor = db.execute(
                "INSERT INTO entries(direction, word, definition) VALUES (?, ?, ?)",
                (direction, word, definition),
            )
            entry_ids.append(cursor.lastrowid)

    terms_added = 0
    for start in range(0, len(index), 1000):
        batch = index[start:start + 1000]
        rows = []
        for offset, (word, _, _) in enumerate(batch, start=start):
            rows.append((
                direction, word, word.casefold(), entry_ids[offset], 0
            ))
        db.executemany(
            """INSERT INTO terms
               (direction, term, normalized, entry_id, is_alias)
               VALUES (?, ?, ?, ?, ?)""",
            rows,
        )
        terms_added += len(rows)

    pos = 0
    alias_rows = []
    aliases_seen = 0

    while pos < len(synonyms):
        end = synonyms.index(b"\0", pos)
        alias = synonyms[pos:end].decode("utf-8", errors="replace")
        target = struct.unpack(">I", synonyms[end + 1:end + 5])[0]
        pos = end + 5

        if target >= len(entry_ids):
            raise ValueError(
                f"Invalid synonym target {target} in {stem}"
            )

        alias_rows.append((
            direction, alias, alias.casefold(), entry_ids[target], 1
        ))
        aliases_seen += 1

        if len(alias_rows) >= 2000:
            db.executemany(
                """INSERT INTO terms
                   (direction, term, normalized, entry_id, is_alias)
                   VALUES (?, ?, ?, ?, ?)""",
                alias_rows,
            )
            alias_rows.clear()

    if alias_rows:
        db.executemany(
            """INSERT INTO terms
               (direction, term, normalized, entry_id, is_alias)
               VALUES (?, ?, ?, ?, ?)""",
            alias_rows,
        )

    db.commit()
    print(f"Main entries: {len(index):,}")
    print(f"Alternate forms processed: {aliases_seen:,}")
    print(f"Total searchable terms added: {terms_added + aliases_seen:,}")


# Build a reproducible subject taxonomy from explicit dictionary labels.
# Category assignment is deliberately conservative: it uses recognized
# parenthesized subject labels, not arbitrary keyword guesses.
OFFLINE_CATEGORY_TAXONOMY = [
    ("science", None, "Science", None),
    ("physics", "science", "Physics", ["physics"]),
    ("biology", "science", "Biology", ["biology"]),
    ("chemistry", "science", "Chemistry", ["chemistry"]),
    ("astronomy", "science", "Astronomy", ["astronomy"]),
    ("mathematics", "science", "Mathematics", ["mathematics", "math"]),
    ("geology", "science", "Geology & Earth Science", ["geology"]),
    ("ecology", "science", "Ecology", ["ecology"]),

    ("technology", None, "Technology & Computing", None),
    ("computer-science", "technology", "Computer Science", [
        "computer science", "computing"
    ]),
    ("internet", "technology", "Internet & Networking", [
        "internet", "networking"
    ]),
    ("electronics", "technology", "Electronics", ["electronics"]),

    ("health", None, "Health & Medicine", None),
    ("medicine", "health", "Medicine", ["medicine", "medical"]),
    ("anatomy", "health", "Anatomy", ["anatomy"]),

    ("society", None, "Society & Public Affairs", None),
    ("law", "society", "Law", ["law", "legal"]),
    ("economics", "society", "Economics", ["economics"]),
    ("finance", "society", "Finance", ["finance"]),
    ("politics", "society", "Politics", ["politics"]),
    ("military", "society", "Military", ["military"]),

    ("sports", None, "Sports", None),
    ("baseball", "sports", "Baseball", ["baseball"]),
    ("football", "sports", "Football", ["football"]),
    ("basketball", "sports", "Basketball", ["basketball"]),

    ("arts", None, "Arts & Design", None),
    ("music", "arts", "Music", ["music"]),
    ("architecture", "arts", "Architecture", ["architecture"]),

    ("language", None, "Language & Linguistics", None),
    ("linguistics", "language", "Linguistics", ["linguistics"]),

    ("humanities", None, "Humanities & Ideas", None),
    ("psychology", "humanities", "Psychology", ["psychology"]),
    ("philosophy", "humanities", "Philosophy", ["philosophy"]),
    ("geography", "humanities", "Geography", ["geography"]),
]

db.executescript("""
CREATE TABLE categories (
    id INTEGER PRIMARY KEY,
    slug TEXT NOT NULL UNIQUE,
    parent_id INTEGER,
    name TEXT NOT NULL,
    FOREIGN KEY(parent_id) REFERENCES categories(id)
);
CREATE INDEX categories_parent ON categories(parent_id, name);

CREATE TABLE category_entries (
    category_id INTEGER NOT NULL,
    entry_id INTEGER NOT NULL,
    PRIMARY KEY(category_id, entry_id),
    FOREIGN KEY(category_id) REFERENCES categories(id),
    FOREIGN KEY(entry_id) REFERENCES entries(id)
);
CREATE INDEX category_entries_entry ON category_entries(entry_id, category_id);
""")

category_ids = {}
for slug, parent_slug, name, _labels in OFFLINE_CATEGORY_TAXONOMY:
    parent_id = category_ids.get(parent_slug) if parent_slug else None
    cursor = db.execute(
        "INSERT INTO categories(slug, parent_id, name) VALUES (?, ?, ?)",
        (slug, parent_id, name),
    )
    category_ids[slug] = cursor.lastrowid

label_to_category = {}
for slug, _parent, _name, labels in OFFLINE_CATEGORY_TAXONOMY:
    if labels:
        for label in labels:
            label_to_category[label.casefold()] = slug

# Link both the matching subject category and its parent category.
category_links = set()
subject_pattern = re.compile(r"\(([^()]*)\)")

for entry_id, definition in db.execute(
    "SELECT id, definition FROM entries"
):
    found_slugs = set()
    for match in subject_pattern.finditer(definition):
        label = match.group(1).strip().casefold()
        slug = label_to_category.get(label)
        if slug:
            found_slugs.add(slug)

    for slug in found_slugs:
        category_links.add((category_ids[slug], entry_id))
        parent_slug = next(
            parent for child, parent, _name, _labels
            in OFFLINE_CATEGORY_TAXONOMY if child == slug
        )
        if parent_slug:
            category_links.add((category_ids[parent_slug], entry_id))

db.executemany(
    "INSERT OR IGNORE INTO category_entries(category_id, entry_id) VALUES (?, ?)",
    sorted(category_links),
)
db.commit()

print(f"\nCategories created: {len(category_ids):,}")
print(f"Category-entry links created: {len(category_links):,}")
for slug, _parent, name, _labels in OFFLINE_CATEGORY_TAXONOMY:
    count = db.execute(
        """SELECT COUNT(*) FROM category_entries ce
           JOIN categories c ON c.id = ce.category_id
           WHERE c.slug = ?""",
        (slug,),
    ).fetchone()[0]
    print(f"  {name}: {count:,} entries")


NOTICE_PATH.write_text("\n".join(notices), encoding="utf-8")
db.execute("ANALYZE")
db.commit()

for direction, in db.execute(
    "SELECT DISTINCT direction FROM entries ORDER BY direction"
):
    entries_count = db.execute(
        "SELECT COUNT(*) FROM entries WHERE direction=?", (direction,)
    ).fetchone()[0]
    terms_count = db.execute(
        "SELECT COUNT(*) FROM terms WHERE direction=?", (direction,)
    ).fetchone()[0]
    print(f"\n{direction}: {entries_count:,} entries, {terms_count:,} searchable terms")

db.close()

# Package the generated database in the format consumed by Android.
GZIP_PATH = ASSETS / "dictionary.db.gz"
GZIP_TEMP = ASSETS / "dictionary.db.gz.tmp"
with DB_PATH.open("rb") as raw, gzip.open(GZIP_TEMP, "wb", compresslevel=6) as packed:
    import shutil
    shutil.copyfileobj(raw, packed)

GZIP_TEMP.replace(GZIP_PATH)
DB_PATH.unlink()

print(f"\nCompressed database created: {GZIP_PATH}")
print(f"Compressed database size: {GZIP_PATH.stat().st_size:,} bytes")
print(f"License notices created: {NOTICE_PATH}")
