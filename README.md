# Kakuro Generator Java

A standalone Java 21 command-line application that generates valid, proven-unique
Kakuro puzzles, stores them in SQLite and exports printable A4 or no-bleed KDP PDF booklets.

This is a new implementation, not a Python port. It uses its own algorithms,
difficulty scale, database schema and versioned fingerprints.

## Build

Install JDK 21 (recommended) and set JAVA_HOME. Maven Wrapper pins Maven 3.9.16.
Internet access is required for uncached build dependencies.

Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --help
```

Linux/macOS:

```sh
sh ./mvnw clean verify
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --help
```

Installed Maven also works: `mvn clean verify`. Compilation targets Java 21.
The packaged JAR includes all runtime dependencies; Maven is not needed to run it.
Do not run the thin `original-...` JAR generated during packaging.

## Generate puzzles

Create the output directory first:

```powershell
New-Item -ItemType Directory -Force output/examples
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --easy 1 --easy-size 8 --medium 1 --medium-size 9 --hard 1 --hard-size 10 --evil 1 --evil-size 10 --seed 20261012 --workers 2 --max-attempts 5000 --db output/examples/puzzles.db --outfile output/examples/booklet.pdf
```

Each successful slot prints its score, search nodes, candidate count, elapsed
generation time, child seed, database ID and fingerprint. Failed slots are
explicitly reported and never replaced with a different difficulty.

The latest digit-diversity review is in `output/solution-review`: 15 independently
verified puzzles, a 30-page booklet and before/after measurements. See
[solution quality](docs/SOLUTION_QUALITY.md), including performance regressions.
The earlier large/rectangular review is in `output/dimensions-review`: ten puzzles,
corresponding solutions, SQLite database, contact sheet and benchmark CSV. See
[large-board review](docs/LARGE_BOARDS.md) for measured results and reproduction commands.
The earlier 12-puzzle [geometry review](docs/GEOMETRY_REVIEW.md) remains available.
The earlier [local verification](docs/LOCAL_VERIFICATION.md) describes the old generator.

## Resumable SQLite puzzle-bank production

`bank` is a separate, PDF-free production command. It fills **total stored targets**,
not an additional count on every invocation. Existing records count toward targets.
Square and rectangular production dimensions remain 7–20 per axis. All four existing
difficulty bands, geometry/digit-quality checks and exhaustive uniqueness proofs remain
in force. Seeds and per-slot candidate budgets are stored in additive SQLite metadata;
the puzzle schema and canonical hashes are unchanged.

These are Windows PowerShell **one-line commands**, run from the project directory.
The production command below requests 50 puzzles for each of Easy, Medium, Hard and
Evil at 8×8, 10×10, 12×12 and 15×15: **800 total**. It is provided for local production;
the full bank has not been generated as part of implementation verification.

Build:

```powershell
mvn clean verify
```

Start a new bank (parent directories are created automatically):

```powershell
java -Xmx2g -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar bank --db output/bank/puzzles.db --sizes 8,10,12,15 --each 50 --seed 20261014 --workers 4 --max-attempts 5000 --max-slots 2000
```

Safe resume, including after Ctrl+C or a slot-limit exit:

```powershell
java -Xmx2g -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar bank --db output/bank/puzzles.db --sizes 8,10,12,15 --each 50 --workers 4 --max-slots 2000
```

Repeating either production command is safe: filled targets are skipped. Resume
inherits the stored seed and candidate budget; changing either is rejected. Worker
count and invocation slot limits may change. Targets may be increased, decreased or
restricted to selected buckets without deleting records. For independently configured
targets, use repeatable `--target ROWSxCOLS:DIFFICULTY:TOTAL` instead of `--sizes/--each`:

```powershell
java -Xmx2g -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar bank --db output/bank/puzzles.db --target 8x8:EASY:50 --target 10x15:HARD:20 --workers 4 --max-slots 100
```

Inspect totals and independently re-solve saved clues with the production solver:

```powershell
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar bank-report --db output/bank/puzzles.db
java -Xmx2g -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --db output/bank/puzzles.db --verify-db
```

Optional logical-v1 quality report (CSV on stdout, summary on stderr):

```powershell
java -Xmx2g -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar bank-report --db output/bank/puzzles.db --logical --analysis-budget 5000000 > output/bank/logical-v1.csv
```

The logical report never changes labels, acceptance rules or puzzle records. Its
technique counts, forced moves, eliminations and dependency depth are measurements
of supported rules, **not calibrated human difficulty**. STALLED is not proof that
guessing is necessary; exhaustion is reported separately. See
[difficulty calibration](docs/DIFFICULTY_CALIBRATION.md).

Production processes Easy → Medium → Hard → Evil, then dimensions in ascending
order within each difficulty. Each bucket has its own versioned seed stream and
diversity history. Committed failures and duplicates advance that stream; interrupted
uncommitted work replays. Completion order never selects results. For a fixed initial
database and bucket targets, changing workers or splitting a run preserves the
committed puzzle sequence. This bank stream is intentionally separate from the
unchanged legacy batch `--seed` schedule.

Progress includes accepted records, candidate attempts, stage counters, elapsed time,
throughput and proof status. A heartbeat appears every 30 seconds while work is pending.
Lifetime committed-slot/rejection totals are retained in SQLite. Stage counts overlap;
speculative work cancelled after a target is met and interrupted work are not durable
attempt counts. Exit 0 means all requested targets are met; 1 means an incomplete
bounded run or operational failure; 2 means invalid arguments; 130 means cancellation.
`--max-slots` bounds an invocation and `--max-attempts` bounds each slot. A target is
not a promise of success within that budget, especially for large Easy/Evil banks.

Use one writer per database on a local filesystem. Bank producers acquire an OS
lock; do not simultaneously write with legacy generation commands, external SQLite
tools or another application. The lock file may remain after exit; the OS lock is
released, so do not delete it to bypass a running producer. Keep database, WAL and SHM
files together while open. For a file-copy backup, close all database connections
(including readers) first or use SQLite's online backup API; never copy only the
main database file while WAL connections are active. Run
verification after production and before using a bank. Existing records are counted
but are not silently revalidated or relabeled during target planning.

See [production audit and contracts](docs/BANK_PRODUCTION.md) for durability,
reproducibility, statistics and operational limits.

## Opt-in wide-board experiments

Only `probe` enables **15 rows × 30, 40 or 50 columns**. It requires explicit seed,
candidate and time limits; no probe or stress benchmark runs automatically. A child
JVM is capped at 512 MiB and forcibly terminated when its deadline expires. Timeouts
never establish uniqueness. Use a fresh output directory for each invocation:

```powershell
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar probe --cols 30 --difficulty MEDIUM --seed 20261014 --max-attempts 5 --seconds 30 --outdir output/probe-15x30
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar probe --cols 40 --difficulty MEDIUM --seed 20261014 --max-attempts 5 --seconds 30 --outdir output/probe-15x40
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar probe --cols 50 --difficulty MEDIUM --seed 20261014 --max-attempts 5 --seconds 30 --outdir output/probe-15x50
```

Limits: 1–1000 candidates and 1–300 seconds, including child startup, generation,
uniqueness rechecking and SQLite writing. Forced termination may require up to five
additional seconds for process cleanup. Success produces `puzzles.db`; failure or
timeout leaves a diagnostic log and possibly `incomplete.db`, which is **not accepted
output**. Complete attempt statistics are available on normal exit; a forcibly killed
worker cannot provide reliable partial counters. Wide-board success rates and output
quality are unbenchmarked and no threshold is relaxed to obtain a result. PDF output
is not part of the experimental command.

## Options

| Option | Behavior |
| --- | --- |
| --easy, --medium, --hard, --evil N | Counts; no counts defaults to one Medium |
| --size N | Global square size, 7–20; default 9 |
| --easy-size, --medium-size, --hard-size, --evil-size N | Per-level overrides |
| --rows R --cols C | Independent dimensions, each 7–20; both required |
| --seed N | Signed 64-bit master seed; an omitted seed is chosen and printed |
| --workers N | CPU worker threads, 1–256; default 1 |
| --max-attempts N | Candidate budget per slot; default 500 |
| --db PATH | Optional SQLite persistence |
| --outfile PATH | A4 PDF; default kakuro_puzzles.pdf |
| --no-pdf | Suppress PDF |
| --page-size a4\|kdp-8.25\|kdp-8.50 | Default a4; KDP presets keep fixed portrait trim dimensions |
| --margin-inner INCHES | KDP total inside margin before optional extra gutter; default automatic |
| --margin-outer, --margin-top, --margin-bottom INCHES | KDP margins; each defaults to 0.5 inches |
| --gutter INCHES | Extra KDP binding space added to inside margin; default 0 |
| --solutions-per-page N | KDP maximum answer keys per page: 1, 2, 3, 4 or 6; default 6 |
| --solution-cell-size POINTS | KDP maximum answer-cell size, 14–72 points; default 20 |
| --export-ids 1,2,3 | Retrieve/export saved IDs in requested order; requires --db |
| --verify-db | Re-solve all saved puzzles from clues and check stored data |
| --analyze-db | Report independent logical deduction profiles for saved puzzles; requires --db |
| --analysis-budget N | Work allowance per logical analysis; default 5000000; requires --analyze-db |
| --help, --version | Usage/version |

Counts must be nonnegative. Specifying zero counts does not trigger the default
Medium puzzle. Batches run in Easy, Medium, Hard, Evil schedule order. Negative
counts, unsupported sizes and conflicting modes are usage errors.

For square boards a per-difficulty size overrides `--size`, which defaults to 9.
Explicit `--rows` and `--cols` apply to every requested difficulty. They cannot be
combined with an explicit `--size` or any per-difficulty size option, even if the
numbers agree; ambiguous combinations exit 2. Dimensions include the clue border.

```powershell
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --medium 1 --size 15 --seed 20261015 --max-attempts 500 --outfile square.pdf
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --hard 1 --rows 10 --cols 20 --seed 20261015 --max-attempts 500 --outfile rectangle.pdf
```

Exit codes: 0 success; 1 failed/duplicate slots, missing export IDs or I/O error;
2 invalid arguments; 130 cooperative cancellation. A partial batch still saves
successful puzzles and writes their booklet. No successes means no PDF is written.
An existing PDF is left intact if no new puzzles succeed.

## Retrieval and verification

```powershell
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --db output/acceptance/puzzles.db --verify-db
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --db output/acceptance/puzzles.db --export-ids 1,2,3,4 --outfile output/acceptance/booklet.pdf
java -cp target/kakuro-generator-0.1.0-SNAPSHOT.jar io.github.skr35.kakuro.pdf.PdfInspector output/acceptance/booklet.pdf output/acceptance/previews
```

The inspector renders every page to PNG and extracts its text. PDF puzzle pages
have empty white cells; solutions follow on separate pages. Wide boards use
landscape A4 and tall/square boards use portrait A4. Cells stay square. Booklets
containing a rectangle or a board larger than 10 use one solution per page for
legibility; booklets of small squares retain four-up solutions. Across clues occupy
upper-right triangles; down clues occupy lower-left triangles.

The orientation and compact-solution behavior above apply to the default `a4`
preset. KDP presets use exact 8.25×11 or 8.50×11 inch portrait pages, mirrored
inside/outside margins and embedded fonts. Puzzle pages remain full-size; compact
answer keys target two columns by three rows, reducing capacity for larger boards.
Answers show digits and shaded black cells without repeating clue sums.
Wide boards scale within the selected trim without rotating. Clues are at least
7 points; layouts that cannot fit legibly are rejected. Margin options require a
KDP preset. No bleed is implemented. See [PDF publishing documentation](docs/PDF_LAYOUT.md)
for margins, publishing limits and the regenerated 18-page mixed review booklets
and 7-page six-up square examples. These short samples are below KDP's publishing
page minimum; the CLI reports their actual page counts and warns accordingly.

```powershell
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --db output/solution-review/puzzles.db --export-ids 1,3,4,5,6,8,9,10,11,13,14,15 --page-size kdp-8.25 --outfile output/kdp-review/kdp-8.25.pdf
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --db output/solution-review/puzzles.db --export-ids 1,3,4,5,6,8,9,10,11,13,14,15 --page-size kdp-8.50 --outfile output/kdp-review/kdp-8.50.pdf
```

Each database insertion is independently committed. Repeating generation with the
same seed, schedule and configuration in the same database rejects duplicate
puzzles and exits 1 without changing existing records. Retrieval preserves
requested order, including repeated IDs; missing IDs are reported.

Opening an existing Java schema-v1 database transactionally adds nullable row and
column fields and upgrades its schema version to 2. Existing rows, IDs, solutions,
scores and square fingerprints are preserved. Older executables reject schema v2;
retain a backup if you need to use the older executable. This is not a migration
of databases belonging to other applications.

## Algorithms and correctness

Geometry uses randomized row-pattern constraint search across the full rectangular
interior. Partial boards may have unfinished vertical runs; completed boards must
pass legality, connectivity and a solver-independent quality gate before filling.
Density, spatial coverage, run variety/balance, diagonal concentration and interior
clues are checked. See [geometry thresholds](docs/GEOMETRY_QUALITY.md).
The top row and left column remain black; all white cells belong to one across
and one down run. Generation currently uses maximum run lengths 4/5/6 for
Easy/Medium/Hard-or-Evil; validation and solving still support lengths 2–9.

A bounded backtracking filler chooses distinct digits for crossing runs and
derives clue sums. A separate solver receives only geometry and clues. It uses
exact digit-set dynamic programming to propagate run constraints, then minimum
remaining values backtracking. It stops on the second solution. A unique result
requires the entire remaining search tree to finish. Budget exhaustion is
INCONCLUSIVE even if one solution has already been found.

Per candidate: at most 10,000 fill-search visits and 20,000 solver-search visits.
Budgets count work, not elapsed time. Every accepted puzzle must also match the
requested difficulty band; there is no out-of-band fallback.

Digit filling balances logarithmic clue-combination risk with seeded candidate
ordering and penalties for digit imbalance and repeated sequences. A separate
solution-quality gate checks entropy, concentration, regional distribution,
sequences and clue diversity; batches also reject near-identical numeric patterns.
These are engineering heuristics, not a uniform-frequency requirement.
Bounded legal
digit changes can tune a proven-unique candidate toward the requested score band.
Every change consumes a candidate and must be re-proven unique. The solver and
scoring formula are unchanged. Each geometry search has at most 32 restarts of
1,000 states. Batch selection requires at least 14% mask distance, including
rotations/reflections and resumes rejected slots within their original budgets.

Rectangular geometry searches along its shorter axis and transposes the completed
mask when necessary. Quality thresholds scale by each interior dimension and area.
For all supported dimensions, pairs of solver counterexamples guide up to 64 legal digit
changes per repair chain. Each proposal consumes a candidate and is fully re-solved;
only a proven unique, in-band puzzle can be accepted. Rectangular layout identity
uses only dimension-preserving reflections/180-degree rotations; a quarter turn
changes dimensions and does not preserve its identity.

## Initial difficulty model

Let L be average run length, A the average log2 of the number of distinct digit
sets compatible with each clue and N the number of solver search visits
(including the root).

```text
score = 10 × (L − 2) + 10 × A + 4 × log2(max(1, N))
Easy:   [0, 12)
Medium: [12, 20)
Hard:   [20, 28)
Evil:   [28, infinity)
```

These are measurable structural/search bands, **not calibrated human difficulty**.
They do not use board size directly. Solver changes may alter scores and require
an algorithm-version change.

Independent `logical-v1` analysis now reports fixed-digit deductions, sum bounds,
run-combination and placement reasoning, candidate eliminations, forced moves and
dependency depth. It consumes clues only and does not change generation labels or
stored scores. STALLED means beyond its supported rules, not that a human must
guess. The 65-puzzle [calibration report](docs/DIFFICULTY_CALIBRATION.md) documents
overlaps and why there is insufficient evidence to replace classification thresholds.

```powershell
java -jar target/kakuro-generator-0.1.0-SNAPSHOT.jar --db output/solution-review/puzzles.db --analyze-db
```

## Reproducibility and cancellation

Each slot gets a child seed from a master java.util.Random stream. Worker tasks
own their RNGs. Ordered result handling makes accepted content, duplicate
selection and final puzzle order independent of worker count. Timings and PDF/
database timestamps are not reproducible.

Algorithm `kakuro-solution-v4` improves digit assignment over geometry version 3.
Seeds can produce different puzzles than earlier versions. Within this version,
seed/configuration reproducibility remains independent of workers.
Existing SQLite records and puzzle fingerprints remain compatible and unchanged.

Ctrl+C cooperatively interrupts generation; committed SQLite records remain.
In-flight results that have not been committed can be lost. PDF output is written
through a temporary file and replaced only after a complete successful export.

## Tests and limits

JUnit covers geometry, exact uniqueness outcomes, inconclusive searches,
independent solver cross-checks, all four levels, score boundaries, deterministic
parallel output, SQLite round trips/deduplication, failure/cancellation, CLI and PDFs.
To audit actual saved examples with the independent test solver:

```powershell
mvn clean verify "-Dkakuro.acceptance.db=output/acceptance/puzzles.db"
```

The optional saved-example audit is skipped without that property. Generation is
stochastic and bounded: some seeds fail at the default budget, particularly for
Easy/Evil. Increase --max-attempts or choose another seed for ordinary batches.
Bank budgets/seeds are fixed at initialization; use a separate database for a different
configuration. Dimensions outside 7–20 (except the explicit 15×30/40/50 probe),
page formats other than A4 and the two KDP presets, human difficulty calibration and compatibility with
other applications' databases are not implemented.

The requested 11–15 squares and 8×12, 10×15, 10×20, 15×10 and 20×10 rectangles have
local accepted examples. Support for an input dimension does not guarantee success
for every seed/difficulty. The full 7–20 dimension/difficulty matrix is not benchmarked.
`tools/DimensionBenchmark.java` records bounded candidate, geometry, uniqueness and
difficulty acceptance statistics; see [the report](docs/LARGE_BOARDS.md).

Dependencies: picocli 4.7.7, SQLite JDBC 3.53.4.0, PDFBox 3.0.8, SLF4J no-op 2.0.17;
JUnit Jupiter 5.12.2 for tests. No application framework or ORM.
On JDK 24+ the SQLite native loader may print a native-access warning; JDK 21 is
the supported baseline.

## Structure

- model: immutable board, runs, validated puzzle and fingerprint
- solver: exact constraint propagation and backtracking
- analysis: independent logical deduction profiles, separate from legacy difficulty labels
- geometry: rectangular mask construction, quality gate, diversity and contact-sheet report
- generator: bounded digit filling, clue tuning and unchanged score bands
- batch: bounded parallel generation with ordered result handling; isolated experimental workers
- persistence: direct JDBC, schema version 2 with v1 upgrade; atomic puzzle/checkpoint transactions and bank writer lock
- pdf: vector booklet writer and local PNG inspection utility
- cli: options, validation, orchestration, resumable bank targets, reports, export and verification
- .github/workflows/ci.yml: Java 21 build/tests on Linux and Windows

See [decisions](DECISIONS.md) for the application contract.