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


## Phase 2 — automatic differentiation

190 tests, 184 fast and 6 slow, up from 149 — 41 added by this phase. The 143 fast tests of
phases 0, 1 and 1b are untouched except for one that was **strengthened** by a surviving mutation
(below); no tolerance, seed or assertion was weakened anywhere.

The phase's claim is narrow and worth stating precisely: a log density is written once, and the
value, the gradient and the oracle are three readings of that one text. What follows is what each
reading was checked against.

### The primitives, against the oracle and against finite differences

The dual-number oracle implements the scalar operations and **inherits** every vectorised primitive
from the defaults of `Real[A]`, which are written in terms of those scalar operations. The
reverse-mode interpreter overrides them with hand-written adjoints that push a single node.
Comparing the two is comparing a hand-written derivative against the derivative of the composition
it claims to equal (design D-36).

| Check | Against | Tolerance | Result |
|---|---|---|---|
| every scalar operation of `Real[A]` in one function, at an ordinary point | the oracle | 1e-12 relative | green |
| a variable read by four paths | the oracle | 1e-12 relative | green: the adjoint accumulates all four |
| an input never read | — | exact | green: a gradient of exactly 0.0, not merely small |
| `sum`, `cumulativeSum`, `logSumExp`, `dot` | the oracle | 1e-12 relative | green |
| `gather` with a repeated index (three observations in one cluster) and an empty cluster | the oracle | 1e-12 relative | green |
| the three `normalSum`, `poissonSum`, `poissonLogSum`, `bernoulliLogitSum`, `binomialLogitSum` | the oracle | 1e-12 relative | green |
| `lowerSolve` and `matVec` on a 3 by 3 factor | the oracle | 1e-12 relative | green |
| the same eight functions | central differences, `h = 1e-6 max(1, \|x\|)` | 1e-6 | green |
| a planted sign error in a gradient | central differences | — | green: detected, which is what makes the check above worth running |
| `poissonSum` at a rate of 0, 1e-300 and 1e-9 | the closed form, and the oracle | value 1e-15, gradient exact | green: value exactly 0 and gradient exactly -1 at a rate of zero, through all three interpreters |
| a tape of 200 000 operations | the closed form of 100 000 additions | 1e-9 relative | green |
| 16 gradients computed with `parTraverse` | the same 16 computed in sequence | bit for bit | green |
| two calls with the same arguments | each other, and the input array afterwards | bit for bit | green |

### The densities of `LogDensity` and `LogMass`

Three kinds of reference, and the difference between them is the point. Stan Math and mpmath are
independent; the law layer is not, and is used only for what it can say.

| Check | Against | Tolerance | Result |
|---|---|---|---|
| the 53 valid density cases, evaluated through `Real[Double]` | Stan Math `test/prob`, with the exact value for the one case Stan gets wrong (D-20) | 1e-12 relative, 1e-14 for the corrected case | green |
| the 72 invalid parameters of the density families | Stan Math, read under our contract | exact | green: `NaN` on this layer, where the law layer gives `Left` — the two halves the plan asks for |
| the 14 variates outside the support | Stan Math, read under our contract | exact | green: `-Inf`, and `NaN` for the `NaN` |
| 9 half-law densities — `halfNormal`, `halfStudentT`, `halfCauchy` | **mpmath at 50 digits**, computed for this phase; Stan has no half law to port | 1e-12 relative | green |
| 14 ordered-logistic masses, including four saturated, two with cut points a rounding apart and two with a gap of 7e9 between them | **mpmath at 50 digits** | 1e-12 relative | green |
| the five categories of an ordered logistic sum to one, at five log odds from -40 to 40 | exact arithmetic | 1e-13 | green |
| the 19 scalar laws, each at an ordinary point | the law layer's own `logPdf`/`logMass` | 1e-14 relative | green: the two layers evaluate the same formula, which is what carries the phase 1 and 1b validation onto this one |
| 13 multivariate Gaussian densities | `multilevel.json` | 1e-12 relative | green |
| 9 LKJ densities, and the 20 normalising constants read through the density of the identity factor | `multilevel.json` | 1e-12 relative | green |
| a factor that is not a factor — an upper entry, a zero pivot, a negative pivot, a wrong length, a `NaN` | — | exact | green: `NaN` in every case |
| an infinite or `NaN` parameter where Stan's fixture is silent | the uniform rule of D-24 | exact | green: `NaN` |
| the vectorised `normal` | the sum of its scalar terms, in value and in gradient | 1e-14 and 1e-13 | green |

Gradients, over the same cases:

| Check | Against | Tolerance | Result |
|---|---|---|---|
| the gradient of all **53** Stan Math cases | the oracle | 1e-12 relative | green; all 53 turn out to be differentiable at the arguments Stan asserts, and the count is asserted so that a silently dropped case fails |
| the gradient of each of the 20 densities | central differences, `h = 1e-5` | 1e-8 | green |
| the gradient of the multivariate Gaussian, in the variate, the mean and the six entries of the factor | the oracle, and central differences | 1e-12, 1e-8 | green |
| the gradient of the LKJ density in the concentration and the six entries | the oracle | 1e-12 relative | green |
| the three off-diagonal entries of the LKJ factor | — | exact | green: exactly 0.0, because the density does not read them |

### Totality, the §6.9 property for this phase

ScalaCheck over arbitrary doubles — `NaN`, `±Inf`, `-0.0`, subnormals, `1e-320`, `Double.MaxValue`
— with the domain rule **restated in the test** rather than read from the code, because a test that
asked the implementation what its rule was would agree with any rule at all.

| Property | Result |
|---|---|
| the four location-scale densities return a value and are `NaN` exactly where the rule says | green |
| the five positive-support densities likewise, and `-Inf` below zero | green |
| the beta density likewise, and `-Inf` outside the unit interval | green |
| the eight masses likewise, and `-Inf` for a negative count | green |
| the ordered logistic likewise, and its categories never hold more than all the mass | **found a bug, see below** |
| the tape returns a value and a gradient of the right length at any point, and agrees with the `Double` interpreter to 1e-12 wherever both are numbers | green |

**The totality property found a real overflow.** The first implementation of a middle ordered-logit
category used the identity `log(expm1(a - b)) + log1mInvLogit(a) + logInvLogit(b)`, which is exact
and, as written, not total: `expm1` overflows above 710, so two cut points 7.07e9 apart gave
`log(+Inf) = +Inf` for the category's log mass — a probability above one, which is the assertion
that failed. The implementation now uses Stan's `log_inv_logit_diff` identity instead,
`logInvLogit(a) + log(1 - exp(b - a)) + log1mInvLogit(b)`, where no term can overflow; both
forms were checked against mpmath over fourteen cases and agree to 2e-16 everywhere the first one
does not overflow (design D-41). The two cases the property produced are now asserted as values,
with mpmath references, so the regression does not depend on a random seed.

### The two models

`ar1PoissonOrdinal` is a reporting triangle: a non-centred AR(1) log rate over 36 months, an
ordered-logit delay over 12 lags whose cut points drift with calendar time, and Poisson counts on
the 234 visible cells. 52 parameters. `varyingIntercepts` is ten thousand observations in fifty
clusters. Both are fictional by construction — the data is a deterministic function of the row
number (2.8).

| Check | Against | Tolerance | Result |
|---|---|---|---|
| the gradient of `ar1PoissonOrdinal`, all 52 coordinates | the oracle | 1e-12 relative | green |
| the same | central differences, `h = 1e-6` | 1e-6 | green |
| its value | the `Double` interpreter and the oracle | 1e-14 relative | green, and **not** bit for bit: see D-43 |
| 16 gradients of it computed concurrently | the same 16 in sequence | bit for bit | green |
| the gradient of `varyingIntercepts` at 1 000 and at 10 000 observations | the oracle | 1e-12 relative | green |
| its tape at 100, 10 000 and 100 000 observations | each other | exact | green: **89 nodes in all three cases**, of which 53 are the parameters themselves |
| the tape of the same model with a scalar predictor written out per observation | the count at 100 observations | exact | green: exactly 3 more nodes per observation — the constant `predictor(i)` is itself a leaf |

### The benchmark

JMH, 3 warmup and 5 measurement iterations, one fork, one thread, on an Apple M2 Pro with 16 GB and
OpenJDK 25. Average time per call, with the 99.9 % interval JMH reports:

| Benchmark | Time |
|---|---|
| `ar1PoissonOrdinal`, value only | 73.9 ± 0.2 µs |
| `ar1PoissonOrdinal`, value and gradient | **148.9 ± 0.5 µs** |
| `varyingIntercepts`, 10 000 observations, value and gradient | **79.0 ± 1.6 µs** |
| `varyingIntercepts`, 100 observations, value and gradient | 2.5 ± 0.0 µs |
| `varyingIntercepts`, 10 000 observations, value only | 83.1 ± 0.3 µs |
| the same 10 000 observations with the slope written out per observation | 458.4 ± 0.6 µs |

Three readings, all recorded in chapter 10 of the manual:

1. **A gradient costs 2.01 times a value** on a 52-parameter model. That is the claim reverse-mode
   differentiation is made for, and it holds; forward differences would have cost 52 values.
2. **A vectorised density is 5.8 times faster** than the same arithmetic written out, on the same
   model and for the same answer, with 89 tape nodes against 30 103.
3. **The value-only path is slower than the gradient** on the vectorised model — 83.1 against
   79.0 µs — because `Real[Double]` inherits the boxing defaults while the tape overrides them.
   That is surprising, it is understood, and it is left alone on purpose (D-46).

### Mutations tried

| Component | Mutation | Test that failed | What it said |
|---|---|---|---|
| `Tape.gradient` | adjoints assigned instead of accumulated | 4 of the 9 `ReverseSuite` tests | `the gradient after 100 000 additions: obtained 1.0E-5, expected 1.0` |
| `RevReal.normalSum` | the sign of the adjoint of the scale flipped | 3 tests, including the model at 10 000 observations | `d/dx(52): obtained 54270.25, expected 36088.43` |
| `RevReal.normalSum` | the override removed, so the primitive falls back to the default | `varyingIntercepts: the tape does not grow with the data` | the three node counts stop being equal |
| `Real[Double].lbeta` | `Special.lbeta` replaced by the definition, three `lgamma`s | the 53 density cases | `neg_binomial.lpdf(10, 1e6, 1e5): obtained -2.0785666421134295, expected -2.078566643110892` — 1.0e-9 away, which is design D-38's whole argument |
| `LogDensity.normal` | the check on the scale removed | 2 tests | `normal sigma: got -Infinity, expected NaN` |
| `Real.poissonSum` | the zero-count branch removed, so `0 log 0` is formed | `poissonSum is exact at a rate of 0, 1e-300 and 1e-9` | see below — **it survived first** |
| `LogDensity.orderedLogistic` | the stable difference replaced by the difference of two probabilities | the mpmath cases | see below — **it survived first** |

**Two mutations survived, and fixing that is the only change made to an existing test.**

The `poissonSum` mutation survived because the suite read the zero-rate case through
`Reverse.valueAndGradient` only, and the reverse-mode interpreter has its **own** `poissonSum`: the
mutation was in the default that `Real[Double]` and the oracle inherit, and no assertion reached
it. The test now also reads the rate of zero through the `Double` interpreter and through the
oracle, in value and in derivative — four assertions where there were none — and the mutation is
caught. The gap was not in the code but in which interpreter the test happened to use, which is
exactly the kind of thing only a mutation finds.

The `orderedLogistic` mutation survived because of an accident of binary arithmetic. The case meant
to catch it was two cut points one rounding apart — 1.0 and 1.0 + 1e-15 — and there the naive
difference is *exact*: the gap is five units in the last place of 1, and five units in the last
place of a half is exactly representable too, so the subtraction near a half loses nothing. The
discriminating case is a **saturated** category instead: for the second of four categories at a log
odds of 30 both probabilities are within 1e-13 of one, their difference keeps three digits of
sixteen, and the naive form is 1.3e-5 relative away from mpmath. Four such cases were added, at log
odds 15 and 30, and the tiny-gap cases were kept with a comment saying what they do *not* test.

### No statistical test in this phase

The definition of done asks every statistical test to state its standard error and its threshold.
This phase has none to state: differentiation is deterministic, so every comparison here is against
an analytic value, an independent implementation or a 50-digit reference, at a stated tolerance. The
one randomised test is a ScalaCheck property over arbitrary doubles, whose assertion is a contract
rather than an estimate.

### Known limits at the end of phase 2

1. **No `LogDensityUnnormalized`** (D-45): the variant without the normalising constants waits for
   the sampler that benefits from it, in phase 4.
2. **`Real[Double]` is not optimised** (D-46): the value-only path inherits the boxing defaults and
   is measurably slower than the taped path on a vectorised model.
3. **A continuous predictor is not vectorised** (D-47): a multilevel regression with a covariate has
   a tape linear in the number of observations. The measurement is recorded; the fix is a fused
   linear-predictor primitive, for the phase that has such a model.
4. **The oracle's `lbeta` derivative is the same formula typed twice** (D-38), not an independent
   derivation; the independent check for it is central differences.
5. **The gradient at an invalid parameter is zeros, not `NaN`** (D-40). Harmless because the value
   is `NaN` and the proposal is rejected on the value, but it is a thing to know before reading a
   gradient without looking at the log density first.
6. **No cumulative distribution functions** still, as at the end of phase 1b: the 152 deferred
   values of the Stan Math fixture are unchanged by this phase, which adds no new law.

## Phase 3 — transforms, named parameters and models

249 tests, 243 fast and 6 slow, up from 190 — 59 added by this phase, of which 19 are the
applicative laws. The 184 fast tests of phases 0 to 2 are untouched; no tolerance, seed or
assertion was weakened anywhere.

### The constraint transforms

Against ports of `stan/math/prim/constraint` and against Stan's own test files. Two things are
asserted for every family, and they are not the same thing: the **value** is what the model sees,
and the **log Jacobian** is what makes the density on the unconstrained space the right density —
get it wrong and the posterior moves while every value stays plausible.

| Check | Against | Tolerance | Result |
|---|---|---|---|
| 18 lower-bound and 9 upper-bound cases | `transforms.json` | 1e-12 relative | green |
| 27 interval cases | `transforms.json` | 1e-12 relative | green |
| the 9 of those that carry one, against the fixture's own finite difference | `transforms.json` | 2.6e-8, the error the fixture reports for itself | green |
| 9 offset-multiplier cases | `transforms.json` | 1e-12 relative | green |
| 15 ordered cases, their Jacobians, their finite differences and their inverse | `transforms.json` | 1e-12 relative | green |
| 15 sum-to-zero cases, and the inverse **derived here** | `transforms.json` | 1e-12 relative | green, and the values sum to zero to 1e-12 |
| 15 simplex cases, their Jacobians, their finite differences and the round trip the fixture states | `transforms.json` | 1e-12 relative | green |
| 12 Cholesky correlation factors, their Jacobians and their inverse | `multilevel.json` | 1e-12 relative | green; rows of norm one to 1e-12 |
| the same 12 against the fixture's numerical determinant | `multilevel.json` | no further from it than the fixture's own closed form, which at K = 5 is 1.9e-6 away | green |
| the 5 cases Stan's own transform tests assert, each read by name | `lub_transform_test.cpp`, `ordered_transform_test.cpp`, `simplex_transform_test.cpp` | 1e-12 relative | green |
| `Interval(0, 1)` at `x = -1000` | Stan's `underflow` case | exact | green: **exactly 0**, and a log Jacobian of -1000 rather than `NaN` |
| the simplex of the empty vector | Stan's `simplex_0` case | exact | green: one component, equal to 1 |
| every transform's round trip, on random points | itself | 1e-12 relative | green, on `\|y\| <= 4` (design D-59) |
| the round trip of a saturated correlation | the conditioning of `atanh` | `1e-14 cosh²(y)` out to `\|y\| = 10` | green |
| the log Jacobian of the seven square families | a finite difference per coordinate, computed in the test | 1e-6 | green |
| the unconstrained sizes of all seven shapes | — | exact | green |
| 14 values outside their constraint | — | exact | green: all `Left`, each naming what it refused |
| `constrain` on arbitrary doubles, `NaN` and `±Inf` included | — | — | green: a value every time, never an exception |

The finite differences deserve a word. Three of them are the fixture's own, computed when it was
built, and the fixture states its own worst error — 2.6e-8 on `transforms.json`. For the Cholesky
factor the fixture's two numbers differ by 1.9e-6 at K = 5, because a 10 by 10 numerical
determinant is the noisy quantity; the test therefore asserts that our closed form is no further
from the finite difference than the fixture's own closed form is, and asserts the closed form
itself to 1e-12 separately. The seven square families get a finite difference computed here, which
shares nothing with either.

### `ParamSpec`

| Check | Result |
|---|---|
| the layout of an eight-block spec: names, sizes, constraints, order, total size | green |
| every value's CmdStan name, for a scalar, a vector, a matrix and a Cholesky factor | green, column major (D-50) |
| the reading, against the transforms applied by hand to the slices the layout implies | green to 1e-15 on the values, 1e-14 on the summed Jacobian |
| a spec with no constrained block adds **exactly** zero | green |
| two blocks of one name | green: `Left(DuplicateParameter)`, naming the name |
| the reading is the same function in `Double`, on the tape and in the oracle | green, gradient to 1e-12 |
| the 19 applicative laws, with discipline | green (D-51) |
| `mapN` reads the blocks in the order they were written, at two arities | green |

### `Model`

| Check | Against | Tolerance | Result |
|---|---|---|---|
| the target is the prior, the likelihood and the Jacobians, and nothing else | the three computed separately | 1e-14 relative | green |
| the gradient of the target, on eight schools centred, non-centred, and a model with one block of **every** constraint | the dual-number oracle | 1e-12 relative | green |
| the same | central differences | 1e-6 | green |
| a point becomes named values and comes back | itself | 1e-12 relative | green |
| the named values are the constrained values, under the layout's names, column major | `Model.constrain` | exact | green |
| a missing name, a vector of the wrong length, a value outside its constraint | — | exact | green: `UnknownParameter`, `DimensionMismatch`, `InvalidArgument` naming the block (D-60) |
| a model whose two blocks share a name | — | exact | green: refused before anything is read |
| `priorOnly` | the prior computed alone | exact and 1e-14 | green: the likelihood is **exactly** zero and the prior is unchanged |
| a derived quantity, `theta = mu + tau z` | the formula | 1e-15 relative | green |
| specs composing beyond the arities `mapN` covers | the ninth block's value and Jacobian | 1e-13 | green |

### The two readings of the eight schools

The data is Rubin (1981), as posteriordb carries it: the only data in this repository that is not
invented, and public.

| Check | Against | Tolerance | Result |
|---|---|---|---|
| `logPrior` term by term, over random points including `tau` down to 0.01 | the three law values of phase 1b | 1e-13 relative | green |
| `samplePrior`'s three streams | the same law values' `sample`, same seed | **bit for bit** | green |
| `simulateData` | a Gaussian per school, same seed | **bit for bit** | green |
| 100 000 prior draws of `mu` | Gaussian(0, 5), Kolmogorov-Smirnov | 1.95/sqrt(n), the 0.1 % point | green |
| 100 000 prior draws of `tau` | half Cauchy(5), Kolmogorov-Smirnov | 1.95/sqrt(n) | green — a half Cauchy has no moment to test, so only `mu` gets one |
| the mean of `mu` | 0 | 4 standard errors | green |
| the centred and non-centred models | each other, through `theta = mu + tau z` and its Jacobian `8 log tau` | 1e-12 relative | green |
| `conditionedOn(simulated)` | the model on the observed data | exact | green: the likelihood changes, the prior and the parameters do not |

The last one is the test worth keeping. The two parameterisations describe the same posterior in
different coordinates, and the identity

```
centred.logDensity(mu, tau, theta) + 8 log(tau) = nonCentred.logDensity(mu, tau, z)
```

holds to 1e-12 over random points. If it failed, one of the two models would be fitting something
else — which is the mistake the non-centred form exists to avoid.

### Mutations tried

Ten, one per component of the phase, every one detected on the first run.

| Component | Mutation | Test that failed | What it said |
|---|---|---|---|
| `Interval` | the log Jacobian through the probability, `log(p) + log(1 - p)` | Stan's `underflow` case | `obtained -Infinity, expected -1000.0` |
| `Ordered` | the Jacobian sums every unconstrained value, not the gaps | 3 tests, Stan's `ordered_j` among them | `obtained -6.0, expected -7.0` |
| `Simplex` | the `0.5 log(N + 1)` offset dropped | the 15 simplex cases | `obtained -4.308800583443201, expected -3.9622269931632284`, and the finite difference too |
| `SumToZero` | the orthogonal weights replaced by `1/n` | the 15 sum-to-zero cases **and** the 15 simplex cases | `obtained 3.02736577944728, expected 2.1406708717792697` |
| `CholeskyFactorCorr` | the `0.5 log1m(sum of squares)` half of the Jacobian dropped | the 12 factor cases | `obtained -0.6379479500547498, expected -0.7768126735637668` |
| `ParamSpec.product` | the second block read from the first one's offset | 2 tests | `tau: obtained 1.4918246976412703, expected 0.6065306597126334` |
| `Block.names` | a matrix named row by row | the CmdStan names | the names are in the wrong order |
| `Model.target` | the log Jacobians not added | 2 tests | `obtained -43.703407531220634, expected -44.40340753122064` |
| `Model.unconstrain` | the names no longer checked first | the clashing model | `a duplicate name gave Right(...)` |
| `EightSchools.nonCentred` | `theta = mu + z`, the scale dropped | the two parameterisations | `obtained -59.46195669335476, expected -68.53524913323237` |

### Known limits at the end of phase 3

1. **The prior consistency test is two thirds of what §5.5 asks for.** The identity and the streams
   are asserted; the comparison against MCMC samples from `priorOnly` needs a sampler and arrives
   with phase 4.
2. **No `Ordered` prior on the gaps.** `c0 + cumulativeSum(inc)` with a log-normal prior on the
   increments is the other way to build cut points, and the plan keeps both; the models that need
   it arrive with the nowcasting components.
3. **`cholesky_corr_transform`'s 12 cases are used, `psis_loo` and `power_scaling` still are not**:
   they belong to phase 6b.
4. **`.mapN(Params.apply)` is not available** (D-52): a model writes a one-line polymorphic lambda
   instead. This is a language limit, not a design choice, and it is documented in chapter 2.
5. **No `OffsetMultiplier` on a vector with per-element offsets.** Stan allows a vector of offsets
   and multipliers; the constraint here takes two scalars, which is what the specification lists.

## Phase 4 — Metropolis, static HMC, NUTS and the warmup

297 tests, 291 fast and 6 slow, up from 249 — 48 added by this phase. The 243 fast tests of phases
0 to 3 are untouched; no tolerance, seed or assertion was weakened anywhere, and the register of
encapsulated local mutation stays at 27 sites because the sampler needed none (design D-75).

Every Monte Carlo threshold below is `sd / sqrt(ESS)` with the effective sample size estimated from
the chains themselves, and four standard errors is the budget. A threshold built on the number of
draws rather than the effective number would fail a correct sampler.

### The integrator

| Check | Against | Tolerance | Result |
|---|---|---|---|
| the 11 cases of `expl_leapfrog_test.cpp`, in Stan's own `V`, `q`, `p`, `g` | Stan's own assertions | the fixture's own, 5e-14 for ten of them and 5e-13 for the largest step | green |
| a step forwards then backwards | the starting point | 1e-14 relative | green: the integrator is reversible, which is what makes the Metropolis correction valid |
| the energy error of a trajectory of fixed length, as the step size halves four times | the `epsilon²` law | each ratio in (3, 5); the whole span in (40, 100) | green |
| a metric as a change of variables: a step with `diag(m)` against a unit-metric step on the rescaled target | each other | 1e-12 relative | green |
| a momentum drawn from a diagonal and from a dense metric | a chi-squared of as many degrees of freedom as dimensions | 4 standard errors | green |
| the energy of a `NaN` point | — | exact | green: `+Inf`, which is what makes the proposal divergent |

### The tree

| Check | Against | Tolerance | Result |
|---|---|---|---|
| `build_tree` at depth 3 on `gauss3D`: `rho`, both `p`, both `p_sharp`, the final state, 8 leapfrog steps, `log_sum_weight`, `sum_metro_prob` | `unit_e_nuts_test.cpp`, Stan's own `EXPECT_FLOAT_EQ` | 1e-6 relative | green |
| the same eleven numbers | the NumPy port the fixture ships | 1e-12 relative | green |
| the subtree summary under two different seeds | itself | exact | green: only the proposal uses the generator |
| the generalised U-turn criterion | the 2 cases Stan asserts | exact | green |
| the same, on 6 asymmetric cases **the fixture cannot show** | the definition | exact | green (see the mutations) |
| the summed Metropolis probability on a trajectory that gains energy | at most one per step | exact | green (see the mutations) |
| a trajectory whose energy error passes 1000 | — | exact | green: divergent, and therefore invalid |

### The warmup

| Check | Against | Tolerance | Result |
|---|---|---|---|
| the 9 window schedules, including the rescaling below 150 iterations and the absence of adaptation below 20 | `windowed_adaptation.hpp`, ported | exact | green: for 1000 iterations the metric is re-estimated at 99, 149, 249, 449, 949 |
| one acceptance statistic equal to the target leaves the step size at 1 | `stepsize_adaptation_test.cpp` | 1e-14 relative | green |
| the dual-averaging trajectory, all ten steps and the completed `exp(xBar)` | the port's trajectory | 1e-12 relative | green |
| a statistic above one, and a `NaN` | Stan's cap, and the rejection convention | exact | green |
| the variance regulariser on ten identical draws in ten dimensions | `var_adaptation_test.cpp`: 3.333e-4 | 1e-14 relative | green |
| the diagonal and dense accumulators on 2000 known draws | the sample variance and covariance computed directly | 1e-12 and 1e-10 | green, and the dense estimate is symmetric and positive definite |
| the initial step-size heuristic on a Gaussian and on one 100 times narrower | Stan's `init_stepsize` | the two must differ by more than 20 times | green: about 1 and below 0.05 |
| the whole state machine over 1000 warmup iterations | the schedule | exact | green: the metric changes at exactly those five iterations |

### The samplers

| Check | Against | Tolerance | Result |
|---|---|---|---|
| a standard Gaussian in 5 dimensions, 4 chains: means, variances, the 5th and 95th percentiles | the closed forms | 4 MCSE | green, with an effective sample size above 400 per coordinate |
| a Gaussian correlated at 0.8 in 10 dimensions, 4 chains: means and variances | the closed forms | 4 MCSE | green |
| its 15 pairwise correlations | 0.8 | 4 standard errors | green |
| static HMC on a Gaussian correlated at 0.5 in **100 dimensions**: the slowest direction's mean, and the hundred variances pooled | the closed forms | 4 MCSE | green, accepting 0.87 of the time |
| a random walk on the Gamma(33.5, 4.2) of `worked_examples.json`: the mean and the standard deviation | 7.976190476190475 and 1.378075821760741 | 4 MCSE | green |
| the three kernels on one target | each other and the closed form | 4 MCSE, plus a span of 5 | green |
| Gamma-Poisson, Normal-Normal and Beta-Binomial, each through its own constraint | the conjugate closed forms | 4 MCSE | green |
| the eight schools, non-centred, 4 chains through `runNuts` | posteriordb's reference summary: `mu` near 4.4 and `tau` near 3.6 | 0.6 and 0.8, the reference being itself Monte Carlo | green |
| 4 chains computed in parallel | the same 4 in sequence | **bit for bit** | green (section 2.4's obligatory test) |
| two calls of one transition, and the arrays it was handed | each other, and themselves | exact | green |
| the same seed twice, and two seeds | each other | exact | green |

### What the sampler says about its own work

| Check | Result |
|---|---|
| mean tree depth on a standard Gaussian | green: between 1 and 4, where a criterion that never fired would saturate at 10 |
| a step size of 1e-4 with a maximum depth of 6 | green: depth exactly 6 and 63 leapfrog steps every time, and no divergence |
| the centred funnel against a Gaussian | green: divergent transitions on the funnel, none on the Gaussian |
| the adapted diagonal metric on scales spanning 1e-2 to 1e2 | green: every variance within a factor 0.4 to 2.5 of the truth |
| a starting point where the density is not a number | green: refused, and the error says so |

### The measurements

**A dense metric against a diagonal one**, on a Gaussian correlated at 0.95 in 10 dimensions,
1000 warmup and 1000 samples, Apple M2 Pro with OpenJDK 25. The cost is counted in gradients
because that is the unit of work of a Hamiltonian method, and the direction measured is the slowest
one — the average of the coordinates.

| Metric | Effective samples | Gradients | Per gradient | Adapted step size |
|---|---|---|---|---|
| diagonal | 21 | 9 764 | 0.0021 | 0.16 |
| dense | 600 | 6 472 | **0.0926** | 0.66 |

A factor of **43**, and it comes from both halves: the dense metric earns four times the step size
*and* twenty-eight times the effective samples. The suite asserts a factor above 5, which leaves
room for the seed and still fails if most of the gain is lost. The adapted covariance's
off-diagonal entries come out between 0.6 and 1.2 against a true 0.95.

**The acceptance after warmup**, on a Gaussian correlated at 0.5 in 8 dimensions:

| | value |
|---|---|
| mean acceptance over the last 50 warmup iterations | 0.768 to 0.781 |
| `exp(x)` at the end of warmup | 0.23 to 0.51 |
| `exp(xBar)`, the completed step size | 0.46 to 0.54 |
| mean acceptance while sampling | 0.84 to 0.93 |

The statistic dual averaging controls lands on its target; the step size it leaves behind is
conservative, so the realised acceptance is above the target. That is Stan's behaviour too, and the
criterion is tested as what it is about — above the target at one step size, below it at two
(design D-71).

### Mutations tried

Thirteen, one per component and three more that the first round showed were needed. **Four survived
the first run**, and each one was a missing test rather than a wrong implementation.

| Component | Mutation | Test that failed | What it said |
|---|---|---|---|
| `Leapfrog.evolve` | a full step of momentum instead of a half | 3 of 6 `LeapfrogSuite` tests | `evolve_1: V: obtained 1.6584988776097886, expected 1.67676034808195` |
| `Leapfrog.updatePosition` | the metric dropped from the position step | the change-of-variables property | `position 0: obtained -1.289108540184802, expected -1.112912742986` — **after the test was added** |
| `Metric.sampleMomentum` | `sqrt(inverse)` instead of its reciprocal, in the diagonal branch | the momentum's covariance | `diagonal: the mean of p' M^-1 p is 20.2, expected 3` — **after the diagonal case was added** |
| `Nuts.criterion` | one end checked instead of both | the asymmetric cases | `the start has turned: criterion gave true, expected false` — **after they were added** |
| `Nuts.buildTree` | the base case stops accumulating `rho` | the fixture | `Stan rho(0): obtained -1.0, expected -11.401228` |
| `Nuts.buildTree` | the Metropolis contribution no longer capped at one | the gaining trajectory | `11.58 over 8 steps, which is more than one per step` — **after the test was added** |
| `Nuts.transition` | the top-level criterion never stops the doubling | the variance of a standard Gaussian | `estimate 0.634092159803493, truth 1.0, \|z\| = 8.3` |
| `DualAveraging.learn` | the statistic no longer capped at one | the port's trajectory | `the step size after 6 statistics: obtained 8.945867791034907, expected 4.849226902267655` |
| `DualAveraging.completed` | `exp(x)` instead of `exp(xBar)` | the port's final value | `obtained 0.5640943957812794, expected 1.1411619037506946` |
| `WindowSchedule` | the lookahead that stretches the last window dropped | the 9 schedules | `warmup 1000: re-estimated at List(99, 149, 249, 449, 849), expected (..., 949)` |
| `DiagonalMoments.regularised` | the ridge dropped | `var_adaptation_test.cpp`'s case | `obtained 0.0, expected 3.333333333333333E-4` |
| `Mcmc.run` | the sampling phase uses the unit metric, not the adapted one | the badly scaled Gaussian | `estimated the variance of coordinate 0 as 1.0, 10000 times the true 1.0E-4` |

**The four survivors are the phase's most useful result**, and they share a cause: the fixture pins
the *unit-metric* case, where several distinctions collapse.

- Stan's eleven leapfrog cases are all one-dimensional with a unit metric, so **nothing in them
  mentions the metric at all**. Dropping it from the position update changed no asserted number.
  The test added is the equivalence a metric is: a step with `diag(m)` on a target equals a
  unit-metric step on the target rescaled by `sqrt(m)`, under `q = S x` and `p = S^-1 p~`. It
  pins the metric in the position step and in the energy at once.
- The momentum test used a dense metric only, so the diagonal branch — the default — was never
  exercised. One `forEach` over both shapes, with variances of 2, 0.5 and 4 so that a square root
  and its reciprocal are nowhere near each other.
- Both of Stan's criterion cases are symmetric: `(1, 1, 2)` and `(1, -1, 0)`. The second is false
  either way, because `(-1) · 0` is not positive. Six asymmetric cases were added, read off the
  definition, where one end separates and the other has turned.
- The fixture's trajectory starts at an energy far above `H0`, so every one of its eight steps
  contributes less than one and the cap is never reached. A trajectory started at `q = (6, 6, 6)`
  moving towards the mode gains at every step, and there the cap is the difference between an
  acceptance statistic of 1.45 and one of 1.

### Known limits at the end of phase 4

1. **The doubling loop is pinned by no fixture** (D-65). Stan ships no test for it; what stands
   behind it is the tree-depth behaviour, the funnel, and every statistical test in the suite. If
   phase 6's posteriordb comparison disagrees with Stan anywhere, this is the first place to look.
2. **`testkit.Ess` is not the validated estimator** (D-74). It sizes thresholds and nothing else,
   and it overstates the effective sample size for a chain that has barely moved — which is why the
   random-walk tests also assert the span of the draws. Phase 5 replaces it.
3. **No `LogDensityUnnormalized` still** (design D-45). The sampler is here, so the variant without
   the normalising constants now has a caller to justify it; it was not needed to make this phase
   work, and adding it means a second copy of nineteen densities to keep right. Deferred again,
   deliberately, with the benefit now measurable.
4. **The dense accumulator allocates a `d * d` array per draw.** Immutable, as the rest is, and not
   measured: the dense metric is not the default and a thousand warmup draws in ten dimensions is a
   hundred thousand doubles in total. If a model needs a dense metric in fifty dimensions this is
   the first thing to measure.
5. **E-BFMI is not computed.** The energy of every transition is reported, which is what phase 5
   needs to compute it; the diagnostic itself belongs there.
6. **`Init.PriorMedian` without a generative part runs the sampler on the prior**, which costs a
   short warmup of its own. That is what the specification asks for, and the generative route is
   much cheaper when a model has one.

## Phase 5 — the diagnostics and CmdStan's CSV

330 tests, 324 fast and 6 slow, up from 297 — 33 added by this phase. The 291 fast tests of phases
0 to 4 are untouched; no tolerance, seed or assertion was weakened, and the register of
encapsulated local mutation stays at 27 sites.

### The fourteen values

Seven statistics on each of two columns of the four Bernoulli chains Stan's own tests use, each
asserted **twice**: against Stan's assertion at Stan's own tolerance, and against the repository's
line-by-line port at 1e-10, which is the sharper comparison wherever Stan's tolerance is loose.

| Statistic | `lp__` | `theta` | Stan's tolerance | Result |
|---|---|---|---|---|
| `rhat_basic_split` | 1.00035489482 | 1.00721797217 | 1e-10 | green |
| `ess_basic` | 1335.41366787 | 1377.5030282 | 1e-8 | green |
| `rhat_rank` | 1.00073 | — | 1e-5 | green |
| `ess_bulk` | 1512.7684 | — | 1e-4 | green |
| `ess_tail` | 1591.9707 | — | 1e-4 | green |
| `mcse_mean` | 0.02016 | — | 1e-4 | green |
| `mcse_sd` | 0.03553 | — | 1e-4 | green |

| Other check | Against | Tolerance | Result |
|---|---|---|---|
| the autocovariance of `ar1.csv`, first six lags and its length | `autocovariance_test.cpp`, and the port | 0.01, and 1e-12 against the port | green: 1000 values |
| the five statistics where ArviZ 1.x differs | **ArviZ**, as a disagreement | more than ten tolerances away from ArviZ and within one of Stan | green (D-76) |
| a constant column, one infinite value, three draws | the contract | exact | green: `NaN` everywhere, and `NaN` from `rhat_basic` too |
| the rank transform's three structural properties | the fixture's own list | 1e-12 | green |
| the split: five draws become two halves of two, the middle one dropped | Stan's `floor((n+1)/2)` | exact | green |
| independent draws, ten seeds | R-hat below 1.01 and three effective sizes within 15 % of 4000 | 15 % | green |
| an AR(1) series at `phi = 0.8` | the formula `n (1 - phi) / (1 + phi)` | a factor of 2.5 either way | green |
| the upper tail on a sample with ties at the 95th percentile | the inclusive convention | exact | green (D-83) |
| the floor on `tau`, on an antithetic chain | `total * log10(total)` | exact | green |

### The summary and the intervals

| Check | Against | Tolerance | Result |
|---|---|---|---|
| the percentile interval of a Gamma(33.5, 4.2), both ends | `worked_examples.json` | 4 Monte Carlo standard errors, `sqrt(p(1-p)/n)` over the density | green |
| the shortest interval, both ends | `worked_examples.json` | the same | green |
| and that it is narrower than the percentile one | the shape of a skewed law | — | green |
| the probability of exceeding 10 and of exceeding 6 | `worked_examples.json` | 4 standard errors, `sqrt(p(1-p)/n)` | green |
| the shortest interval of a two-humped sample | — | — | green: it covers the gap, and its width says so |
| an empty sample, a single draw, a mass of 0 or 1.5 | the contract | exact | green |
| a summary's row names | the model's own `ParamSpec`, plus `lp__` first | exact | green: `lp__, mu, tau, z.1 … z.8` |
| a summary's moments on a Gamma(2, 0.5) posterior | the closed form | 4 MCSE | green |
| the table's formatting | the root locale | exact | green: no comma anywhere (D-82) |
| E-BFMI of independent energies | the closed form, 2 | 0.1 | green |
| E-BFMI on a centred funnel against a Gaussian | Betancourt's threshold of 0.3 | — | green: 0.11 and 0.09 against 1.05 and 1.12, with 7 divergences against 0 |
| the counts of divergences and saturated depths | a run made to saturate | exact | green |

### CmdStan's CSV

| Check | Against | Tolerance | Result |
|---|---|---|---|
| a real CmdStan file, comments before **and** after the header | `bern1.csv` | 1e-12 on the first draw | green: 8 columns, 1000 draws |
| the four chains as one fit | the fixture's own independent reader | **bit for bit** | green |
| a round trip of arbitrary doubles, `NaN` and `±Inf` included | itself | **bit for bit** | green |
| a fit of four chains as four files and back | itself | exact | green |
| the four structural rules CmdStan's reader applies | `cmdstanpy`'s own parser, read from its source | exact | green (D-80) |
| seven malformed files | the contract | exact | green: each a `Left` naming the source |
| `nan`, `inf`, `-inf`, `NaN`, `Infinity` | CmdStan's spellings | exact | green |
| a posteriordb reference summary, its rows and its per-chain diagnostics | the file | exact | green: 10 chains of 10 000, every R-hat below 1.01 |
| `Draws` as a rectangle: ragged chains, a narrow draw, a repeated name | the contract | exact | green, each naming what was wrong |
| `Draws` read three ways, and each draw repeated | the rectangle it came from | exact | green |

**The ArviZ check, run rather than asserted.** `reference/check_cmdstan_csv.py` renders the four
real Bernoulli chains back out through this library's renderer and asks Stan's own tooling to read
them:

```
cmdstanpy read: 4 chains, 1000 draws, 8 columns
arviz groups: ['/', '/posterior', '/sample_stats']
  theta: ess 1407.5, rhat 1.006790
  theta: identical to the original to the last bit: True
```

It took three attempts, and the two failures are the useful part:

1. **No trailing comment block** — `cmdstanpy` raised "Unable to count warmup and sampling draws".
   Its reader locates four blocks in order and the last is the timing footer CmdStan always writes;
   without it the file is refused outright, however well-formed the rows are.
2. **`num_warmup = 0` with the adaptation engaged** — "Must specify iter_warmup > 0 when
   adapt_engaged=True". The reader validates the configuration it parsed, not only the shape.

Neither would have been caught by any amount of reading the format, and neither affects our own
parser, which reads its own output happily. `StanCsvSuite` now asserts all four rules so that
`sbt test` catches a regression without Python in the loop.

### Mutations tried

Fourteen, one per component. **Two survived**, for the same reason as in phase 4: the fixture's own
chains cannot separate two of Stan's conventions.

| Component | Mutation | Test that failed | What it said |
|---|---|---|---|
| `autocovariance` | unbiased, divided by `n - t` | 3 of 8, `ess_basic` among them | `obtained 1333.402201889681, expected 1335.41366787` |
| `splitChains` | the second half from `n/2` | the five-draw split, and the rank statistics | `the second half is List(3.0, 4.0)` |
| `rankTransform` | the offset `1/2` instead of `3/8` | `ess_bulk` | `obtained 1512.6716751814292, expected 1512.7684` |
| `essBasic` | the chains split, as ArviZ does | `ess_basic`, and the ArviZ gap | `obtained 1341.6851277442772, expected 1335.41366787` — **exactly ArviZ's number** |
| `mcseSd` | the unsplit effective size of `\|x - mean\|` | `mcse_sd` against the port | `obtained 0.035597364762789255, expected 0.035530465913680566` |
| `ess` | the monotone step dropped | 2 tests | `theta.ess_basic: obtained 1219.41, expected 1377.50` |
| `Intervals.hpd` | the middle window instead of the narrowest | the shortest interval | it stopped being narrower than the percentile one |
| `Summary.of` | `lp__` dropped | the row names | `the rows are List(rate)` |
| `SamplerReport.lowEnergyChains` | the comparison reversed | 2 tests | a tiny step size was flagged and the funnel was not |
| `StanCsv.parse` | comments skipped only before the header | 4 of 8 | `row 1 has 1 values, the header has 8` |
| `StanCsv.render` | the timing block dropped | the four rules | `the trailing block is not the timing block` |
| `Draws.of` | raggedness no longer checked | the rectangle | `a chain of a different length was accepted` |
| `essTailParts` | `>` for the upper tail | the tied sample | `the upper tail is 1133.50, … rather than the inclusive 533.06` — **after the test was added** |
| `ess` | the floor on `tau` dropped | the antithetic chain | `the effective size is -344523.74 where the floor puts it at exactly 3000.0` — **after the test was added** |

**The two survivors, and what closed them.**

The **upper tail's inclusive comparison** is invisible on Stan's own chains, because the tail
diagnostic is the smaller of two effective sizes and there the *lower* tail is the smaller one.
`essTailParts` is now public and the suite asserts the upper half on a sample built to separate the
conventions: four chains of the integers 0 to 19, whose 95th percentile is exactly 18 — reached by
96 draws and exceeded by only 48. The inclusive convention gives 533 effective samples there and
the exclusive one 1133.

The **floor on `tau`** binds only for a sample whose autocorrelations are strongly negative, which
no posterior sample is. An antithetic chain is: alternating values with a little wobble, where the
unfloored estimate of the integrated time goes negative and the effective size comes out at
**-344 524**. The floor puts it at exactly `1000 log10(1000) = 3000`, and that equality is what the
test asserts.

### Known limits at the end of phase 5

1. **`testkit.Ess` is still there and still not the reference.** It sized the thresholds of phase 4
   and is now redundant: `Convergence.ess` is the validated one. Phase 6 should point the sampler
   suites at `diagnostics` and delete it; doing it here would have meant `inference`'s tests
   depending on `diagnostics`, which is backwards in the module graph. The two agree to a few per
   cent on the chains both see, which is all `testkit.Ess` was ever used for.
2. **No LOO, no power scaling** (plan §5.7's "model criticism"): §8.2 assigns them to phase 6b with
   the multilevel models, and they need `PointwiseLikelihood` on a model, which no model has yet.
3. **`Summary` has no `ess_tail`-based MCSE and no `mcse_sd` column.** Both are computed and tested
   in `Convergence`; the row carries the four columns the specification lists, and widening it is a
   change to a published case class that should wait for a reason.
4. **`LogDensityUnnormalized` is deferred a third time** (D-84), now with the arithmetic written
   down: of the order of ten tape nodes saved against the 89 a multilevel model costs.
5. **`StanCsv.render` writes placeholders** for the step size, the mass matrix and the elapsed
   time, because a `Draws` holds none of them. A file this library writes is therefore readable but
   not a faithful record of the run that produced it; carrying the tuning would mean `stan-io`
   depending on `inference` (D-80).
6. **No `Draws` from a `Chain` yet.** `Summary.of` takes chains and `StanCsv` takes `Draws`, and the
   bridge between them — which needs the sampler's seven columns — is written by hand in the
   manual's chapter 9 and in `check_cmdstan_csv.py`. It belongs in `effect`, with the writer.

## Phase 6 — the reference posteriors and simulation-based calibration

354 tests, 336 fast and 18 slow, up from 330 — 24 added by this phase, half of them slow. The 324
fast tests of phases 0 to 5 are untouched; no tolerance, seed or assertion was weakened, and the
register of encapsulated local mutation stays at 27 sites.

This is the phase where the library is compared against **Stan itself**, on nine models, rather
than against Stan's unit tests. Everything before it checked a piece; this checks the composition.

### The nine reference posteriors

Each Stan program was translated by hand into the Scala API, fitted with four chains of a thousand
warmup and a thousand kept samples, and compared parameter by parameter against the reference
summary posteriordb ships — ten chains of a thousand thinned draws of a long Stan run. The rule is
the collection's own: `|mean_ours - mean_ref| <= 4 sqrt(mcse_ours² + mcse_ref²)`, and the same for
the standard deviation.

| Posterior | What it exercises | Parameters compared | Result |
|---|---|---|---|
| `eight_schools-eight_schools_noncentered` | a multilevel model, a positive scale, a derived quantity | 10 | green |
| `arK-arK` | an autoregression of order 5 over 195 rows | 7 | green |
| `arma-arma11` | an error recursion over 200 steps | 4 | green |
| `garch-garch11` | a bound that depends on another parameter, a recursive likelihood | 4 | green |
| `kidiq-kidscore_momiq` | a plain regression, 434 rows | 3 | green |
| `earnings-logearn_height` | a regression on a log scale, 1192 rows, no prior at all | 3 | green |
| `dogs-dogs` | a logistic regression over 750 Bernoulli cells | 3 | green |
| `low_dim_gauss_mix-low_dim_gauss_mix` | ordered means, a two-component mixture over 1000 rows | 5 | green |
| `gp_pois_regr-gp_pois_regr` | a latent Gaussian process, Poisson counts, a **differentiated Cholesky** | 13 | green |

**52 parameters, every mean and every standard deviation inside the budget, and every one of our
own fits converged first** (R-hat below 1.01 and a bulk effective size above 100, asserted
separately so that a comparison against a chain that had not mixed would say so rather than report
a gap). The suite takes 3 minutes 15 and lives in `src/slow/scala`.

Before any of them is fitted, a fast suite checks the ports themselves: all nine load, every target
is finite at two points, **every gradient agrees with the dual-number oracle to 1e-12** — the
Gaussian process's Cholesky included — and central differences agree to 1e-5. And every name the
reference summaries report is produced by its port, as a parameter or as a derived quantity, so a
comparison cannot silently skip one.

Three of the references report a quantity the model does not carry as a parameter, and each is a
`derived`: `theta` of the non-centred eight schools, `beta1` of the GARCH, and `f` of the Gaussian
process.

### The two parameterisations of the eight schools

| Check | Result |
|---|---|
| the centred form produces divergent transitions | green: more than half a per cent of its transitions |
| the non-centred form produces almost none | green: under two in a thousand |
| and the first has more than ten times the second | green |
| `tau`'s bulk effective size more than doubles | green |
| the energy diagnostic improves | green |
| the two still agree on `tau`'s posterior mean | green |

The contrast is the point. A sampler that reported divergences everywhere would pass the first
check and fail the second, and one that reported none would fail the first.

### Simulation-based calibration

Three small generative models, 256 replications each, samples thinned to 63 per fit, ranks binned
into 16 of 4 possible values, against the 0.1 % point of chi-squared with 15 degrees of freedom —
37.697. Nine parameters in all, so the announced failure rate is about 1 run in 100.

| Model | Parameters | Result |
|---|---|---|
| a Gaussian location and scale, 12 observations | `mu`, `sigma` | green |
| a Gamma-Poisson rate, 20 counts | `rate` | green |
| four varying levels, non-centred | `mu`, `tau`, `z.1` … `z.4` | green |

And the machinery itself, in the fast suite: ranks in range and one per replication, a vector
parameter ranked value by value under CmdStan's names, the statistic exactly 0 on perfectly uniform
ranks and above 1000 on ranks piled in one bin, and `NaN` when the bins do not divide the ranks
evenly.

**The test that gives the rest their meaning**: a model whose density says `Gamma(3, 4)` while its
simulation draws from `Gamma(3, 1)` — a prior written twice and differently, which is the mistake
calibration exists to find — is rejected at the 0.1 % level in 48 replications, while the honest
version passes. Without that, a calibration suite that always passed would prove nothing.

### The stress suite

`sbt -Dbayes4s.stress=true slow:test`, which takes fresh seeds from the clock, doubles the warmup
and the samples of every posteriordb fit and raises the calibration to 512 replications:

```
18 tests, 0 failed, seed 1793910010875
  three calibrations                 16s, 10s, 34s
  nine reference posteriors          51s to 1:54min each
```

Green on a seed nobody chose, which is what the weekly workflow is for.

### Mutations tried

Eight, one per component of the phase, every one detected.

| Component | Mutation | Test that failed | What it said |
|---|---|---|---|
| `garch11` | the Jacobian of the dependent bound dropped | the GARCH comparison | `alpha1: mean 0.614213 against 0.567284, gap 0.046929 over a budget of 0.012738` |
| `arma11` | the first prediction is `mu` instead of `mu + phi mu` | the ARMA comparison | `mu: mean 0.004038 against 0.006915, gap 0.002877 over a budget of 0.000820` |
| `gaussianMixture` | the mixture weights not on the log scale | the mixture comparison | caught by the convergence guard: `Rhat 1.7320 and a bulk effective size of 6` |
| `dogs` | the two counts of past outcomes swapped | the dogs comparison | `beta.2: mean -0.209233 against -0.358265, gap 0.149032 over a budget of 0.004204` |
| `lowerFactor` | the factor transposed | the Gaussian process comparison | `alpha: sd 0.712680 against 0.792822, gap 0.080142 over a budget of 0.073788` |
| `Sbc.once` | the fit conditioned on the observed data, not the simulated | all three calibrations | `mu: chi-squared 1824.00 over 16 bins` |
| `Sbc.once` | the samples no longer thinned | the multilevel calibration | `z.1: chi-squared 40.13`, and the two easy models still passed |
| `uniformityChiSquared` | the expected count per bin off by one bin | the known-ranks test | `perfectly uniform ranks gave 0.941, expected exactly 0` |

Two are worth a line. The **mixture** mutation never reached the comparison: putting the weights on
the wrong scale makes the posterior bimodal in a way the sampler cannot explore, and the
convergence guard caught it with R-hat 1.73. That is the failure one wants to see — "your fit is
broken" rather than "your fit disagrees with Stan by 0.3".

The **thinning** mutation failed on one of the three models and not the other two, which is where
it should bite: autocorrelation is highest in the multilevel model, and `z.1` came out at 40.13
against a critical value of 37.697. A single easy model would have let it through, which is why
there are three.

### Known limits at the end of phase 6

1. **`testkit.Ess` is still there.** Phase 5 noted it is redundant now that `Convergence.ess` is
   validated; pointing `inference`'s suites at `diagnostics` would invert the module graph, so it
   waits for a module that sits above both — `effect`, in phase 11.
2. **A differentiable Cholesky is written in a model, not in the library** (D-85). Fine at eleven
   dimensions and the wrong approach at a hundred. If a later phase needs a Gaussian process of any
   size, a fused primitive with its own adjoint is the answer, and the phase-2 benchmark is the
   instrument.
3. **A parameter-dependent bound has no `Constraint`** (D-85). The workaround is exact and is two
   lines in a model, and appendix A of the manual documents it; a `Constraint` case that closed
   over another parameter would be a different design.
4. **No LOO, no power scaling, no correlated-effects posterior**: §8.2 assigns all three to phase
   6b, with `PointwiseLikelihood` and the multilevel test models.
5. **256 replications is a modest calibration.** Talts et al. use a thousand or more. At 256 with
   16 bins the expected count is 16 a bin, which catches a gross miscalibration — as the deliberate
   one shows — and would miss a subtle one. The stress run doubles it; a sharper check belongs with
   the models it would be about.
6. **The posteriordb comparison uses one seed per run.** Nine fits of four chains is 3 minutes, so
   a sweep over seeds is not something to put in a suite; the weekly stress run is the sweep, one
   fresh seed at a time.

## Phase 6b — multilevel models and model criticism

377 tests, 356 fast and 21 slow, up from 354 — 23 added by this phase. The 336 fast tests of
phases 0 to 6 are untouched; no tolerance, seed or assertion was weakened, and the register of
encapsulated local mutation stays at 27 sites.

Two defects in earlier phases' code were found by this phase and fixed, both of them silent: a
matrix parameter was an array of objects at run time (D-95) and simulation-based calibration could
not rank a derived quantity (D-96). Both are described below with the test that now pins them.

### Pareto-smoothed importance sampling, against ArviZ at 1e-6

`reference/multilevel.json` carries `az.loo` on 20 observations by 4000 samples from the posterior
— an invented regression with one deliberate outlier — and `az.psense` on two power-scaling cases.
There is no port to read as a specification for either, so the installed ArviZ source was read
instead (D-89).

| Check | Reference | Result |
|---|---|---|
| `elpd`, its standard error and `p_loo` | `az.loo` | green at 1e-6 relative |
| the 20 pointwise `elpd` values | `az.loo` | green at 1e-6 |
| the 20 Pareto shape parameters | `az.loo` | green at 1e-6 |
| `lpd` per observation, the log mean likelihood | computed by hand from the same array | green at 1e-12 |
| the outlier is the **only** flagged observation | `k > 0.7` | green; and at 100 samples the threshold moves to 0.5 |
| `compare` orders by `elpd` and gives the error of a difference | a model against itself shifted by a constant | green: the error is exactly 0 |
| the two sensitivities of both power-scaling cases | `az.psense` | green at 1e-6 |
| the cumulative Jensen-Shannon distance | computed by hand on known inputs | green at 1e-12 |
| the four readings of the two numbers | the rule of Kallioinen et al. | green |

The relative effective number of samples PSIS needs for its tail length is Stan's tail estimate,
which reproduces ArviZ's `r_eff` to every digit that matters on these arrays — 0.870602 and
1.013745, giving tail lengths of 203, 203, 203 and 188 (D-90). No second effective-size
implementation exists in the library.

### The correlated varying effects, against an independent posterior

posteriordb has no model with correlated varying effects, so the reference was simulated and
fitted elsewhere: twelve clusters, 138 observations, an LKJ(2) prior on the Cholesky factor, and a
reference of eight NumPyro chains of 4000 samples with no divergent transitions. This library fits
the same `model.stan` and is held to posteriordb's own rule —
`|mean_ours - mean_ref| <= 4 sqrt(mcse_ours² + mcse_ref²)`, and the same for the standard
deviation.

| Parameter | Reference mean, sd | Result |
|---|---|---|
| `mu[1]` | 1.3174, 0.1877 | green |
| `mu[2]` | -0.4953, 0.0899 | green |
| `tau[1]` | 0.5871, 0.1638 | green |
| `tau[2]` | 0.1475, 0.1052 | green |
| `rho` | -0.1367, 0.4077 | green |
| `sigma` | 0.6715, 0.0435 | green |

The two hard ones are the point of the exercise. `tau[2]` is pressed against zero — a posterior
mean of 0.1475 against a standard deviation of 0.1052 — and a scale near zero leaves the
correlation it multiplies barely identified, which is why `rho` has a standard deviation of 0.41 on
a parameter confined to (-1, 1). Both means and both standard deviations are inside the budget, and
our own fit's R-hat and bulk effective number of samples are asserted first so that a comparison
against a chain that had not mixed would say so.

### Simulation-based calibration on the two multilevel models

Added to phase 6's three, at the same thresholds: 256 replications, samples thinned to 63, ranks
binned into 16 of 4 possible values, against the 0.1 % point of chi-squared with 15 degrees of
freedom — 37.697. Eighteen parameters in all now, so the announced failure rate is about 1 run in
55.

| Model | Parameters | Result |
|---|---|---|
| correlated varying intercepts and slopes, six clusters | `mu.1`, `mu.2`, `tau.1`, `tau.2`, `sigma`, `rho` | green |
| a multilevel logistic regression, six clusters of ten | `mu`, `tau`, `beta` | green |

The first is the only model in the library whose generative part names a quantity the model
**derives** rather than declares, and ranking `rho` is a stronger test than ranking a parameter:
the LKJ prior, the factor's transform and that transform's Jacobian all have to agree at once. It
is also what exposed the defect in `Sbc` below.

### The sum of the pointwise log likelihood

The obligatory identity, asserted for six models — varying intercepts centred and non-centred,
complete pooling, no pooling, correlated effects, multilevel logistic — at an arbitrary point in
the unconstrained space rather than at the posterior, because it is arithmetic and holds
everywhere. Green at 1e-12 relative.

### Where leave-one-out prefers partial pooling

Two datasets of invented data, each simulated from a level above its clusters, and three models
fitted to each: complete pooling, no pooling, partial pooling.

| Dataset | Rejected extreme | Difference | Partial pooling |
|---|---|---|---|
| 20 clusters of 3, between-cluster spread 2.0, within 1.0 | complete pooling | -28.3 against an error of 4.5 (6.3 errors) | first; no pooling -1.8 ± 2.1 |
| 40 clusters of 2, spread 0.6, within 1.0 | no pooling | -11.7 against an error of 4.4 (2.7 errors) | first; complete pooling -3.6 ± 2.7 |

Twenty datasets were measured before these two were chosen, and the result is a fact about
cross-validation rather than about this library: a difference larger than its own error on **both**
sides does not happen, because the multilevel fit interpolates between the extremes and is
therefore always close to whichever one the data supports (D-98). One configuration — a spread of
half the within-cluster one over thirty clusters of three — rejects partial pooling by 2.3 errors,
because the data says the spread is zero and the hierarchical model still pays for estimating it.
The suite therefore asserts what is true of both datasets: partial pooling ranks first, and the
extreme that is wrong for the data is rejected. Chapter 11 says exactly that and no more.

Power scaling on a fitted model, on the same invented data under three priors on `mu`, with the
data averaging 2.155:

| Prior on `mu` | Prior sensitivity | Likelihood sensitivity | Reading |
|---|---|---|---|
| `Gaussian(0, 5)` | 0.0022 | 0.1066 | the data is doing the work |
| `Gaussian(10, 0.2)` | 0.4495 | 0.3490 | prior-data conflict |
| `Gaussian(2, 0.2)` | 0.0605 | 0.0978 | prior-data conflict |

The third row is the diagnostic's own limit, asserted deliberately so that nobody reads more into
the label than it says: a narrow prior is sensitive whether or not it conflicts, so one placed
where the data already is reads as a conflict too, and what separates the two is the size of the
number — 0.4495 against 0.0605 — rather than the label.

### Predicting a cluster that is not in the data

| Check | Reference | Result |
|---|---|---|
| the spread of a new cluster's effect | the closed form `Gaussian(m, sqrt(s² + t²))` | green, 40 000 samples, within 4 standard errors |
| the location and the scale stay paired, sample by sample | a posterior of a tight half and a wide half, whose crossed version has a different spread | green |
| a correlated new cluster's covariance | `diag(tau) L Lᵀ diag(tau)` | green within 4 standard errors |
| mismatched lengths | nothing rather than a wrong answer | green: an empty array |

The pairing test is the one worth having. A location drawn from one sample with a scale from
another produces plausible-looking numbers from a posterior nobody has, and only a posterior built
of two visibly different halves can tell the two apart.

### Two defects found in earlier phases

**A matrix parameter was an array of objects** (D-95). `ParamSpec.matrix` and
`ParamSpec.choleskyCorr` built their rows with `IArray.tabulate` inside a generic method, where the
compiler cannot know that a row of `A` is a `double[]`. Any model with a matrix parameter therefore
crashed with a `ClassCastException` the first time it was read at `Double` — in `pointwise`, in
`derived`, in predictive simulation — while being perfectly fine for gradients, which is why the
nine ported posteriors never noticed. Fixed by `Real[A].rowTag`, `matrixOf` and `rowsOf`; pinned by
a test that asserts the runtime class and then calls `matVec`, whose signature is the cast.

**Calibration could not rank a derived quantity** (D-96). `Sbc` read `Model.values`, which carries
the declared blocks only, so `rho` had no column, no sample counted as below the truth, and the
rank was zero in every replication: chi-squared 48 on four bins where uniformity gives about 3 — a
correct model reported as the worst failure the test can express, with nothing saying why. Fixed by
including `derived`, and a named quantity with no column is now a `BayesError.UnknownParameter`
rather than a rank.

### Mutations tried

Thirteen, every one detected. One per component, plus one for each defect above.

| Component | Mutation | Test that failed |
|---|---|---|
| `Pareto.fit` | the prior on the shape dropped, so `k` is the profile maximum | the 20 Pareto shapes, and the `elpd` with them |
| `Pareto.tailLength` | always `n/5`, dropping the `n r_eff > 225` branch | the `elpd` at 1e-6 **and** both power-scaling cases |
| `Pareto.smoothRightTail` | the cap at the largest observed weight removed | the 20 pointwise `elpd` values |
| `Loo.psis` | the plain `lpd` used as the `elpd`, i.e. no reweighting at all | the `elpd`, its error and `p_loo` |
| `Loo.compare` | the two errors added in quadrature instead of the pointwise differences | the constant-difference case, whose error must be exactly 0 |
| `Loo.kThreshold` | a fixed 0.7 instead of `min(1 - 1/log10(S), 0.7)` | the flagged-observation test at 100 samples |
| `PowerScaling.sensitivity` | one direction only, dropping the reflected sample | both cases at 1e-6 |
| `PowerScaling.diagnose` | the two one-sided readings swapped | the four readings, and the fitted-model suite with them |
| `Hierarchy.newGroup` | the first sample's scale used for every sample | the pairing test |
| `Hierarchy.newGroupCorrelated` | the scales dropped from `levels + scales ⊙ (L z)` | the covariance test |
| `MultilevelModels.pointwise` | the slope dropped from the correlated model's pointwise form | the sum against `logLikelihood` |
| `ParamSpec.matrix` | the rows built by `IArray.tabulate`, as before the fix | the runtime-class test |
| `Sbc` | the derived quantities left out of the posterior values | the correlated calibration, in one second, naming `rho` |

The last one is worth a line: with the guard added alongside the fix, the mutation no longer
produces a chi-squared of 48 after two and a half minutes of sampling — it produces
`UnknownParameter(rho)` in one second. A silent wrong answer became a named error, which is the
library's rule everywhere else.

### The stress suite

`sbt -Dbayes4s.stress=true slow:test`, fresh seed from the clock, doubled warmup and samples for
every reference posterior and 512 calibration replications: **21 tests, 2 failed, seed
1793914607957, 23 minutes 53**.

Phase 6b's own three are green on a seed nobody chose:

```
+ correlated varying effects, against its reference summary        1:55min
+ correlated varying intercepts and slopes, six clusters           23:33min  (512 replications)
+ a multilevel logistic regression                                 1:31min
```

The two failures are **phase 6's** posteriordb suite, and both are the convergence guard refusing
to compare rather than a disagreement with Stan. Neither is a regression: phase 6b changes nothing
on the path either model takes, the same code is green at the fixed seed, and the behaviour is
reproducible from the failing seed alone.

**`arma-arma11`, R-hat 1.59 with a bulk effective number of samples of 7.** Diagnosed: three of the
four chains agree with each other and with the reference — `phi` 0.957, `sigma` 0.166 — and the
fourth sits at `phi` -1.28, `theta` +1.08, outside the stationary region, with a step size of
0.00003 and the maximum tree depth hit on 1858 of its 2000 transitions. A uniform(-2, 2) starting
point — Stan's own default, and this library's `Init.Uniform(2.0)` — landed it where the ARMA
recursion explodes and the likelihood is nearly flat; warmup then adapted the step size down by
four orders of magnitude instead of escaping, and the chain never left.

Measured over ten seeds at the ordinary settings: **two of ten runs have exactly one stuck chain of
four**, so roughly one chain in twenty. The other eight runs put all four chains at `phi` 0.957
with R-hat below 1.002. The suite reports this correctly and loudly, which is the behaviour one
wants from the guard; what it reveals is a limit of the initialisation, not of the sampler, and the
fix belongs to whichever phase revisits `Init` rather than to a tolerance here.

**`gp_pois_regr`, R-hat 1.025 on `alpha` and 1.015 on `rho`**, with 155 and 412 effective samples.
A different and milder thing: the latent Gaussian process mixes slowly along the ridge where a
larger `alpha` trades against a longer `rho`, and at this seed four chains of 4000 did not quite
reach 1.01. No chain is stuck and the means are in the right place; the honest reading is that this
posterior needs more samples than the stress settings give it.

### Known limits at the end of phase 6b

1. **Leave-one-out with one observation per cluster is the worst case for the smoothing**, and the
   eight schools of chapter 4 show it: three of the eight come out above the 0.7 threshold, because
   leaving an observation out removes most of what identified its own parameter. The library reports
   this correctly and the chapter says what to do — refit, or cross-validate a whole cluster at a
   time. Leaving out a cluster at a time is not implemented.
2. **No moment matching and no mixture estimators.** When `k` is above the threshold the answer is
   to refit, not to patch the weights. `loo` in R offers both; this library offers the diagnostic
   and the honest advice.
3. **`PowerScaling.analyse` scales the whole prior**, not one component of it. Scaling a single
   prior would need the model to decompose `logPrior` by parameter, which `Model` does not do;
   the reading for a parameter is therefore the sensitivity of its marginal to the whole prior.
4. **`Loo.psis` asks the caller for the relative effective number of samples.** It cannot compute
   it itself without being handed the samples by chain, and the shape it takes — observations by
   samples — has lost that structure. The two suites and chapter 11 show the four lines that
   compute it; a helper that takes chains would be a better interface and is deferred.
5. **`Init.Uniform(2.0)` leaves about one ARMA chain in twenty outside the stationary region**, and
   it does not come back. Found by this phase's stress run and quantified above; it is phase 6's
   suite and Stan's own default, so nothing here was changed for it, and it is reported to the
   maintainer as the one thing phase 6b found that it did not fix. Accepted on 6 November 2026: the
   weekly stress workflow will therefore fail roughly one Monday in five, the seed stays random
   because a pinned one would defeat the job, and the fix waits for the phase that next revisits
   `Init`. A red stress run is to be read, not rerun.
6. **The correlated calibration is the slowest test in the repository**: 2 minutes 24 at 256
   replications and 23 minutes 33 at 512. Six clusters is already the reduced model; the cost is
   256 separate fits of an 18-parameter posterior, and the only real lever is the number of
   replications, which is what sets the test's power.

## Phase 7 — simulating futures

437 tests, 416 fast and 21 slow, up from 377 — 60 added by this phase, all fast. The 356 fast tests
of phases 0 to 6b are untouched; no tolerance, seed or assertion was weakened, and the register of
encapsulated local mutation stays at 27 sites.

This phase has **no reference fixture**, and that is a property of what it delivers rather than a
gap: every claim here is a closed form, a distributional property, a law, or a case small enough to
work out by hand. Where a number comes from a simulation it is compared against its own analytic
mean and variance at four standard errors.

### The ensemble and its monoid

| Check | Reference | Result |
|---|---|---|
| the shape is validated once, at construction | ragged, short and over-long matrices | green |
| `total` sums over keys, sample by sample | a 2-by-3-by-2 case by hand | green |
| `total` after `regroup` equals `total` | three regroupings: collapsing, renaming, partitioning | green |
| keys mapped together are summed, not overwritten | the collapsing case | green |
| the commutative monoid's laws | `CommutativeMonoidTests`, 19 properties | green |
| ensembles of different horizons sum over the covering run | January-to-April by hand | green |
| the two groupings of a sum agree to a few ulp, and not exactly | measured against `ulp` | green |

The monoid is lawful for ensembles of **different shapes**, which it has to be to be a monoid at
all (D-99), and the law check uses an equality that allows a few ulp while the published `Eq` stays
exact. The reason is arithmetic: the instance is associative over the reals, and `Double` addition
is not. The gap was measured at 1.776e-15 — exactly `ulp(10.0)` for cells drawn from (-5, 5) — and
the suite now asserts that it is bounded by a few ulp rather than claiming it is zero.

### Why quantiles are computed last

The criterion asks for the sum per sample against the sum of quantiles. The case is built so the
two cannot coincide: two keys perfectly anti-correlated across 100 samples, so their total is
constant.

| Quantity | Value |
|---|---|
| the 90th percentile of the total | 99 (the constant itself) |
| the sum of the two keys' own 90th percentiles | 178 |

A library that added quantiles would report 178 for a quantity that never leaves 99. The manual's
chapter 6 shows the same contrast on a four-sample case: 60.5 against 40.0.

### One simulated future, and a run of them

| Check | Result |
|---|---|
| a rule with an offset places each contribution at close plus offset | green, by hand |
| a contribution falling outside the run is dropped, not clamped or wrapped | green |
| an event closing outside the run contributes nothing | green |
| two events in one future add, period by period | green |
| the amount and its parts come from the same template in the same future | green, exactly |
| a run of futures equals the monoid sum of the futures, each in its own row | green, cell for cell |
| the same seed gives the same futures, a different one does not | green |
| a future computed alone equals the same future computed in a run | green |

The correlation test is the one that would be hard to notice failing. In every future the parts sum
to exactly three quarters of the size, because they are shares of the same drawn number; had the
simulator drawn the size in one future and the parts in another, the identity would break while
every mean stayed plausible and every interval came out too narrow.

### The AR(1) projection and the trend

| Check | Reference | Result |
|---|---|---|
| the mean at each of six horizons | `phi^h z_last` | green, 4 standard errors, 20 000 futures |
| the variance at each of six horizons | `tau² (1 - phi^2h)/(1 - phi²)` | green, 4 standard errors |
| the variance at horizon 40 | the stationary `tau²/(1 - phi²)` | green, 4 standard errors |
| a projection with no noise | `z_last phi^h`, exactly | green at 1e-15 |
| totality | `NaN` in gives `NaN`; `abs(phi) > 1` explodes as documented | green |
| the trend | `amount exp(kappa years)`, and two years is one year squared | green at 1e-14 |
| whole months as years, forwards and backwards | `months/12` | green at 1e-15 |
| draws paired with futures cyclically | `index mod draws` by hand | green |

### Thinning, the triangle and the exposure

| Check | Reference | Result |
|---|---|---|
| every cell's mean | `rate × share`, 20 cells, 4000 replications | green, 4 standard errors |
| a cell's variance equals its mean | the Poisson property, 20 000 replications | green, 4 standard errors |
| the first period's factor applies to the first delay only | by hand | green |
| right censoring keeps `m + k <= c - 1` | all 20 cells enumerated | green |
| censoring intersected with left truncation | the band `1 <= m + k <= 2`, by hand | green |
| an arbitrary mask with a hole in it | summed visible shares | green |
| the censored triangle's exposure | **`F(c - 1 - m)` per period, and the sum over periods** | green at 1e-14 |
| corrected counts | `observed / exposure` | green at 1e-14 |
| a period below the visibility threshold | `NaN`, not a number and not the raw count | green |

### The Bayesian bootstrap

| Check | Reference | Result |
|---|---|---|
| the weights are positive and sum to one | exactly, over 20 000 sets | green within 1e-12 |
| each weight's mean | `1/n` | green, 4 standard errors |
| each weight's variance | `(1/n)(1 - 1/n)/(n + 1)` | green, 4 standard errors |
| a pool draws in proportion to its weights | the multinomial standard error | green, 4 standard errors |
| **weights held over the horizon give wider futures than weights redrawn per period** | the spread of 2000 futures' own averages | green, the held spread over 1.5 times the redrawn one |
| a stratum below the minimum size falls back on the whole pool | the global share of the rare item | green, 4 standard errors |
| a stratum above it mixes at `n/(n + k0)` | `0.2 × 2/42` | green, 4 standard errors |
| the mixing weight | `n/(n + k0)`, and its three edges | green |

The fifth row is the one §5.8 is emphatic about and the only one that could not be checked by
reading the code. Both ways of drawing the weights give the same mean; what differs is the spread,
and holding one opinion per future gives the wider — which is the whole reason for the rule.

### The model's error term

| Check | Reference | Result |
|---|---|---|
| `eta` has mean 0 and variance `sigma²` | 40 000 draws | green, 4 standard errors |
| the perturbed rate's mean | `rate exp(sigma²/2)` | green, 4 standard errors |
| its median | `rate`, unchanged | green, 4 standard errors |
| the inflation at `sigma = 0.3` | 1.0460279 | green at 1e-6 |
| two error terms applied in turn | one term of their sum | green at 1e-14 |

The mean and the median are asserted separately on purpose. A multiplicative error leaves the
median where it was and raises the mean by 4.6 % at this sigma, which is a bias nobody meets on
purpose; the documentation says so because the library does not silently centre it (D-103).

### Mutations tried

Sixteen, one or two per component, every one detected.

| Component | Mutation | Test that failed |
|---|---|---|
| `Periods.cover` | the later start kept instead of the earlier | the covering-run test |
| `Ensemble.combine` | the period shift ignored, so a run is placed at offset 0 | the different-horizons test |
| `Ensemble.quantiles` | the quantile taken across periods instead of samples | the sum-against-quantiles test |
| `Ensemble.regroup` | keys mapped together overwrite instead of adding | the total and the regroup tests |
| `SimulatedFuture.add` | the close offset dropped | the close-plus-offset test |
| `SimulatedFuture.add` | a late contribution clamped to the last period | the dropped-not-clamped test |
| `Simulator.run` | every future given the same seed | the determinism and monoid-equality tests |
| `Ar1.project` | the decay towards zero dropped | the mean and variance at six horizons |
| `TrendScaling.at` | clamped to the last draw instead of cycling | the cyclic-pairing test |
| `PoissonThinning.means` | the first-period factor applied to every delay | the means test |
| `ReportingTriangle.rightCensored` | off by one: `m + k <= current` | the censoring and intersection tests |
| `ReportingTriangle.correctedCounts` | the visibility threshold ignored | the corrected-counts test |
| `BayesianBootstrap.weights` | equal weights instead of Dirichlet | the weight moments **and** the held-over-horizon test |
| `BayesianBootstrap.mixingWeight` | the prior and the size swapped | the mixing tests |
| `StratifiedPools.draw` | the stratum's own pool always used, never the global | the fallback test |
| `ModelError.applied` | the error added instead of multiplying | the median-and-mean test |

Two are worth a line. Replacing the Dirichlet weights by equal ones failed the moment test, as it
should, **and** the held-over-horizon test — because equal weights carry no opinion at all, so
holding them costs nothing and the two spreads coincide. And clamping a late contribution into the
last period failed the placement test rather than only the dropping test, which is the right
failure: clamping moves a contribution that belongs nowhere into a period that is reported.

### Known limits at the end of phase 7

1. **Aggregating an ensemble's months into quarters is not here.** `Periods.quarterOf` says which
   quarter a period belongs to and §5.8 gives the summing to `forecast` in phase 9; adding a second
   aggregation API now would mean guessing its shape before its only caller exists (D-104).
2. **`ModelError`'s `sigma_err` is not calibrated.** The grid over past dates needs a scoring rule
   and a backtest, which arrive in phase 8 (D-103). The term itself is here and tested.
3. **No mean-preserving variant of the error term.** `exp(eta - sigma²/2)` is one line for a caller
   who wants it; which of the two a forecast should use depends on the forecast, so the library
   documents the 4.6 % rather than choosing.
4. **`Ensemble` is dense.** A key with a value in one period of sixty still carries sixty doubles
   per sample. Nothing in this phase's use needs sparsity, and a sparse representation would make
   the monoid harder to get right, which is the part that must not be wrong.

## Phase 8 — scoring a forecast

456 tests, 435 fast and 21 slow, up from 437 — 19 added by this phase, all fast. The 416 fast tests
of phases 0 to 7 are untouched; no tolerance, seed or assertion was weakened, and the register of
encapsulated local mutation stays at 27 sites.

### The score, against properscoring at 1e-12

`python/scoring.json` carries properscoring 0.1's own values: six sample CRPS cases from one
sample to a thousand, one case with ties, and four Gaussian closed forms. The fixture gives both
the sample form and the energy form for each case, so the phase's own criterion — that the two are
one function — has a reference on both sides.

| Check | Reference | Result |
|---|---|---|
| the sample CRPS, six cases from n = 1 to n = 1000 | `properscoring.crps_ensemble` | green at 1e-12 relative |
| the tied case, three equal samples and one apart | properscoring, 0.1875 | green **exactly**, to the bit |
| the Gaussian CRPS, four cases | `properscoring.crps_gaussian` | green at 1e-12 relative |
| the sorted form against the energy form | each other, over 100 generated cases with ties forced | green at 1e-12 |
| a point forecast | the distance `abs(x - y)` | green at 1e-15 |
| spread costs, and being wrong costs more | three forecasts ordered by hand | green |
| scale equivariance and order independence | the score doubled with the scale | green at 1e-13 |
| the sample score against the closed form | 200 000 Gaussian draws | green, 4 standard errors |
| totality | empty, `NaN`, negative scale, infinite sample | green |

The tied case is the one worth singling out. The sorted form weights each sample by its position,
so ties are exactly where an implementation that deduplicated or used a strict inequality would
drift — and the arithmetic there is exact, so the assertion is bit equality rather than a
tolerance.

`Special.phi` was added to `core` for the Gaussian closed form (D-107), written as
`0.5 erfc(-x/sqrt 2)`: at `x = -10` the value is 7.6e-24 and the `erf` form loses every digit of
it. The four Gaussian cases agree with properscoring to the last bit.

### Calibration

| Check | Reference | Result |
|---|---|---|
| the transform, above, below, in the middle, on a sample | by hand | green at 1e-15 |
| a tie counts as half a sample | the randomised transform's expectation | green |
| the bias at both ends and in the middle | scoringutils' `1 - 2 pit` convention | green at 1e-15 |
| the interval score, inside, below, above | Winkler's definition, by hand | green at 1e-15 |
| **the interval score against the two pinball losses** | `2/alpha (rho_{alpha/2} + rho_{1-alpha/2})`, written independently, 21 cases | green at 1e-13 |
| coverage, boundaries included | by hand | green |
| a calibrated forecast's transform histogram | chi-squared over 10 bins against the 0.1 % point of 27.877, 9 degrees of freedom | green |
| a forecast shifted by one standard deviation | the same test, which must reject it | green |

The pinball identity is the strongest check here because the two sides share no code: Winkler's
interval score is `2/alpha` times the sum of the pinball losses at the interval's own quantile
levels, and the test writes the pinball loss out from its definition.

### The backtest and the standard error that is wrong

| Check | Reference | Result |
|---|---|---|
| each date's row | the pieces called directly | green at 1e-15 |
| the interval comes from the forecast's own type-7 quantiles | `Quantile.quantile` at 0.05 and 0.95 | green |
| the aggregates, and an empty backtest | `NaN` rather than zero | green |
| **the block bootstrap on independent dates** | the ordinary `sd/sqrt(n)` | green, within a quarter either way |
| **the block bootstrap on AR(1) dates, phi = 0.9** | more than 1.5 times the ordinary error | green |
| blocks of one reproduce the ordinary error's blindness | under 1.25 times it | green |
| determinism by seed, and the degenerate cases | | green |

The two bootstrap rows are the point of the component. On a run of 400 scores with an AR(1)
correlation of 0.9 the true standard error of the mean is inflated by about 4.4 over the
independent one; resampling single dates sees none of that, and blocks of 40 see most of it. Both
directions are asserted, so neither a bootstrap that always inflates nor one that never does would
pass.

### Mutations tried

Thirteen, one or more per component, every one detected.

| Component | Mutation | Test that failed |
|---|---|---|
| `Crps.ensemble` | the sorted coefficient off by one | the fixture and the tied case |
| `Crps.ensemble` | the unbiased `1/(n(n-1))` estimator instead of `1/n²` | the fixture at 1e-12 |
| `Crps.energy` | the half dropped from the pairwise term | the form-equality property |
| `Crps.gaussian` | the `1/sqrt(pi)` constant dropped | the Gaussian fixture and the sample convergence |
| `Special.phi` | the sign of the argument flipped | the Gaussian fixture |
| `Calibration.pit` | a tie counted in full instead of half | the transform test |
| `Calibration.bias` | the sign flipped | the bias test |
| `Calibration.intervalScore` | `1/alpha` instead of `2/alpha` | the hand cases **and** the pinball identity |
| `Calibration.coverage` | the boundary excluded | the coverage test |
| `Calibration.pitHistogram` | a transform of exactly 1 falls past the last bin | the histogram test |
| `Backtest.run` | the interval taken from the extremes instead of the quantiles | the per-date test |
| `Scored.covered` | the boundary excluded | the aggregates test |
| `blockBootstrap` | single dates resampled whatever the block size | the correlated-dates test |

The estimator mutation is the one that justifies D-108: swapping `1/n²` for `1/(n(n-1))` is a
defensible choice in the abstract and a disagreement with every published score in practice, and
the fixture catches it at the first case.

### A flaky test from phase 5, found by this phase's CI run and corrected

Phase 8's push turned CI red on a test phase 8 does not touch:
`ConvergenceSuite`, "independent draws give R-hat near 1 and an effective size near the count",
with `the tail effective size is 3397.3159941619515 for 4000.0 independent draws` at seed 134.

Nothing was wrong with the diagnostics. 3397 of 4000 is an honest draw from that estimator's own
sampling distribution; what was wrong was the test's band. Its comment derived ±15 % from
`sqrt(2/total)` — 2.2 % here — which is the asymptotic standard error of an effective size when the
autocorrelation structure is known. This estimator truncates by Geyer's rule on four chains of a
thousand, and the tail version works on quantile-indicator series with about two hundred draws per
tail, so both are noisier than that formula allows.

The whole seed population the property samples from was enumerated — all 400 seeds, four chains of
a thousand independent standard Gaussians each:

| Statistic | min | 1st percentile | 5th | median | max | outside ±15 % |
|---|---|---|---|---|---|---|
| basic | 0.837 | 0.874 | 0.911 | 0.983 | 1.090 | 3 of 400 |
| bulk | 0.845 | 0.885 | 0.917 | 0.987 | 1.092 | 2 of 400 |
| tail | 0.838 | 0.866 | 0.887 | 0.971 | 1.048 | 3 of 400 |

Six seeds fail on at least one statistic — 31, 134, 203, 249, 371, 386 — so a run of ten seeds
failed about **one time in seven**. Every earlier CI run passed by luck.

Two changes, and the second matters more than the first:

1. The band is now **±20 %**, which contains all 400 seeds for all three statistics with margin and
   is still far from the factor of two that would make the check vacuous. The comment in the test
   cites this measurement rather than the formula that did not apply.
2. The six extreme seeds are **pinned in a test of their own**. The property samples ten of four
   hundred, so the cases it exists to cover were visited about one run in forty; they are now
   checked every run. Reverting the pinned test's band to ±15 % fails it immediately — on seed 31,
   whose basic effective size is 0.840 — which is how the fix was verified to have teeth.

No seed was changed and nothing was skipped. The band was corrected because the test asserted
something false 14 % of the time, and the correction is recorded here with the numbers that justify
it.

### Known limits at the end of phase 8

1. **The backtest does not refit.** Producing one forecast per origin, with no fit having seen its
   own target, needs a model and a sampler that `scoring` deliberately does not depend on (D-111).
   What is here is the scoring half; the protocol belongs to the caller, and the worked example
   arrives with the nowcasting model in phase 10.
2. **`sigma_err` is still uncalibrated.** Phase 7 deferred the grid over past dates to the phase
   that has a backtest. The backtest is now here, but the model whose error it would calibrate is
   not, so the pairing happens in phase 9 or 10.
3. **No weighted or threshold-weighted CRPS.** Scoring the tail harder than the body is a real
   need for a forecast used for capacity, and the weighted variants are a different estimator
   rather than a weight applied afterwards; nothing in the plan asks for them yet.
4. **The transform is the non-randomised one.** For a heavily discrete forecast the mid-rank
   convention is an approximation to the randomised transform rather than a draw from it, so a
   histogram over very few distinct values will still look lumpy. Said here rather than discovered.

## Phase 9 — the nowcasting models

494 tests, 470 fast and 24 slow, up from 456 — 38 added by this phase, 34 fast and 4 slow. The 435
fast tests of phases 0 to 8 are untouched; no tolerance, seed or assertion was weakened, and the
register of encapsulated local mutation stays at 27 sites.

This is the phase where everything built so far is used at once: a model written against `Real[A]`,
differentiated by the tape, sampled by NUTS, summarised by the diagnostics, simulated forward with
phase 7's ensemble machinery, scored by phase 8's rules, and checked three ways.

### The components, on their own

| Check | Reference | Result |
|---|---|---|
| the rate is level plus trend plus wobble | written out by hand | green at 1e-14 |
| the wobble starts from the **stationary** distribution | the first and last month's variances agree, 20 000 draws | green within 6 % |
| a projection continues the recursion at the times it is given | the trend line, no noise | green at 1e-14 |
| a projection carries the last wobble forward | the decay from a planted final innovation | green |
| the delay probabilities sum to one, at five drifts | exactly one | green at 1e-14 |
| the delay probabilities are the differences of the cumulative logistic | written out by hand from §5.10 | green at 1e-13 |
| a positive drift makes reporting slower | the sign convention | green |
| decreasing cut points give `NaN` | not an ordering | green |
| both components name their parameters as CmdStan would | the layout | green |

### The count model

| Check | Reference | Result |
|---|---|---|
| the data refuses a shape it cannot hold | four malformed cases | green |
| the visible cells are the reported triangle | enumerated by hand, 18 of 24 cells | green |
| the log likelihood is the Poisson sum over the visible cells | written out by hand | green at 1e-13 |
| **an unreported cell cannot change the answer** | a thousand planted in an invisible cell | green, bit for bit |
| a reported cell must change it | fifty added to a visible one | green |
| the gradient against the dual-number oracle | `Dual.gradient` | green at 1e-11 |
| the gradient against central differences | `GradientCheck` | green at 1e-5 |
| the derived rate is the component's | `exp(logRate)` | green at 1e-14 |
| the generative part's cell means | `lambda[m] p[m](k)`, 24 cells, 3000 replications | green, 4 standard errors |

The unreported-cell test is the one that matters most. A nowcast exists because the recent periods
are incomplete, so a likelihood that treated an unseen cell as a zero would be confidently wrong
about exactly the months anyone cares about. The fixture carries zeros in those cells — as such
data usually arrives — which is why the test plants a thousand in one of them instead of trusting
the zeros.

### Against NumPyro, on the whole assembly

`reference/build_nowcast_reference.py` simulates eighteen months of fictional data from known
parameters and fits the same model in NumPyro: 8 chains, 3000 warmup, 4000 draws, target acceptance
0.99. One divergent transition in 32 000, worst R-hat 1.0005, smallest bulk effective size 8 076 —
and the count is recorded in the fixture rather than reseeded away (D-123).

**All 34 parameters agree within 4 combined MCSE**, first run: `a`, `b`, `phi`, `tau`, `cut0`,
`beta`, the four cut-point increments, and all 24 monthly rates. Including the rates is what makes
it a test of the whole assembly rather than of six scalars: a model that got the average level
right and the shape wrong would pass on the scalars and fail on `lambda[1..24]`.

Writing that reference produced the phase's sharpest finding. With a **uniform** prior on `phi` the
same run reported **173 divergent transitions**; with a Beta(2, 2) on `(phi + 1)/2` it reported
**one**. The Beta's density is proportional to `1 - phi²`, which exactly cancels the stationary
scale's `1/sqrt(1 - phi²)` blow-up at a unit root (D-117). Both the Scala component and the script
carry it, because the comparison is only meaningful if the two are the same model.

### Recovery, calibration and the predictive checks

| Check | Threshold | Result |
|---|---|---|
| 90 % intervals cover about 90 % of the time | 240 intervals, 4 standard errors of `sqrt(.9·.1/240)` | green |
| no single parameter covers far less often | above 70 % over 40 replications | green |
| the reduced model is calibrated | 40 replications, 4 bins, the 0.1 % point of chi-squared with 3 df, 16.266 | green on all six parameters |
| the posterior predictive covers its band | at most 2 of 11 periods outside the 90 % band | green |
| a series shifted far away is rejected | at least 9 of 11 outside | green |

The two slow checks look similar and fail for different reasons. Coverage asks whether one interval
is honest; calibration asks whether the whole rank distribution is, which is what catches a prior
written twice and differently, a wrong Jacobian, or a sampler that cannot explore the model.

### The shrinkage pair

| Check | Reference | Result |
|---|---|---|
| the shrinkage weight | `worked_examples.json`, 8 cases | green at 1e-15 |
| the fitted dispersion | the maximum of the marginal likelihood on a 40 001-point grid | green: the fit's likelihood is at least the grid's best, and `alpha` agrees to 2e-3 |
| a group's posterior | the conjugate `Gamma(alpha + n, alpha + e)` | green at 1e-14 |
| a small group borrows more than a large one | the weights' order | green |
| **the mixture over the dispersion** | the law of total variance, by hand | green at 1e-14 |
| at 200 groups the two methods agree | widths within 5 %, means within 5 % | green: 0.1 % and 1.6 % |
| at 4 groups the dispersion is barely identified | relative spread above 2, and 10× the 200-group one | green: 4.3 against 0.13 |
| at 4 groups the two methods part | worst group differs by over 10 % | green: 12 % to 58 % |

Measured over six seeds at each size before the thresholds were written:

| groups | width ratio | worst mean gap | `alpha`'s relative spread | `alphaHat` |
|---|---|---|---|---|
| 200 | 0.999 to 1.000 | 0.9 % to 1.6 % | 0.125 to 0.139 | 3.3 to 4.6 |
| 20 | 1.001 to 1.004 | 4.2 % to 9.9 % | 0.396 to 1.954 | 3.1 to 5.8 |
| 4 | 0.852 to 1.148 | 12 % to 58 % | 4.261 to 12.577 | 1.3 to 10.8 |

The suite deliberately does **not** assert that the full fit is wider at four groups: it is not,
in two of six seeds, and the reason is that the shortcut's width is set by wherever its point
estimate landed (D-126). "More uncertain" is a claim about the dispersion, and that is what is
asserted.

### The forecast and the sizes

| Check | Reference | Result |
|---|---|---|
| one future per sample, times `perDraw` | 5 and 15 | green |
| the expected total | the rate times the mean size, 2000 futures | green, 4 standard errors |
| the partial first period | 70 instead of 80 at a factor of 0.5 | green, 4 standard errors |
| a delay moves contributions later | the first two periods stay empty | green |
| log-normal sizes' mean | `exp(mu + s²/2)`, 40 000 draws | green, 4 standard errors |
| the cap truncates and leaves the median | the median is `exp(mu)` | green within 2 % |
| the same seed forecasts the same futures | | green |
| the regression recovers its parameters | 400 rows, four posterior standard deviations | green |
| the mean amount exceeds the median | `exp(s²/2)`, 8.3 % at `s = 0.4` | green at 1e-14 |

### Two defects found, one fixed and one reported

**Fixed, in this phase's own model.** `GammaPoissonHierarchical` left `alpha` unbounded, and a
chain wandered to `alpha = 3e21`, where the step-size adaptation froze it at `10^-32` with an
acceptance statistic of `NaN`: the fit reported one chain at 3e21 and another, correct, at 3.9, and
the comparison came out as nonsense rather than as a failure. `alpha` is now bounded at `10^6` —
four orders of magnitude above anything the data can identify and seven below the breakdown below
— and the fit went from 1 minute 47 to 1 second (D-118).

**Reported, not fixed.** The reason that region is fatal is that the negative binomial's gradient
degenerates there. Measured on `LogMass.negativeBinomial(1, alpha, alpha/10)` differentiated with
respect to `log alpha`:

| `log alpha` | value | gradient | what it should be |
|---|---|---|---|
| 10 | -7.6955994936 | -1.8e-3 | correct |
| 20 | -7.6974148246 | -6.6e-7 | correct |
| 30 | -7.6974149070 | **+1.2e-2** | wrong sign, about 1e-13 |
| 38, 41, 50 | -7.6974149070 | **-2.0 exactly** | about 0 |

The value is right to the last bit everywhere — it converges to `Poisson(1 | 10)` exactly, which is
what `Real[A].lbeta` exists for. It is the derivative that collapses, and a wrong constant gradient
is worse than a wrong value because the sampler follows it. That is phase 2's component and fixing
it means reworking the adjoint of a cancelling expression, so phase 9 keeps its models away from
the region and records the measurement (D-119).

### Mutations tried

Nineteen, one or more per component, every one detected — but one needed a new test first.

| Component | Mutation | Test that failed |
|---|---|---|
| `Ar1Trend.wobbleOf` | the first value starts at zero, not stationary | the rate and the stationary-variance tests |
| `Ar1Trend.wobbleOf` | the persistence dropped from the recursion | the same two |
| `Ar1Trend.project` | the wobble restarted at zero | the carry-forward test |
| `Ar1Trend.logPrior` | the Beta persistence prior dropped | **the NumPyro comparison** |
| `OrderedLogitDrift.cutsOf` | the first cut dropped from the later ones | the cumulative-logistic test |
| `OrderedLogitDrift.logPmf` | the delay used as the category, off by one | the sum-to-one and difference tests |
| `OrderedLogitDrift.logPmf` | the drift's sign flipped | the drift-direction test |
| `DelayCountModel.logLikelihood` | every cell read, not only the visible ones | the hand sum and the unreported-cell test |
| `DelayCountModel.logLikelihood` | the delay probability dropped from the rate | the hand sum |
| `DelayCountModel.generative` | a flat delay distribution when simulating | the cell-means test |
| `Checks.arrivals` | reports counted in the creation period | the diagonal test |
| `PredictiveSample.band` | the wrong tail probabilities | the band test |
| `GammaPoissonShrinkage.fit` | the likelihood maximised by minimising it | the grid test |
| `GammaPoissonShrinkage.weight` | the prior and the exposure swapped | the fixture and the borrowing test |
| `GammaPoissonHierarchical.theta` | the uncertainty about the dispersion dropped | **survived at first** |
| `Forecast.run` | the partial first period ignored | the expected-total test |
| `Forecast.run` | the reporting delay not added to the close | the delay test |
| `SizeModel.logNormal` | the cap ignored | the cap test |
| `LogNormalTrendRegression.meanAmount` | the lognormal correction dropped | the mean-against-median test |

The survivor is the instructive one. Dropping the between-draw variance from `theta` removes
exactly what distinguishes the full fit from the shortcut — and it survived, because the suite
asserted the two **agree** at 200 groups (where the term is negligible) and deliberately did not
assert a width ordering at 4 (D-126). The fix was a new test that is exact rather than statistical:
the law of total variance on four hand-chosen dispersion values, asserted at 1e-14. It now fails
the mutation immediately.

### Known limits at the end of phase 9

1. **One group only.** §5.10 describes partially pooled groups, group-varying cut points, and two
   populations sharing a level; the phase's own criterion is the one-group model, and that is what
   is here. The component traits take `groups` throughout, so the structure is in place.
2. **`sigma_err` is still uncalibrated.** Phase 7 deferred it to the phase with a backtest and
   phase 8 delivered the backtest; the grid over past dates now has everything it needs and is a
   phase-10 job, since it belongs with the worked example rather than with the model.
3. **The negative binomial's gradient above `10^13`** (D-119). Reported, measured, worked around.
4. **`LocalLevel` and the tightened-trend variant are not written.** §5.10 lists them as
   `RateComponent` alternatives; one component satisfies the phase, and a second would be tested
   against nothing the first does not already cover until there is a model comparison to run.

## Phase 10 — the group structures

520 tests, 486 fast and 34 slow, up from 494 — 26 added by this phase, 16 fast and 10 slow. The 470
fast tests of phases 0 to 9 are untouched; no tolerance, seed or assertion was weakened, and the
register of encapsulated local mutation stays at 27 sites.

Phase 9 delivered the one-group count model. This phase answers the question a second group asks:
what does a quiet group fall back on?

### The components

| Check | Reference | Result |
|---|---|---|
| a hierarchical rate is the group's own level and trend plus the shared wobble | written out by hand | green at 1e-14 |
| centred and non-centred describe the same rate | each other, four groups | green at 1e-14 |
| the two parameterisations have **different priors** | a flag that changed nothing would do nothing | green |
| two populations have their own levels and share the spread | by hand, four groups in two populations | green at 1e-14 |
| a population index out of range is refused | `Ar1TrendHierarchical.of` | green |
| a local level is a random walk | one innovation, then none | green at 1e-14 |
| a local level's projection does not drift | the last level, 8 horizons, 20 000 seeds | green, 4 standard errors |
| its spread grows with the horizon | more than doubling over 8 periods | green |
| a per-group delay shifts every cut point together | the common delay plus an offset | green at 1e-14 |
| the offsets are drawn around their **own fitted spread** | the Gaussian written out by hand | green at 1e-13 |
| every component is total in its time axis and its group | `NaN` past the innovations | green |

The offsets' prior scale deserves its row. `delta[g] ~ Gaussian(0, sigmaCut)` with `sigmaCut` a
parameter is what lets the data say how much the groups' reporting speeds differ, and replacing it
with a constant is invisible to calibration — a parameter whose posterior equals its prior still
has uniform ranks. That mutation survived the whole suite until this test was written (below).

### The three criteria that need fits

| Criterion | Threshold | Result |
|---|---|---|
| recovery on fictional data | four posterior standard deviations, on `muA`, `muB`, `cut0`, `beta` | green |
| **R-hat below 1.01** | on the same four | green |
| per-group forecast summed sample by sample equals the total | exactly, by `Ensemble.eq` | green |
| the sum of the groups' percentiles exceeds the total's | the contrast of chapter 6 | green |
| a trend fitted to walk-generated data contains zero | its 90 % interval | green |
| the walk, on the same data, finds the wandering | its step size above 0.01 at the 5th percentile | green |

### Every combination, calibrated

The point of interchangeable components is that a variant costs a component rather than a rewrite;
the price is that each combination is a model nobody has checked. All eight are now checked — four
stories about the rate against two about the delay, 32 replications each on a reduced model (8
months, maximum delay 3, two groups).

| Combination | Result |
|---|---|
| independent groups, one delay | green |
| independent groups, a delay per group | green |
| pooled groups, non-centred, one delay | green |
| pooled groups, non-centred, a delay per group | green |
| pooled groups, centred, one delay | green |
| two populations, one delay | green |
| a local level, one delay | green |
| a local level, a delay per group | green |

The threshold is the **0.01 %** point of chi-squared with three degrees of freedom, 21.108, rather
than the 0.1 % point the other calibration suites use. Eight combinations ranking six to nine
parameters each is about fifty-six checks; at 0.1 % that fails by chance about one run in eighteen,
and phase 8 spent a round fixing exactly that kind of flake. At 0.01 % the announced rate is near
one run in a hundred and eighty. The power lost is the point of the division of labour: this suite
catches a combination that is **broken**, and phase 9's single-combination suite checks one deeply.

### The model's error, calibrated at last

Phase 7 deferred `sigma_err`'s calibration to the phase with a backtest; phase 8 delivered the
backtest; here it is. Each candidate scale widens every past forecast, the widened ensembles are
scored, and the best candidate wins.

| Check | Reference | Result |
|---|---|---|
| the scale the observations were generated with | 0.3, on a grid of 0.05 steps, 400 dates | green within two grid steps |
| the score is worse at both ends than at the optimum | a minimum, not a slope | green |
| an already-calibrated forecast is not widened | the honest answer is nothing | green, at most 0.10 |
| widening is per sample | 4000 distinct values | green |
| the widened mean rises by `exp(sigma²/2)` and the median does not | the lognormal | green within 3 % |
| the search is a function of its seed | the same seed, the same scores | green |
| it refuses no dates and an empty grid | | green |

The first row works because the score is **proper**: forecasts that are certain and observations
that are not give a known right answer, and the scale that minimises the CRPS is the one the
observations were really generated with. That is a property of the scoring rule, which is exactly
what makes it a test of this code.

### Mutations tried

Twelve, every one detected — two only after a new test was written for them.

| Component | Mutation | Test that failed |
|---|---|---|
| `Ar1TrendHierarchical` | the spread dropped from the non-centred effect | the hierarchical rate and the agreement tests |
| `Ar1TrendHierarchical` | the centred flag ignored when reading the effects | the agreement test |
| `Ar1TrendHierarchical` | every group read as population zero | the two-populations test |
| `Ar1TrendHierarchical` | the centred prior used for both parameterisations | the agreement test |
| `LocalLevel.logRate` | the innovations not accumulated, so not a walk | the walk test |
| `LocalLevel.project` | mean-reverting instead of a walk | the no-drift test |
| `OrderedLogitDriftByGroup` | the group's offset ignored | the offset test |
| `OrderedLogitDriftByGroup` | the offsets given a fixed spread, not the fitted one | **survived at first** |
| `ModelError.widen` | one error for the whole ensemble, not one per sample | the per-sample and recovery tests |
| `ModelError.calibrate` | the worst candidate chosen | the recovery test |
| `ModelError.calibrate` | every candidate scored on its own noise | **survived at first** |
| `Forecast.byGroup` | only the first group forecast | the per-group total test |

The two survivors are both instructive. Giving the group offsets a **constant** prior scale instead
of the fitted one cannot be seen by calibration, for a reason worth remembering: it severs the only
link between `sigmaCut` and the likelihood, so `sigmaCut`'s posterior becomes its prior — and ranks
drawn from the prior are uniform by construction. The fix is an exact test of the prior itself, with
the Gaussian written out by hand.

Scoring each candidate on **its own** noise instead of shared noise is not wrong at 400 dates, which
is why it survived: common random numbers reduce the variance of a comparison but do not bias it.
What it breaks is reproducibility, so the test that catches it is the one every seeded thing in this
library should have had — the same seed gives the same scores.

### Two defects found, both in earlier phases' code

**A rate component threw.** `Ar1Trend.logRate` indexed the wobble by month without checking that an
innovation existed for it, so asking for the rate at a time beyond the data raised an
`ArrayIndexOutOfBoundsException` — against 2.1's rule that a public function returns a value. It had
been there since phase 9 and was found by writing chapter 8, which asked exactly that question. All
three components are now total in their time axis and in their group (D-133).

**A dead type parameter.** `ModelError.calibrate` was declared `[F[_]: Monad: Rng]` and interpreted
`State` internally, so `F` was never used. Caught by the compiler under CI's fatal-warning settings
— the second time in two phases that those settings found something a plain local build does not
(D-132), which is why the verification command now carries them.

### Known limits at the end of phase 10

1. **A group that is quick at first and slow in the tail is not expressible.**
   `OrderedLogitDriftByGroup` shifts all of a group's cut points together, so only the position of
   its delay distribution varies and not its shape. Per-group increments would be the fix and
   nothing in the plan asks for them.
2. **The predictive score does not separate the rate variants** (D-134). Measured, reported in
   chapter 8, and sharper as a question about the parameter than about the forecast.
3. **`Ar1Trend` with a tightened trend prior** is listed in §5.10 as a variant and is not written:
   it is `Ar1Trend(trendPrior = (0.0, 0.1))`, a constructor argument rather than a component, so
   there is nothing to add.
4. **The fictional world has no named generator yet.** §9.2 puts `examples.FictionalWorld` in the
   `examples` module as whole programs (`IOApp.Simple`), which needs the `effect` module of phase
   11; the manual's chapters generate their invented data in place, as they have since phase 9.

## Phase 11 — the effectful edge

540 tests, 506 fast and 34 slow, up from 520 — 20 added by this phase, all of them fast: an
effectful edge is checked by asserting it equals the pure core, which is quick, rather than by
sampling for minutes. The 486 fast tests of phases 0 to 10 are untouched; no tolerance, seed or assertion was weakened, and the
register of encapsulated local mutation stays at 27 sites.

The only module with effects. It interprets the core's algebras in `F` and contains no statistical
logic: the warmup schedule, the transitions and the scoring all come from the modules below it, and
this one decides only when they run, on which fibre, and what happens to an error.

### One loop, two interfaces

`Mcmc.run` folds a whole chain; a stream wants one iteration and the chance to stop in between.
Those are now the same loop — `Progress` carries the state, `step` does one iteration, and `run` is
a fold over `step` (D-135).

| Check | Result |
|---|---|
| the stepped chain is the folded chain, draw for draw | green, **bit for bit**, transitions and adapted step size included |
| the progress says which phase it is in and stops when done | green: 5 warmup then 3 sampling, then nothing |
| a starting point the target cannot read is an error | green, and so is one with no usable step size |
| **the whole existing suite, unchanged** | green: 11 leapfrog fixtures, 9 posteriordb posteriors, every SBC suite |

That last row is the evidence that rewriting phase 4's loop changed nothing observable. Every one of
those tests runs on a fixed seed, so a single different random draw anywhere would have shown.

### The randomness algebra at the edge

| Check | Reference | Result |
|---|---|---|
| every method against the `State` interpreter | interleaved longs, uniforms and normals | green, **bit for bit** |
| a split seed gives independent chains | `State` on the same split | green |
| a `Ref` read twice carries on rather than restarting | one long pure program | green |
| concurrent use is serialised and consumes each value once | the pure interpreter's multiset | green, 200 distinct values |

The interleaving matters: a generator that drew the right numbers in the wrong order would pass a
test of each method alone. It cannot drift, because `RefRng` runs the **same pure programs** rather
than reimplementing the generators (D-136).

### The sampler

| Criterion | Result |
|---|---|
| **parallel equals sequential, bit for bit** | green: a four-chain `IO` fit equals `Mcmc.runNuts` on the same seed, value for value, 400 rows |
| a streamed chain is the same chain | green, and it says which of its 50 iterations were warmup |
| **cancellation lands quickly** | green: 100 000 samples cancelled after 100 ms stopped in under 2 s |
| **no core error is lost** | green: an unreadable start raises the `BayesError` itself, in both `run` and `chain` |

Bit-for-bit parallelism is not a property of the model used: chain `c` is driven by its own
generator at `seed.fold(c)`, so what it draws cannot depend on the scheduler (D-137). A shared
generator would have been easier and would have made the fit depend on timing.

### Files

| Check | Result |
|---|---|
| a chain written and read back | green, value for value |
| a fit of three chains to one file each, and back as one fit | green, named `fit-1.csv` to `fit-3.csv` |
| writing a fit to **one** file is refused | green, and the error says to use `writeAll` |
| a malformed file is an error of `F` | green, and so is a file that does not exist |
| sampler to disk and back, end to end | green: a two-chain fit survives the round trip exactly |

### The backtest loop

| Check | Result |
|---|---|
| one row per origin, in order, scored as phase 8 scores it | green, every field identical |
| **taking two rows costs two refits, not six** | green |
| an error in a refit stops the stream and is not swallowed | green, and the rows before it are still obtainable |
| no origins is an empty stream, not an error | green |

The second row is the only property that distinguishes a stream from a list, and it is the reason
this is a stream: a backtest refits once per origin, which is the most expensive thing the library
does.

### Mutations tried

Eleven, every one detected.

| Component | Mutation | Test that failed |
|---|---|---|
| `RefRng.run` | the seed read but never advanced | the agreement and split-seed tests |
| `RefRng.make` | a generator reimplemented instead of the pure program | the agreement test |
| `Sampler.chain` | every chain given the same seed | the bit-for-bit and streamed tests |
| `Sampler.run` | the warmup draws kept | the bit-for-bit test |
| `Sampler.chain` | a failing start becomes an empty stream | the error test |
| `Mcmc.step` | the tuning never frozen, so the sampler keeps adapting | **phase 4's step-size bracket test** |
| `Mcmc.step` | every iteration reported as sampling | the bit-for-bit and phase tests |
| `PosteriorCsv.write` | a fit silently truncated to its first chain | the refusal test |
| `PosteriorCsv.writeAll` | the chains numbered from zero | the naming test |
| `BacktestRunner.run` | every origin refitted before the first row | the laziness test |
| `StanCsv.parse` | a header with no rows accepted again | the malformed-file test |

The sixth is worth a line. Because `run` is now a fold over `step`, a mutation inside `step` changes
**both** interfaces identically — so the test that they agree cannot see it. What caught it was
phase 4's own assertion that the adapted step size brackets its target, which is the argument for
keeping external references rather than only internal consistency checks.

### A defect found in phase 5's codec

`StanCsv.parse` accepted a file whose only line was a header, returning a posterior of zero draws
(D-140). A truncated file and a mistyped path that happens to exist both look exactly like that, and
every statistic computed from the result comes back `NaN` with nothing to say why. It now returns
`Malformed`; the stan-io suite passed unchanged, since a real CmdStan file always has at least one
row.

### Known limits at the end of phase 11

1. **`Sampler.run` holds every chain in memory.** It compiles each stream to a vector before
   building `Draws`, because `Draws` is a rectangle. A fit too large for memory wants the streaming
   interface and `PosteriorCsv` written draw by draw, which the module supports and `run` does not
   do for you.
2. **Cancellation is between iterations, not inside one.** A single NUTS transition at maximum tree
   depth is a thousand leapfrog steps and will run to completion. That is the specification's
   intention — never half a transition — and it means the worst-case cancellation latency is one
   iteration, not one step.
3. **`BacktestRunner` cannot enforce that no fit sees its own target** (D-141), since the forecast
   function is opaque to it. The contract is the caller's and chapter 8 states it.
4. **No `examples` module yet.** §9.2 puts the long programs there as `IOApp.Simple`, which this
   phase makes possible; writing them, and the named `FictionalWorld` generator, belongs with the
   manual's completion.
