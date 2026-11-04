# Validation

One section per phase: what was compared against what, with which tolerance, what the result was,
and which deliberate mutations were caught by which test. A number in this file always carries its
provenance, and no number is ever compared against an earlier output of this library.

---

## Phase 0 — skeleton

Phase 0 delivers no numerics, so there is nothing yet to compare against mpmath or Stan. What is
verified here is that the reference data is present and readable, that the lint gate really fails,
and that the private-term guard really fires.

### What was checked

| Check | Against | Result |
|---|---|---|
| The build loads and every module compiles | — | 15 modules, Scala 3.3.9, `Test/compile` green |
| 45 reference files present on the test classpath | the inventory in `Reference.allFiles`, written from the reference package's own manifest | green |
| 28 JSON reference files parse | the files themselves | green |
| 6 CSV reference files parse | the files themselves | green |
| `poisson_lpmf(5 \| 0)` reads as `-Inf` | `python/densities_extreme.json`, case 9 (mpmath, 50 digits) | exact |
| `normal_lpdf` first invalid case reads as `+Inf` | `stan-math/distributions.json`, from Stan Math `test/prob` | exact |
| A `NaN` inside a JSON string survives parsing | `stan/diagnostics/expected.json`, key `columns.constant` | exact string |
| First draw of `bern1.csv`: `lp__` and `theta` | CmdStan 2.35.0 output committed with the reference data | exact (tolerance 0) |
| `ar1.csv` is 1000 values, first value exact | the committed series | exact (tolerance 0) |
| A missing file and a missing column are `Left`, not exceptions | — | green |
| `sbt "scalafixAll --check"` | — | green on the tree |
| `sbt scalafmtCheckAll` and `sbt scalafmtSbtCheck` | scalafmt 3.11.5, `.scalafmt.conf` | green after one reformatting pass (JDK 25, see D-3) |
| `sbt slow:test` | — | green (no slow suite yet) |
| `sbt headerCreateAll` / Apache headers | sbt-typelevel from `licenses` and `startYear` | every source carries the ALv2 header |
| `scripts/check-private-terms.py` with no digest file | — | passes, says nothing is configured |
| `scripts/check-private-terms.py` with digests but no key | — | **exit 2**, refuses to pass |

Tolerances are 0 wherever a value is read back from a file rather than computed: parsing must be
exact, and a tolerance there would only hide a bug.

### Mutations tried

Each mutation was introduced by hand, the intended test was confirmed to fail with a message that
names the cause, and the mutation was then reverted and the suite re-run green.

| Component | Mutation | Test that failed | Message |
|---|---|---|---|
| scalafix gate | a `private object` in `core` with `var n = 0` and a `while` loop | `sbt "scalafixAll --check"` | `[DisableSyntax.var] mutable state should be avoided` and `[DisableSyntax.while] while loops should be avoided`, each with file, line and column |
| `Reference` token quoting | `quoteNonFinite` made to rewrite the whole file instead of only what lies outside string literals | `the word NaN inside a JSON string is left untouched` **and** `every JSON reference file parses` | 2 of 9 tests failed, 7 passed |
| private-term guard | a line containing a test term planted in a tracked file | `scripts/check-private-terms.py` | `docs/probe-mutation.md:1: private term (digest 8ad4449c3595...)`, exit 1 — the term itself is never printed |

The test term and its HMAC key were created outside the repository, used, and the digest file was
not committed.

### Known gaps at the end of phase 0

1. **`.private-terms.sha256` is absent**, so the guard passes without checking anything, and the
   `BAYES4S_PRIVATE_TERMS_KEY` secret is unset on the repository. Only the maintainer can produce
   them, with `scripts/hash-private-terms.py` and a key that stays out of the repository. Until
   then the rule rests on human review.
2. **`stress.yml` has never run.** It is registered and scheduled weekly, but there is no slow
   suite to run until phase 6, so nothing has exercised it yet.
3. **The register of local mutation sites is empty and its CI cross-check is not written**
   (docs/design.md, D-13). Owed with the first annotated site, in phase 2.

### Closed on 4 November 2026

- `sbt scalafmtCheckAll` runs, on JDK 25.0.5. The four sources it reformatted carry no semantic
  change — split lines, and `case` arrows no longer aligned, as `align.preset = none` asks.
- **The first CI run went green end to end**, on `ubuntu-latest` with temurin 25, in 4m07s:
  headers and formatting, scalafix lints, the 9 tests (`Passed: Total 9, Failed 0, Errors 0`),
  binary compatibility, the API documentation and the manual. The reference data loads from the
  test classpath on a clean runner, not only from a warm local checkout. The private-term step
  ran and reported that nothing is configured, which is what it must do while no digest exists.
