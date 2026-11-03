# CLAUDE.md

bayes4s: a general-purpose Bayesian modelling library in Scala 3 (reverse-mode AD, NUTS
conforming to Stan, diagnostics, predictive simulation, forecast scoring, nowcasting models).

## Commands

- Build and test: `sbt test`
- Before every commit: `sbt headerCheckAll scalafmtCheckAll "scalafixAll --check" test`
  (`scalafixAll --check` needs the quotes; it takes an argument)
- Slow statistical suites (SBC, posteriordb): `sbt slow:test`, sources in each module's
  `src/slow/scala`
- Stress mode (fresh seeds, more draws): `sbt -Dbayes4s.stress=true slow:test`
- Docs: `sbt docs/mdoc` (every code block must compile)
- Private-term check: `python3 scripts/check-private-terms.py`
- The build needs a JDK 23 or newer (scalafmt does); the artefacts target Java 17

## How we work

- The maintainer gives the work one phase at a time. Do only the phase asked for, then stop and
  report how to verify it. Never start the next phase on your own.
- Ask before changing a public signature, adding a dependency, or deviating from an agreed design.
  Record every decision in `docs/design.md`.
- Write tests first when possible and show them before implementing.

## Functional programming rules (enforced by scalafix)

- Every public function is referentially transparent. No `var`, `while`, `throw`, `null`,
  `return`, `asInstanceOf`, no mutable type in a public signature, no global randomness or I/O
  outside the `effect` module.
- Errors are values: `Either` / `ValidatedNec` of `BayesError`. Numeric functions are total and
  return `NaN` or `±Inf` outside their domain.
- Randomness is a value: `Seed` and `Rng[F]`, interpreted by `State` in the core.
- Densities are written once against `Real[A]`, never inside `F[_]`.
- The only allowed exception is local mutation that never escapes a pure function (AD tape,
  leapfrog, FFT, batch sampling). Mark each site with `// scalafix:ok` plus one sentence of
  justification, and list it in `docs/design.md`.

## Testing rules

- Compare only against independent references (`testkit/src/main/resources/reference/`, analytic
  results, the `Dual` oracle). Never against earlier output of this library.
- Never widen a tolerance, change a seed or skip a test to make it pass. Investigate and report.
- Monte Carlo checks use the MCSE based on ESS, with the threshold stated in the test.
- Before declaring a component done, break it on purpose, confirm the intended test fails, revert,
  and record the mutation in `docs/validation.md`.
- When references disagree, Stan wins over ArviZ and mpmath wins over SciPy; document the gap.

## Content rules (absolute)

- This repository is generic. No organisation-specific content of any kind: no names, domain
  vocabulary, figures or data from any real business, in code, tests, docs, examples or commit
  messages.
- Examples use only the fictional world in `examples.FictionalWorld` or public data.
- If a name or example might look specific to a real case, choose a more generic one and tell the
  maintainer.
- Never print, log or commit the plain-text private term list, nor the HMAC key. Only
  `.private-terms.sha256`, which holds keyed digests, lives in the repository.
