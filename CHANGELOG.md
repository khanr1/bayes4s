# Changelog

This project is built one phase at a time. Each phase gets an entry here when it meets its
definition of done: tests green in CI, lint clean, a section in `docs/validation.md`, a section in
`docs/design.md` for every decision taken, and the manual chapters it affects updated.

Versions follow git tags. Nothing is released yet.

## Unreleased

### Phase 1b — the multilevel laws, the linear algebra, the names (5 November 2026)

Added

- Ten laws as values: `StudentT`, `Cauchy`, `HalfStudentT`, `HalfCauchy`, `Exponential`,
  `BernoulliLogit`, `BinomialLogit`, `BetaBinomial`, `MultiNormalCholesky`, `LkjCorrCholesky`,
  each with a smart constructor, a density and a sampler. The laws on the log-odds scale never
  pass through the probability; the heavy-tailed ones report `NaN` for the moments they do not
  have, and are tested against their whole distribution instead.
- `core.linalg`: `Matrix`, an opaque `Cholesky` carrying its invariant, and the five operations the
  multilevel models need — `matVec`, `cholesky`, `lowerSolve`, `logDetFromCholesky`, `gather`.
- `docs/manual/glossary.md`, `docs/manual/terminology.csv` and
  `scripts/check-terminology.py` in CI: the manual's prose keeps the vocabulary of *Statistical
  Rethinking* while the API keeps the names of Stan.
- 27 tests, for 149 in all. Coverage of the Stan Math fixture rises from 30 valid density cases to
  53, from 46 distinct parameter rejections to 78, and the laws outside the specification fall from
  eight to three.

Changed

- The naming rule is applied: `invLogit`, `logInvLogit`, `invPhi`, `quantile`, `quantileOfSorted`
  and `Normal.stdNormal` replace their former names, which stay as `@deprecated` forwarders until
  v0.1 and are asserted to agree with their replacements.
- `Matrix.identity` and `Linalg.lowerSolve` return `Either`, like every other constructor and
  partial operation in the library.

Found

- The LKJ normalising constant needs no special case at `eta = 1`: the product form of Theorem 5
  reproduces all twenty reference constants, the four uniform ones included, to 1.8e-14. Stan's
  separate branch is an optimisation (`docs/design.md`, D-29).
- Computing a Bernoulli on the log-odds scale through the probability reproduces SciPy's error to
  nine digits, which is what the mutation test showed (`docs/validation.md`).


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

### Phase 2 — automatic differentiation (5 November 2026)

Added

- `Real[A]`, the arithmetic a log density is written against, with the three interpreters the plan
  asks for: `Double` for values, a reverse-mode tape for gradients, and dual numbers as an exact
  oracle in the tests. Every vectorised primitive carries a default written in terms of the scalar
  operations, which is what makes the oracle independent of the adjoints it checks.
- `DiffFn`, whose rank-2 signature stops a tape from leaking out of the gradient that owns it;
  `Reverse.value`, `Reverse.valueAndGradient` and `Reverse.nodeCount`; `GradientCheck` for central
  differences; and `bayes4s.syntax`, so that a model reads as a formula.
- Vectorised primitives: `sum`, `cumulativeSum`, `logSumExp`, `poissonSum`, `poissonLogSum`, three
  `normalSum`, `bernoulliLogitSum`, `binomialLogitSum`, `dot`, `matVec`, `lowerSolve` and
  `gather` — each one node of the tape, and `gather` none at all.
- `LogDensity` and `LogMass`: nineteen scalar densities, the ordered logistic, the multivariate
  Gaussian and the LKJ prior on a Cholesky factor, all written against `Real[A]`, with vectorised
  overloads for the five primitives a model would otherwise reach for directly.
- `lbeta` as a primitive of `Real[A]`, the one addition to the trait the plan sketches: written as
  three `lgamma`s it loses 1.0e-9 on a negative binomial at a concentration of 1e6 (design D-38).
- A JMH benchmark of both models, and chapter 10 of the manual, which reports what it measured: a
  gradient costs 2.0 times a value, and a vectorised density is 5.8 times faster than the same
  arithmetic written out, with 89 tape nodes against 30 103.
- Chapter 1 of the manual gains its `Real[A]` section, so the same density is shown evaluated,
  differentiated and checked.
- 41 tests, for 190 in all. The gradient of all 53 Stan Math density cases is checked against the
  oracle, the 72 invalid parameters of those cases give `NaN` on this layer where the law layer
  gives `Left`, and the four densities Stan has no test for — the three half laws and the ordered
  logistic — are checked against mpmath at 50 digits.

Found

- **The ordered logistic needed Stan's identity, not its mirror.** A middle category written as
  `log(expm1(gap)) + ...` is exact and not total: `expm1` overflows above 710, so two cut points
  7e9 apart gave a probability above one. The totality property of §6.9 found it; the fix is
  `log_inv_logit_diff`, where no term can overflow (D-41).
- **Two mutations survived the first time and strengthened a test each.** The zero-count branch of
  `poissonSum` was read through the tape only, which has its own copy, so the default that the
  other two interpreters inherit was never exercised at a rate of zero. And the ordered-logistic
  case meant to catch a naive difference of probabilities — two cut points one rounding apart — is
  a case where the naive difference happens to be exact; a saturated category at a log odds of 30
  is what discriminates, and the naive form is 1.3e-5 away there.
- **The value-only path is slower than the gradient** on a vectorised model with ten thousand
  observations, because `Real[Double]` inherits the boxing defaults while the tape overrides them.
  Measured, understood and deliberately left alone until something needs it (D-46).
- **A continuous predictor is not vectorised yet**: written out per observation it costs three tape
  nodes a row and 5.8 times the time. The fix is a fused linear-predictor primitive, for the phase
  that has a multilevel regression to run (D-47).

### Phase 3 — transforms, named parameters and models (5 November 2026)

Added

- `Constraint` and `Transform`: the eleven constraints the specification lists — unconstrained,
  positive, lower and upper bounds, interval, unit interval, offset-multiplier, ordered,
  sum-to-zero, simplex and the Cholesky factor of a correlation matrix. A transform **returns** its
  log Jacobian instead of adding it to an accumulator, which is what lets a test assert the
  Jacobian on its own; `constrain` is total and `unconstrain` returns `Either`, because a value
  outside its constraint has no antecedent.
- `ParamSpec`, a pure applicative description of a model's parameters: names, sizes, constraints
  and how to read them out of the unconstrained vector, composed with `mapN` and with no registry
  anywhere. Every value carries the name CmdStan would give it, column major for a matrix.
- `Model`, with the prior and the likelihood separate; `Model.target` for the sampler,
  `Model.priorOnly`, `Model.constrain`, `Model.values` and `Model.unconstrain`; and the optional
  generative part, `Generative` and `GenerativeModel`, whose `conditionedOn` is what
  simulation-based calibration will be built on.
- `BayesError.DuplicateParameter`, and appendix B describes it.
- Chapter 2 of the manual, *Writing a model*: the eight schools written centred, then non-centred,
  with the change of variables checked rather than asserted.
- 59 tests, for 249 in all, of which 19 are the applicative laws of `ParamSpec` under discipline.
  The gradient of `Model.target` is checked against the dual-number oracle on a model carrying one
  block of **every** constraint, and ten mutations — one per component — were all detected.

Found

- **The inverse of `sum_to_zero_constrain` is derivable**, and was derived here rather than ported:
  the recursion inverts cleanly, and the result reproduces the fixture's fifteen cases and its
  simplex round trips to 1e-15.
- **A saturating transform comes back only as well as its inverse allows.** `atanh` multiplies the
  last bit of a correlation by `cosh²(y)`, so a round trip at `y = 10` cannot do better than 1e-8.
  The property asserts 1e-12 over the range a model lives in and the conditioning bound beyond it,
  which is tighter than a flat tolerance where it can be and honest where it cannot.
- **`.mapN(Params.apply)` is not available in Scala 3**: a polymorphic method eta-expands with
  `A := Any` before it meets a polymorphic function type. A model writes the lambda, and chapter 2
  explains why the function has to be polymorphic in the first place.
- **An error from `Model.unconstrain` now names the block.** Writing chapter 2 showed what a user
  would actually read — `value = -1.0 violates value > 0.0`, with thirty parameters to choose from
  — so the error is relabelled with the block's name and, where the transform named one, the
  coordinate.

### Phase 4 — Metropolis, static HMC, NUTS and the warmup (5 November 2026)

Added

- `inference`: the leapfrog integrator, `Metric` in its diagonal and dense shapes, static
  `Hmc`, `RandomWalkMetropolis`, and `Nuts` following Stan — multinomial sampling, the generalised
  U-turn criterion, a maximum tree depth, a divergence when the energy error passes its threshold.
  `build_tree` is a function returning a subtree summary rather than eight references the caller
  passes in, and the case `unit_e_nuts_test.cpp` asserts matches Stan's six digits and the
  fixture's independent port's sixteen.
- The warmup as an immutable state machine: dual averaging on the log step size, the windows of
  `windowed_adaptation.hpp` — for a thousand iterations the metric is re-estimated at 99, 149, 249,
  449 and 949 — Welford accumulators for both metric shapes with Stan's regulariser, and the
  initial step-size heuristic.
- `Mcmc.run` and `Mcmc.runNuts`: a fold over values, so a run is a function of its seed and four
  chains computed in parallel are the four computed in sequence, bit for bit. `Init` in its three
  forms, including the prior median from a generative part or from a short run on the prior alone.
- `Generative.named`, without which `Init.PriorMedian` cannot exist, and
  `AdaptationSettings(metric = MetricShape.Dense)` to ask for a dense mass matrix.
- Chapters 0 and 3 of the manual: a quick start that fits a model in a page, and the sampler — what
  the warmup learns, where a chain starts, and what every transition reports.
- 48 tests, for 297 in all. Thirteen mutations, one per component.

Found

- **A dense metric is worth a factor of 43** on a Gaussian correlated at 0.95 in ten dimensions:
  0.0926 effective samples per gradient against 0.0021, and the adapted step size grows from 0.16
  to 0.66. At 0.7 in four dimensions it is worth much less, which is why the manual says to measure
  before reaching for it.
- **The acceptance after warmup is above the target, not at it.** The last fifty warmup iterations
  average 0.77 to 0.79, which is the statistic dual averaging controls; sampling with the step size
  it leaves behind realises 0.84 to 0.93, because `exp(xBar)` over fifty iterations is
  conservative. Stan behaves the same way, so the criterion is tested from both sides instead:
  above the target at one step size, below it at two.
- **Four of the thirteen mutations survived the first run**, and all four were missing tests rather
  than wrong code — every one of them because Stan's own fixtures use a unit metric, where the
  metric, a velocity and a momentum, and both ends of the U-turn criterion all coincide. The tests
  added are in docs/validation.md; the most useful is the equivalence a metric *is*, which pins it
  in the position step and in the energy at once.
- **A random walk wants an acceptance near 0.23.** Driven to Stan's 0.8 it crawls, and reports a
  variance of 0.52 where the truth is 1 while an autocorrelation estimator claims eight hundred
  effective samples. The suites give it its own target and also check the span of its draws.
- **The sampler needed no local mutation.** The specification allows the leapfrog and the tree to
  mutate local arrays; neither did, and the register stays at the 27 sites it had.

### Phase 5 — the diagnostics and CmdStan's CSV (5 November 2026)

Added

- `diagnostics`: the convergence statistics of Vehtari et al. (2021) **as Stan implements them** —
  biased autocovariance by FFT, Geyer's initial positive and monotone sequence, the split at
  `floor((n+1)/2)`, rank normalisation, R-hat basic and rank-normalised, the basic, bulk and tail
  effective numbers of samples, and both Monte Carlo errors. The fourteen values Stan's own tests
  assert are reproduced at Stan's tolerances and at 1e-10 against the repository's line-by-line
  port.
- `Summary` and `Summary.table`, a `stansummary`-shaped report whose rows are named by the model's
  own `ParamSpec`; `SamplerReport` for divergent transitions, saturated tree depths and E-BFMI; and
  `Intervals` for the percentile interval, the shortest interval and an exceedance probability.
- `Draws` in `core`: posterior samples as a rectangle, built only through a smart constructor, with
  access by name, by chain, flattened, and repeated per draw.
- `stan-io`: `StanCsv.parse` and `render` as pure functions of lines, `parseAll` and `renderAll` for
  a fit of several chains, and `PosteriordbSummary` for the reference summaries phase 6 compares
  against. The round trip is exact to the last bit, `NaN` and `±Inf` included.
- Chapters 4 and 9 of the manual — diagnosing a fit, and reading and writing CmdStan's files — and
  an intervals section in appendix C. The chapter-4 example fits the **centred** eight schools on
  purpose: it reports 91 divergent transitions and an E-BFMI of 0.32 and 0.27, which is what the
  chapter is about.
- `reference/check_cmdstan_csv.py`, which asks Stan's own tooling to read what this library writes.
- 33 tests, for 330 in all. Fourteen mutations, one per component.

Found

- **A file CmdStan's reader accepts needs more than well-formed rows.** `cmdstanpy.from_csv`
  refused the first renderer's output twice: once for having no trailing comment block, which is
  how it finds where the sampled rows end, and once for declaring `num_warmup = 0` while the
  adaptation was engaged. Neither would have been found by reading the format. All four of its
  structural rules are now asserted in Scala, so `sbt test` catches a regression without Python.
- **`arviz.from_cmdstan` no longer exists.** ArviZ 1.3 moved the CmdStan reader out to `cmdstanpy`
  and converts through `arviz_base.from_cmdstanpy`, so the check goes through both — which is
  stricter than the original, since the file must satisfy Stan's own parser first. The rendered
  chains come back identical to the originals to the last bit.
- **`f"%.4f"` formats in the default locale**, so the summary table printed `-54,1903` here and
  would print `-54.1903` on CI. A table that differs between machines is not a table;
  `Locale.ROOT` everywhere, and the suite asserts that no comma appears.
- **Two of the fourteen mutations survived**, both because Stan's own chains cannot separate two of
  its conventions: the upper tail's inclusive comparison, which is invisible when the lower tail is
  the smaller of the two, and the floor on `tau`, which binds only for a strongly antithetic
  sample. Without the floor such a sample reports an effective size of **-344 524**; the two tests
  that close the gaps are built to separate the conventions on purpose.
