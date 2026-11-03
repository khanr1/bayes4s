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

1. **`sbt scalafmtCheckAll` has not been run.** scalafmt 3.11.5 needs a JDK >= 23 and this machine
   has Java 22 at most (docs/design.md, D-3). CI runs on temurin 25, where it will run. Formatting
   is therefore unverified locally: the sources are hand-formatted to the 100-column rule of
   `.scalafmt.conf`, which is not the same thing as scalafmt agreeing.
2. **`.private-terms.sha256` is absent**, so the guard passes without checking anything. Only the
   maintainer can produce it, with `scripts/hash-private-terms.py` and a key that stays out of the
   repository. Until then the rule rests on human review.
3. **No CI run has happened.** The repository is local and private; `ci.yml` and `stress.yml` are
   written and generated but have never executed on a runner.
4. **The register of local mutation sites is empty and its CI cross-check is not written**
   (docs/design.md, D-13). Owed with the first annotated site, in phase 2.
