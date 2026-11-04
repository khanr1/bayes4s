# Changelog

This project is built one phase at a time. Each phase gets an entry here when it meets its
definition of done: tests green in CI, lint clean, a section in `docs/validation.md`, a section in
`docs/design.md` for every decision taken, and the manual chapters it affects updated.

Versions follow git tags. Nothing is released yet.

## Unreleased

### Phase 1 — the core and the laws as values (4 November 2026)

Added

- `core`: `Seed` (SplitMix64, checked bit for bit against `java.util.SplittableRandom`), `Rng[F]`
  with its `State` interpreter, `Sampling` (gamma by Marsaglia and Tsang, Poisson by inversion and
  Hormann's PTRS, beta, Dirichlet, categorical, alias), `Batch` and `AliasTable`, `BayesError`,
  `Special` (lgamma, digamma, lbeta, the normal quantile by AS241), `LogExp`, type-7 `Quantile`,
  a radix-2 `Fft`, and `Optimize` (Brent's minimum and Brent-Dekker root).
- `distributions`: fourteen laws as values behind `ContinuousLaw` and `DiscreteLaw` — Normal,
  HalfNormal, LogNormal, Gamma, Beta, Logistic, Poisson, PoissonLog, NegBinomial2, NegBinomial,
  Binomial, Categorical, OrderedLogistic, Dirichlet — each built by a smart constructor that
  returns `Either`, plus the Gamma-Poisson conjugate update and its negative binomial predictive.
- 115 fast tests and 6 slow ones, against mpmath at 50 digits, the Stan Math test suite, the JDK's
  own SplitMix64, Apache Commons cumulative functions, exact integer arithmetic and analytic
  results. `StanMathCoverageSuite` asserts what of the fixture is covered and what is deferred, so
  the record in `docs/validation.md` cannot quietly stop being true.
- `scripts/check-mutation-register.py` and a CI step for it: the sites of encapsulated local
  mutation annotated in the code must match the register they are argued for in `docs/design.md`.
- Manual: chapter 1 on the concepts, appendix B on the error contract, appendix C on the classic
  worked examples, every example compiled and run by mdoc.

Found

- Apache Commons Numbers' `Digamma` is 2e-10 relative away from the truth near x = 1, which misses
  the 1e-13 the reference demands; `digamma` is implemented here instead (`docs/design.md`, D-17).
- Stan Math's asserted value for `neg_binomial_lpmf(10 | 1e6, 1e5)` is wrong by 1.3e-12 relative,
  and SciPy's by 2.0e-10, while this library is within 1.3e-15 of the exactly computable answer.
  The suite asserts the exact value for that case (D-20).


### Phase 0 — skeleton (3 November 2026)

Added

- sbt build on Scala 3.3.9 (the LTS line) with sbt-typelevel 0.8.7: strict compiler options,
  fatal warnings in CI, Apache 2.0 headers on every source, MiMa, and a generated `ci.yml`.
  Fifteen modules wired up according to the architecture, all empty but `testkit`.
- `testkit`, which depends on no other module of this build: `Reference` loads the reference data
  from the test classpath and returns `Either`, accepting Python's `Infinity`, `-Infinity` and
  `NaN` tokens while leaving those words untouched inside JSON strings; `Approx` and `MonteCarlo`
  give tolerance-based and standard-error-based expectations whose failure messages state what was
  compared and against which threshold.
- The reference data of 45 files — Stan Math distribution tests, Stan HMC and diagnostics cases,
  nine posteriordb posteriors, mpmath special functions and extreme densities, properscoring CRPS,
  worked examples — with the Python scripts that regenerate it, and the BSD-3-Clause licences of
  Stan, Stan Math and posteriordb in `LICENSES/`.
- `ReferenceSuite`: nine tests asserting that every reference file is present and parses, that
  non-finite tokens decode to the right IEEE values, that CmdStan CSV columns carry the values
  CmdStan wrote, and that a missing file or column is a value rather than an exception.
- scalafix with `DisableSyntax`: the build fails on `var`, `while`, `throw`, `null`, `return`,
  `asInstanceOf` and `isInstanceOf`.
- A `slow` sbt configuration for the statistical suites, with `stress.yml` running them weekly
  with fresh seeds, outside the blocking build.
- `scripts/check-private-terms.py` and `scripts/hash-private-terms.py`: an HMAC-SHA-256 guard
  against any term that would tie this repository to a real organisation, checking both the tree
  and the commit messages of a pull request, and failing closed when it cannot verify.
- `docs/design.md` and `docs/validation.md`, started; `docs/manual/index.md` as the manual's
  skeleton.

Notes

- Publishing is deliberately off: no release branch, no release tag, no publish job.
- The build needs a JDK 23 or newer, because scalafmt 3.11.5 does; the artefacts target Java 17.
  CI runs on temurin 25 and `ubuntu-latest`.
- Formatting settled at phase 0 so it never has to be redone: 100 columns, no alignment, and
  scaladoc in the Scala style rather than scalafmt's javadoc default (`docs/design.md`, D-14).
