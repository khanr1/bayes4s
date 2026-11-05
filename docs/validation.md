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

### The reference data was refreshed on 5 November 2026

The reference package was replaced wholesale. Of the 50 data files, 44 are byte-identical to what
phase 1 was validated against, one changed and five are new; of the 12 scripts, six are identical,
three changed and three are new.

The one changed file is `stan-math/distributions.json`, which grew from 196 valid and 361 invalid
cases to 205 and 366 by adding two function families: `bernoulli_logit` (7 valid, 2 invalid) and
`binomial_logit` (2 valid, 3 invalid). **What this library covers did not change** — 30 valid
density cases, 46 distinct parameter rejections, 7 variates outside the support — because both
families are logit parameterisations of laws whose logit form is not implemented yet. Only the
deferred counts grew, and the two suites that assert them were updated: no tolerance was touched.

The new parameter `binomial.alpha` is listed in `StanMathFixtureSupport.notYetImplemented` beside
`neg_binomial_2.eta`, rather than being allowed to fall through onto our `probability`. Without
that list the rejection suite would have gone on passing while asserting one case fewer than it
reported.

The five new data files — `multilevel/multilevel.json`, the three files of a fictional
correlated varying-effects posterior, and `rethinking/rethinking.json` — are read by no test yet.
They are loaded and parsed by `ReferenceSuite`, which is what makes the inventory of 50 files and
their syntax a live claim rather than a promise.

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

---

## Phase 1 — the core and the laws as values

115 fast tests and 6 slow ones, against mpmath at 50 significant digits, the Stan Math test suite,
`java.util.SplittableRandom`, exact integer arithmetic, and analytic results. Every number below
has a source; none comes from an earlier run of this library.

### Special functions and numerics

| Check | Against | Tolerance | Result |
|---|---|---|---|
| `lgamma` on 72 points, 0.001 to 1e5, negatives and poles | mpmath, 50 digits | 1e-13 relative above 1, absolute below | green, Apache Commons Numbers plus the reflection formula |
| `digamma` on 72 points | mpmath, 50 digits | 1e-13 | green **after replacing Commons Numbers**, which is 2e-10 relative away near x = 1 (design D-17) |
| `lbeta` on 6 pairs, including (300, 0.25) and (1e5, 1e5) | mpmath, 50 digits | 1e-13 | green after the Stirling-difference form (D-19); the textbook form reaches 2.3e-13 on (300, 0.25) and cannot do better |
| normal quantile on 16 points down to p = 1e-300 | mpmath, 50 digits | 1e-13 | green, Wichura's AS241 |
| `logistic`, `logInvLogit`, `log1pExp` at ±800, `log1mExp`, `logit` to p = 1e-300, `logSumExp` | mpmath, 50 digits | 1e-13 | green, 85 points |
| `lgamma(x+1) - lgamma(x) = log x` and `digamma(x+1) - digamma(x) = 1/x` | analytic | `4e-13 * max(1, \|f(x)\|)` | green on 11 points each |
| type-7 quantiles | NumPy and R, 8 cases | 1e-12 | green, plus the closed form on 1..101 |
| radix-2 FFT, lengths 1 to 64 | a naive O(n²) discrete Fourier transform written in the test | `1e-13 * magnitude` | green, plus Parseval, the impulse, the constant signal and the inverse round trip |
| Brent's minimum on three objectives | analytic minima | 1e-7 argument, 1e-12 value | green (D-21 explains the argument) |
| Brent's root on x² - 2 and cos x - x | analytic roots | 1e-12 | green; non-bracketing, exhausted budgets and unevaluable functions all return `Left` |
| totality of every numeric function | ScalaCheck over `NaN`, `±Inf`, `-0.0`, subnormals, ±800, ±1e300 | — | green, no exception and every documented range respected |

The error budget of the two recurrences is stated rather than assumed: each `lgamma` carries about
1e-13 of its own magnitude, and the difference cancels that magnitude down to `log x`, so the bound
has to be absolute and scaled by what was cancelled. A relative bound on the difference is
unachievable at x = 1e5 and would mean nothing.

### Randomness

| Check | Against | Threshold | Result |
|---|---|---|---|
| 1000 draws from 7 seeds | `java.util.SplittableRandom`, an independent implementation of SplitMix64 by its own authors | bit for bit | green |
| `uniform` strictly inside (0, 1) | — | 1e5 draws, none at either end | green; `log(u)` is always finite |
| uniform, normal and gamma (4 shapes) distributions | Kolmogorov-Smirnov against `Erfc` and `RegularizedGamma` of Apache Commons, called in the test | 1.95/√n, the 0.1 % point | green |
| moments of normal, gamma, Poisson (λ from 0.1 to 1e4), Dirichlet, categorical | analytic moments | 4 standard errors | green, on both sides of the shape = 1 boost and the inversion/PTRS boundary at λ = 10 |
| Poisson counts in 10 bins | a mass function computed in the test by its own recurrence | chi-squared 27.877, 9 degrees of freedom, 0.1 % | green |
| categorical frequencies | its own probabilities | chi-squared 18.467, 4 degrees of freedom | green |
| two split streams | sample correlation | \|r\| ≤ 4/√n | green; 16 streams pairwise in the slow suite |
| `Batch` against the `State` program, for normal, Poisson and the alias table | each other | exact, element by element | green |
| every pure function with local mutation | itself, twice, in sequence and through `parTraverse` | identical, and inputs unchanged | green for `Fft` and `Batch` |

The slow suite repeats all of it at a million draws, over nine gamma shapes and eleven Poisson
means, and is run weekly by `stress.yml` with a seed taken from the clock and printed in every
message so that a failure can be replayed as a fixed seed.

### The laws

| Check | Against | Tolerance | Result |
|---|---|---|---|
| 30 density cases of the 10 laws in the fixture | Stan Math `test/prob` | 1e-12 relative | green, with one exact value substituted (see below) |
| 46 distinct invalid parameters | Stan Math | exact | green: each is a `Left(InvalidArgument)` naming that parameter |
| 7 variates outside the support | Stan Math, read under our own contract | exact | green: `-Inf`, where Stan throws (D-24) |
| 29 densities at extreme arguments | mpmath, 50 digits | 1e-12 relative | green, including `half_normal`, `poisson_log` at a log rate of -700, and `neg_binomial_2` at a dispersion of 1e8 |
| the mass of each count law summed over its support | analytic | 1e-12 | green |
| moments of all 14 laws against their own `mean` and `variance` | their closed forms | 4 standard errors | green, at 1e5 draws and again at 1e6 |
| the Gamma-Poisson conjugate update, with and without an exposure | `worked_examples.json` | 1e-12 | green |
| the negative binomial predictive and the Poisson that plugs in its mean | `worked_examples.json` | 1e-12 on the moments, exact on both 90 % intervals | green; the intervals are obtained by summing the mass function, which exercises `logMass` across its range |
| the grid posterior, and the posterior after a second observation | `worked_examples.json` | 1e-12 | green |

### Where the reference is wrong

For `neg_binomial_lpmf(10 | 1e6, 1e5)` the binomial coefficient is an integer, so the answer is
computable exactly: `-2.07856664311089194671129470824235`, by exact integer arithmetic and 40-digit
decimal logarithms. This library returns it to **1.3e-15**. Stan Math asserts a value 1.3e-12 away
and SciPy one 2.0e-10 away; the fixture recorded them as agreeing because their errors share a sign
and its threshold was looser than both. The suite asserts the exact value for this one case and
names the reason in place (design D-20).

This is the first time in the project that Stan has been overruled, and it is overruled on
demonstrated numerics rather than on preference, which is the only ground the plan allows.

### Coverage of the Stan Math fixture, and what is deferred

`StanMathCoverageSuite` partitions all 196 valid and 361 invalid cases and asserts the size of each
part, so these numbers cannot quietly stop being true: implementing one more law or one cumulative
function changes a count and the suite says which row to update.

| Part | Count |
|---|---|
| valid density cases covered | 30 |
| distinct parameter rejections covered | 46 |
| variates outside the support covered | 7 |
| valid cases deferred: the `cdf`, `lcdf` and `lccdf` families, and the `neg_binomial_2`, `bernoulli` and `binomial` logit parameterisations | 175 |
| distinct parameter rejections deferred | 49 |
| variates outside the support deferred, in the cumulative and logit families | 37 |
| laws outside the specification: bernoulli, beta_binomial, cauchy, chi_square, double_exponential, exponential, inv_gamma, student_t | 8 |

Four fields of `worked_examples.json` are also deferred, all of which need the inverse incomplete
gamma: `equal_tailed_90`, `hpd_90`, `p_gt_10` and `p_gt_6` of the conjugate example. The empirical
intervals of phase 5 may make the analytic ones unnecessary; the decision belongs to that phase.

### Mutations tried

| Component | Mutation | Test that failed | Message |
|---|---|---|---|
| `lgamma` | the call to Commons Numbers replaced by a bare Stirling approximation, which is what truncating the asymptotic series amounts to here | `lgamma on 72 points`, and in passing `lbeta` and the recurrence | 3 of 8 tests failed, 5 passed |
| `Rng`, the `State` interpreter | returns the seed it was given instead of the next one | `two successive draws differ, because the seed advanced` | `two consecutive uniforms were both 0.3624326349800531` |
| the register of local mutation | one site removed from the register in `docs/design.md` | `scripts/check-mutation-register.py` | `Batch.scala [var]: 6 annotated in the code, 5 listed in the register` |

The second mutation is worth one more sentence. Besides failing its targeted assertion, it makes
every rejection sampler loop forever — gamma, Poisson above mean 10, and the polar normal all
retry on an input that never changes — so the suite does not merely fail, it hangs. A generator
that does not advance is not a wrong generator, it is a deadlock, and that is a reason for the
`Batch`-against-`State` equality to exist.

### Known limits at the end of phase 1

1. **`split` and `fold` have no external oracle.** `java.util.SplittableRandom` carries a gamma per
   instance while a `Seed` here is one `Long`, so only `next` is checked against the JDK bit for
   bit. The other two are held to determinism, distinctness over a thousand indices, and the
   absence of pairwise correlation over sixteen streams. If a better reference appears, this is the
   claim to strengthen.
2. **The polar normal truncates the far tail.** Its smallest uniform is about 2e-16, which caps a
   draw near 8.6 standard deviations. That is beyond anything a momentum draw needs and well past
   where the Kolmogorov-Smirnov statistic could see it, but it is not an exact normal.
3. **Poisson inversion stops at 2000.** Below a mean of 10 the walk cannot plausibly get there, and
   the cap exists so that a corrupted rate cannot produce an unbounded loop.
4. **No cumulative distribution functions**, by the scope decision of this phase. The 166 deferred
   values are the measure of it.

---

## Phase 1b — the multilevel laws, the linear algebra, the names and the vocabulary

149 tests, 143 fast and 6 slow, up from 122. The 81 tests of phases 0 and 1 are untouched in
substance: not one assertion or tolerance changed, only the names they call (design D-26).

### The ten laws

| Check | Against | Tolerance | Result |
|---|---|---|---|
| 53 valid density cases, up from 30 | Stan Math `test/prob`, with the exact value substituted for the one case Stan gets wrong (D-20) | 1e-12 relative | green |
| 78 distinct invalid parameters, up from 46 | Stan Math | exact | green: each a `Left(InvalidArgument)` naming that parameter |
| 14 variates outside the support, up from 7 | Stan Math, read under our own contract | exact | green: `-Inf` for the thirteen finite ones, `NaN` for the `NaN` of `exponential` (D-33) |
| `bernoulliLogit(0 \| 25)` | mpmath, 50 digits: -25.000000000013888 | 1e-12 relative | green, and asserted **not** to agree with SciPy's -24.99999582 |
| 13 multivariate Gaussian densities | ports of `multi_normal_cholesky_lpdf` | 1e-12 relative | green |
| what the multivariate Gaussian must refuse | the four prose descriptions of the fixture, built as inputs in the test | exact | green |
| 20 LKJ normalising constants, across four dimensions and four concentrations | `multilevel.json` | 1e-12 relative | green, with no special case at `eta = 1` (D-29) |
| 9 LKJ densities on a Cholesky factor | `multilevel.json` | 1e-12 relative | green |
| the onion sampler's marginal correlations | the theorem: Beta(eta - 1 + K/2) on (-1, 1), as `lkj_corr_test.cpp` checks | chi-squared 27.877, 9 degrees of freedom, 0.1 % | green at three combinations of dimension and concentration |
| moments of the eight new scalar laws | their own `mean` and `variance`, standard error from the sample's fourth moment (D-22) | 4 standard errors | green |
| Cauchy, half Cauchy and Student-t with three degrees of freedom | Kolmogorov-Smirnov against closed-form cumulatives | 1.95/sqrt(n), the 0.1 % point | green — these laws have no moments to test (D-30) |
| the multivariate sampler | `mu` and `L L'` | 4 standard errors | green, on both variances and the covariance |

### The linear algebra

| Check | Against | Tolerance | Result |
|---|---|---|---|
| 4 matrix-vector products, 3 Cholesky factors, 3 triangular solves, 1 group indexing | `multilevel.json` | 1e-12 relative | green |
| `L L' = S` and `logDetFromCholesky` against the sum of the logs of the diagonal | the input itself | 1e-12 relative | green |
| a matrix that is not positive definite | — | exact | green, and the message **names the pivot** |
| asymmetry, ragged rows, an upper triangle, a negative diagonal, a rectangular factor, a wrong length, an index out of range, a size below one | — | exact | green: all `Left` |

### The names and the vocabulary

| Check | Result |
|---|---|
| the five deprecated aliases return exactly what their replacements return, `NaN` and the far tails included | green |
| `scripts/check-terminology.py` over the manual | green: 5 pages, 25 terms, none to replace |
| the three chapters of phase 1 brought into line | three terms rewritten — "the same samples" for "the same draws", "the Gaussian quantile" for "the normal quantile", "a stream of samples" for "a stream of draws" — and the escape hatch used nowhere |

### Mutations tried

| Component | Mutation | Test that failed | What it said |
|---|---|---|---|
| `Linalg.cholesky` | the pivot no longer checked | `a matrix that is not positive definite is refused, and the pivot is named` | see below |
| `BernoulliLogit` | through the probability instead of `log1mInvLogit` | the exact value at a log odds of 25 | `obtained -24.99999582410784, expected -25.000000000013888` |
| `LkjCorrCholesky` | the Jacobian half of the diagonal weight dropped | the 9 densities | `obtained -2.248185272379627, expected -2.479271433218281` |
| `MultiNormalCholesky` | the log determinant dropped | the 13 cases | `obtained -9.286445229243647, expected -11.739082618462863` |
| a deprecated alias | `logSigmoid` forwarding to `logInvLogit(-x)` | `DeprecatedNamesSuite`, at -800 | the alias and its replacement disagree |
| `check-terminology.py` | "a vague prior and a credible interval" planted in chapter 1 | the script | both terms reported with their replacements; adding `<!-- term-ok -->` to the line made it green again, which is the only test the escape hatch gets |

Two of these are worth more than a line.

**The first mutation survived, and that was the finding.** Removing the pivot check left all nine
linear-algebra tests green: `sqrt` of a negative gives `NaN`, and `Cholesky.ofLower` caught the
`NaN` diagonal one layer down, so the function still returned a `Left`. The test accepted the right
outcome for the wrong reason. It now asserts the **diagnosis** — that the message names the pivot
and says what positive definite means — and the mutation is caught. The redundancy between the two
guards is kept; what changed is that the useful one is the one that speaks.

**The second mutation reproduced SciPy's error to nine digits.** Going through the probability
returns -24.99999582410784, against SciPy's documented -24.99999582. That is not a coincidence and
it is the whole argument for `logInvLogit` and `log1mInvLogit`: the error is not in SciPy's
arithmetic, it is in the order of its operations, and any implementation that repeats the order
repeats the error.

### Coverage of the Stan Math fixture, after phase 1b

| Part | Was | Is |
|---|---|---|
| valid density cases covered | 30 | **53** |
| distinct parameter rejections covered | 46 | **78** |
| variates outside the support covered | 7 | **14** |
| valid cases deferred (the cumulative families, and `neg_binomial_2_log`) | 175 | 152 |
| distinct parameter rejections deferred | 49 | 17 |
| variates outside the support deferred | 37 | 30 |
| laws outside the specification | 8 | **3**: `chi_square`, `double_exponential`, `inv_gamma` |

### Known limits at the end of phase 1b

1. **No cumulative distribution functions still**, which is what the 152 deferred values measure.
2. **`lkj_corr` on a correlation matrix** — the three `corr_cases` of `multilevel.json` — is not
   covered: the specification asks for the Cholesky form only, and the matrix form belongs to the
   optional `rethinking` module of phase 13.
3. **`cholesky_corr_transform`, `psis_loo` and `power_scaling`** of `multilevel.json` are read by
   no test yet: they belong to phases 3 and 6b.
4. **The glossary is a starting point**, as the plan says. It will grow as the manual does, and the
   check is only worth the rows the table lists.

