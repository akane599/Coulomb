# ADR-0062: Export, import and "Delete all data"

- Status: accepted (amends ADR-0040's "the export writes kinds by name" for the JSON bundle, and
  reads SPEC 8.1's "export reads through the same DAOs" as "through the database module")
- Date: 2026-11-21
- Phase: P6 (SPEC 14.5, 17, 18)
- Builds on: ADR-0005 (`batterystats -c`, never `--checkin`), ADR-0015 and ADR-0051 (the schema and
  the stored history), ADR-0027 (second opinion: taken for the bundle's format and what import does)

## Context

SPEC 14.5 asks for a CSV per table, a JSON bundle, a raw bundle for Battery Historian, an import of
Coulomb's own JSON bundle "for moving to a new phone", and "Delete all data". The bundle is a data
format that outlives the build that wrote it, and import decides whether numbers of two phones can
meet in one table, so both went to an independent second opinion before any code (ADR-0027). Its two
main recommendations are taken.

## Decisions

### The JSON bundle

1. **A zip of line-delimited JSON, one file per table.** `manifest.json` first, `README.txt`,
   `tables/<table>.ndjson`, `settings.json`, and `end.json` last. A bundle without `end.json` did not
   finish and is refused. One row per line keeps memory bounded on both sides; a year of data is
   tens of megabytes of JSON, which a single document would hold in memory at once.
2. **Rows in stored form.** Each line is a JSON array in the manifest's column order: integers as
   integers (64-bit; the README warns tools that read doubles), reals in their shortest round-trip
   form, NULL as `null`, a BLOB as base64 text. Codes stay codes, and the manifest carries each
   coded column's list of names. This amends ADR-0040, which had the export write names: names go
   to the CSV, which people read; the bundle is restored by Room's own migrations, and a name codec
   per schema version would be a second migration chain that can mislabel rows silently.
3. **The manifest** holds `format` ("coulomb-bundle"), `formatVersion` (major 1, minor 0), a random
   `bundleId`, the export time and zone, the app's version, the schema's version and identity hash,
   the device (model, manufacturer, build fingerprint, SDK), and per table its columns (name,
   SQLite type, not-null), primary key and row order.
4. **One generic reader in `:core:database`.** It walks `sqlite_master`, so a table cannot be
   forgotten; a test holds the list of tables written against the schema and a named exclusion
   list. The export reads a copy made with `VACUUM INTO` in the cache directory, so the bundle is
   one consistent state, the collector's writer is not held, and the copy is gone when the export
   ends (one left by a killed process is removed by the next).
5. **The stored history goes as it is stored** (`history_chunk` with its blobs), so the bundle
   restores what the phone has; the CSV export decodes it into events instead.
6. **`snapshot.blobPath` is written as null.** The raw snapshot files are not in the bundle.

### The CSV export

7. One zip: `<table>.csv` per table and a README. UTF-8 with a byte-order mark, CRLF, RFC 4180
   quoting, comma and decimal point whatever the phone's language. NULL is an empty field and an
   empty string is `""`. Booleans are 0 and 1. Coded columns are written by name. Every wall-clock
   column in epoch milliseconds gets a second column `<name>Utc`. Text that starts with `=`, `+`,
   `-`, `@`, a tab or a carriage return gets a leading apostrophe, because tags and labels come from
   other apps and a spreadsheet would run them as formulas. The history is written as decoded
   events, not as blobs. A file is split at 1,000,000 rows.

### The raw bundle

8. A zip of what the worker reads, unparsed: `dumpsys batterystats -c` and
   `dumpsys batterystats --history`, both on SPEC 19.1's list, with a README naming the build.
   Whether Battery Historian takes the two files without a full bug report is not verified
   (`docs/questions.md`).

### Import

9. **An imported bundle becomes an archive, never rows of this phone's tables.** It is written to
   its own database under `noBackupFilesDir/archives/<bundleId>/` and shown as "History from
   <model>", with its dates and "imported from a file" on every screen that reads it. Reasons, each
   of which would put a wrong number on screen as this phone's:
   - capacity, health and cycle sums read "the latest" and "all" rows; insight baselines reach back
     two weeks by wall time; the collector chains each window from the last snapshot;
   - UIDs belong to one phone: 10234 is another app on the next;
   - ids are inside strings (`session:42`) and behaviour follows id order;
   - an old phone's action-log row whose value happens to match would offer a revert that writes
     the old phone's value here.
   An archive is more accurate, costs the live queries nothing and is removed by deleting a folder
   (SPEC 0.3 rule 5). Nothing from an archive reaches Now, a widget, a notification, an insight or a
   "usual" baseline. Its action log is history only: no revert and no command.
10. **Older bundles are replayed, not translated.** The importer builds the archive from the
    bundle's own schema version (the exported schema files ship as assets from the first version
    that can export), loads the rows by bound parameters, and opens it through
    `CoulombDatabase.configured`, which migrates and validates it like any database. A newer schema
    or an unknown format major is refused with "written by a newer Coulomb".
11. **The bundle is treated as hostile.** Entries are read by their expected names only. An entry
    this build does not know is damage, unless the manifest's minor version is ahead of this
    build's: a later minor may add entries, and they are passed over. Sizes are counted while
    inflating, not taken from the zip's header: the file at most 1 GiB, what it inflates to at most
    4 GiB, a small entry 1 MB, a row 8 MB; and the load stops while the phone still has 256 MB
    free, because the collector writes to the same storage. The manifest's tables and columns must
    equal the app's schema for that version, its lists of codes must agree with this build's as far
    as both go, and no SQL is built from a bundle's text. The manifest, the settings and the
    closing entry must be strict JSON no deeper than eight levels before a recursive parser sees
    them; a row is read token by token as one flat array with nothing after it. Every value is
    checked for length and type and inserted with ABORT; counts must equal `end.json`.
    After the load: `foreign_key_check`, `integrity_check`, every `bootSeq` present in `boot`, and
    Room's own validation when the file is opened. Then the values a reader would throw on
    instead of showing: a position-coded column holds one of its codes, an enum stored by name one
    of its names, a column with a code of its own (privilege mode, breakdown, kernel coverage) a
    known one; an id that points at a lookup row without a foreign key finds it; every kernel log
    event reads back; every history chunk decodes whole, to kinds and changes this build knows, in
    boots that are there. A test finds the entity classes and holds every enum column to one of
    these lists. Free text that only its own parser reads (a kernel read's gaps and causes, an
    insight's evidence) is not checked: whatever reads an archive takes a failure there as an
    unreadable row.
    Any failure rejects the whole bundle: a skipped row would read as a real gap. The reason is
    told apart: not a bundle, newer, too old, unfinished (also a file cut short), damaged, too
    large, already here, or failed (no room, or the file could not be read), which is not the
    bundle's fault. The bundle's id must be a UUID before it names a folder, and the archive
    appears by one directory rename. An archive's `window_stat` is rebuilt without a rowid, the
    stored form. Every imported boot is marked `imported`, so it is never the base of this phone's
    numbering. One import, export or deletion runs at a time, and what a killed one left is
    removed when Coulomb next starts.
12. **Settings travel as named keys and only neutral ones are applied**, and only when the user
    asks: show system apps, scale to measured, the charge target, the temperature threshold (a file
    sets the threshold and never switches the alarm off), the digest time, theme, units and the
    screen-on sampling interval. The file is read as one flat object; anything nested is not a
    settings file. The rest are written for the
    record and never applied: the Action Center, the automation API, the charge alarm, the digest
    switch, the component breakdown, Lite mode and the longer retention each stand for a grant, a
    permission or a consent given on the old phone. `onboarded` and the developer console are not
    written at all.
13. **No background work.** Export and import run while their screen is open, with progress; no
    wakelock, alarm or job (SPEC 7.4).

### Delete all data

14. **It stops the collector, clears every table, deletes the snapshot files, the archives and any
    staging files, then restarts the process.** The collector holds this boot, the calibration and
    the capacity in memory, so a restart is the one way nothing of the old data stays in use.
    Settings and grants stay (SPEC 14.5). If an action is still in force it refuses first and names
    the Action Center: deleting the log would leave a write on the phone that nothing can revert
    (SPEC 1.2).
    Amended 2026-11-22 (audit CONC-001): the last check and the erase hold the Action Center's
    lock, and once the erase has run no action starts until the restart, so an automation call
    cannot log a change between the check and the erase. An erase that fails opens it again.
    Amended 2026-11-23 (audit CONC-010): the collector is held before that lock is taken, never
    inside it. The collector's drift check takes the same lock after each heavy snapshot, so an
    erase confirmed during one waited for the collector while the collector waited for the lock,
    for ever. A confirmation that is stale is refused before anything stops; if the check under
    the lock refuses, the collector is started again, as after a failed erase.
    Amended 2026-11-23 (audit DATA-006): an Automation API call's own row goes through the Action
    Center too, behind a gate the erase holds. The row is written before the tables are cleared or
    not at all, and a call that finds the log closed does nothing and answers "failed".
    Amended 2026-11-22 (audit DATA-008): a restored install's first start stays pending until the
    reset of §15 is stored, so a start cut short runs it again.

### Backup

15. **Auto Backup keeps the settings, without the flags that open something.** A restored phone
    starts with `onboarded`, the Action Center and the automation API off, whatever the old phone
    had: each stands for a grant or a consent given on that phone.
    Amended 2026-11-23 (audit DATA-002): while that reset could not be stored, the settings read
    as it would leave them, so a restored consent opens nothing in that process either; the next
    settings write stores the reset with itself and the first start then counts as done. A start
    with no mark at all is a first start even when the database is there (it was "an install
    from before the marks" until now): a first start whose pending mark could not be written
    must not pass for a finished one. Every released build writes the marks, so only a
    development install from before 2026-11-21 is reset once more. Owner delegated the choice.

## Consequences

- The export screen says that a bundle holds the names of installed apps, and that it is not
  signed: the checks find damage, not editing.
- Archives are outside SPEC 7.7's 150 MB target; each shows its size and a delete button.
- A continuous view across the old and the new phone is not built. It can come later in the read
  layer without changing the format (`BACKLOG.md`).
- The round trip is tested against the current schema. When the schema next changes, a synthetic
  bundle of the version before it is kept as a golden under "every migration tested" (SPEC 8).
- The exported schema files ship in the APK as assets (about 100 kB a version before compression).

## Reviewed

One independent review of the engine (2026-11-21) found three things above minor, all in the
import of a file built to do harm, and they are fixed in §11: a manifest or row of nested brackets
ended the process with a stack overflow; only the file's size was bounded, not what it inflates
to; and values Room's converters and Coulomb's own decoders throw on were accepted. Open, minor:
the dump reads a table through one cursor, which re-steps from the first row at each window and
is slow on a table of millions of rows (estimated, not measured); `device_profile` rows get new
rowids, and one query of the collector orders by rowid, which no archive is read by.

## Not verified yet

- Battery Historian with the raw bundle's two files.
- A bundle round-tripped on the phone itself (a phone test; it waits with the others, ADR-0053).
  On the build server the round trip is tested with a synthetic database, and `CaptureBundleTest`
  ran it on the database pulled from the phone on 2026-11-21: 39 tables and 230,561 rows went out
  as a 3.3 MB bundle and came back table for table, past every check of §11.

## Reverting

The feature is `:core:database`'s `transfer` package, `:core:data`'s export and archive classes and
Settings' Data section. Removing them leaves the schema untouched; archives are folders.
