from pathlib import Path
from zipfile import ZipFile
import gzip
import sqlite3
import struct

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
print(f"\nDatabase created: {DB_PATH}")
print(f"Database size: {DB_PATH.stat().st_size:,} bytes")
print(f"License notices created: {NOTICE_PATH}")
