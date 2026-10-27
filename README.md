# English ↔ Indonesian Offline Dictionary

An offline-first Android dictionary with a native Android WebView interface and a local SQLite dictionary database.

## Included features

- Offline English → Indonesian and Indonesian → English dictionary lookup.
- A–Z browsing and subject categories.
- Favorites, vocabulary collections, personal entries, and backup/restore.
- A bundled starter vocabulary of 161 curated English terms and informal/slang expressions, searchable in both directions (up to 322 directional entries; duplicate Indonesian headwords are merged). Slang notes explain informal usage and context.
- The starter vocabulary is added to local app storage once per starter-data version, without overwriting existing user entries. It can be edited or deleted from Personal Vocabulary.

## Build

GitHub Actions builds a debug APK when changes are pushed to `main`, or when the workflow is started manually. The APK is uploaded as an Actions artifact.

The packaged dictionary database is stored as `app/src/main/assets/dictionary.db.gz`; Android decompresses it into the app's private storage during setup. The database version is bumped when packaged database loading changes.

## Data policy

The main dictionary compilation is redistributed with its original license and attribution notices in `app/src/main/assets/DICTIONARY-LICENSES.txt`. The added starter vocabulary is a small, manually curated list of short terms, translations, and usage notes. Slang meanings can vary by community, region, and context; the notes are intended as usage guidance, not universal definitions.
