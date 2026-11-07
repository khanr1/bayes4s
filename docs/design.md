# Design decisions

One section per decision taken while building the library, in the order they were taken. A
decision is recorded here when it constrains later work: a public signature, a dependency, a
layout, a deviation from what was planned. Where a decision departs from the original design, the
reason is given, so that reversing it is a deliberate act rather than an accident.

This file is the reference for the repository. The planning document that preceded it is not part
of the repository and is never cited from it.

---

## Phase 0 — skeleton

### D-1. Name, licence, native bindings, publishing

- The library is named **bayes4s**: root package `bayes4s`, artefacts `bayes4s-core`,
  `bayes4s-ad`, and so on, and the stress flag `-Dbayes4s.stress=true`.
- It is licensed under **Apache 2.0** (`LICENSE`). The BSD-3-Clause texts of Stan, Stan Math and
  posteriordb sit in `LICENSES/`, because reference data copied from those projects lives in
  `modules/testkit/src/main/resources/reference/` and keeps its own licence.
- **No BridgeStan module, and no native dependency at all in v1.** If the sampler ever falls
  behind, the fallback is `stan-io`: read CmdStan draws and run the predictive simulation, the
  scores and the backtest on them.
- **Publishing is off.** The repository stays private for now, and
  `githubWorkflowPublishTargetBranches := Seq()` is what removes the job: `ci.yml` carries no
  publish job, no Sonatype credentials and no PGP step. sbt-typelevel's `tlCiReleaseBranches` and
  `tlCiReleaseTags` feed into that key, so setting them as well would be dead configuration — sbt
  says as much, through `lintUnused`. There is deliberately no `release.yml` either.

  Turning publishing on later means: confirm the decision, drop that line, set
  `tlCiReleaseBranches := Seq("main")`, pick a group id that matches a verified Sonatype namespace
  (the current `io.github.bayes4s` is a placeholder and has **not** been claimed), fill in
  `developers`, then run `sbt githubWorkflowGenerate` and commit the result.

### D-2. Dependency versions, checked on 3 November 2026

Every version below was read from Maven Central on that day, not from memory.

| What | Version | Note |
|---|---|---|
| Scala | 3.3.9 | The LTS line. 3.3.9 shipped 2026-10-22, three years after 3.3.0, while 3.7.x stopped at 3.7.4 in November 2025 and 3.8, 3.9 and 3.10 are "Next" releases. Patch releases still arriving on 3.3 is what identifies the LTS. |
| sbt | 1.13.1 | sbt 2.0.x exists, but the whole Typelevel plugin set is still published for the sbt 1.x axis. |
| sbt-typelevel | 0.8.7 | Brings sbt-header, sbt-scalafmt, MiMa, and the CI generator. |
| cats / cats-effect | 2.13.0 / 3.7.1 | |
| fs2 / fs2-data-csv | 3.14.0 / 1.14.1 | `effect` only. |
| circe | 0.14.17 | 0.15.0-M1 is a milestone, not a release. |
| commons-numbers | 1.3 | `-gamma` and `-core`, for `logGamma` and `digamma`. |
| weaver | 0.13.0 | On `org.typelevel`; the old `com.disneystreaming` group is archived. |
| scalafmt-core | 3.11.5 | See D-3. |
| sbt-scalafix / sbt-mdoc / sbt-jmh | 0.14.9 / 2.9.2 / 0.4.8 | |

### D-3. The build needs a JDK >= 23; the artefacts target Java 17

`tlJdkRelease := Some(17)`, so anything published stays usable from Java 17 on. The **build**,
however, needs a newer JDK than that: scalafmt 3.11.5 loads a class compiled for Java 23
(`io.github.alexarchambault.isterminal`, pulled in by the coursier that scalafmt-dynamic uses),
and fails with `UnsupportedClassVersionError` on anything older. CI therefore runs on
**temurin 25**, the current LTS.

The development machine runs **JDK 25.0.5** (Homebrew `openjdk@25`), installed on 4 November
2026 for exactly this reason, and the whole pipeline including `scalafmtCheckAll` runs there.

That formula is keg-only and is not registered with `/usr/libexec/java_home`, so sbt has to be
told where it is:

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

This deliberately is **not** written into a `.sbtopts`: that path exists on one machine and would
break the Linux runners. On the development machine the export sits in `~/.zshenv`, which zsh
reads for every shell, so sbt finds the right JDK from a script or a hook and not only from an
interactive terminal.

### D-4. `testkit` depends on no bayes4s module

Every module, `core` included, declares `dependsOn(testkit % Test)`. For that to hold without a
dependency cycle, `testkit` itself depends on nothing from this build — only on cats, circe and
weaver. Nothing it currently provides needs a bayes4s type:

- `Reference` loads files and returns `Either`, `Json`, `IArray[Double]`;
- `Approx` and `MonteCarlo` return `weaver.Expectations`.

Generators and oracles that *do* need library types stay in the tests of the module that defines
them and travel with `dependsOn(other % "test->test")`: the `Dual` interpreter in the tests of
`ad`, `TestModels` in the tests of `inference`. The original plan had `testkit` depend on `core`,
which would have made `core`'s own tests a cycle.

### D-5. `Draws` belongs in `core`

`stan-io` parses and renders CmdStan CSV into `Draws`, and `predictive` consumes `Draws`. Keeping
`Draws` in `predictive` would force `stan-io` to depend on it for a plain data type. `Draws` is
therefore a `core` type, and `stan-io` depends on `core` alone.

### D-6. Layout: `modules/<name>`

Each module lives in `modules/<name>` rather than at the top level, so the repository root holds
only build files, documentation and the reference scripts. Reference data consequently sits in
`modules/testkit/src/main/resources/reference/`, and the BSD-3 licence texts that came with it
were moved to `LICENSES/` at the root.

### D-7. The private-term guard uses HMAC, and it is a Python script

The rule is absolute: nothing in this repository relates to the maintainer's employer, sector,
products, clients or data. The automated guard works on digests only, so that the list of terms
never enters the repository. Two deviations from the original design:

1. **HMAC-SHA-256 with a key kept outside the repository**, not a bare SHA-256. A bare SHA-256 of
   ordinary words — a company name, a product, a city — falls to a dictionary attack in seconds,
   so publishing such a file would leak exactly what it is meant to protect. With a key that lives
   only in the maintainer's environment and in a CI secret, the digest file reveals nothing.
2. **Python, not scala-cli.** `scripts/check-private-terms.py`, `scripts/hash-private-terms.py`
   and the shared `scripts/private_terms.py` need no toolchain beyond the Python 3 that is already
   present locally and on the runners. The rule that sbt never launches Python still holds: this
   is a CI step, not an sbt task.

Behaviour, in order of strictness:

- `.private-terms.sha256` absent or empty → the check passes and says nothing is configured. That
  is the state the repository ships in: only the maintainer has the term list.
- digests present, no key → **exit 2**. The check refuses to pass silently when it cannot verify.
- a digest matches → exit 1, naming the file, the line and the first bytes of the digest. Never
  the term: the script does not know it, and the CI log must not carry it.

A single-word line is digested as that word; a multi-word line is digested whole and as pairs of
consecutive words, but not word by word, or an ordinary word of a name ("group", "industries")
would fail CI everywhere. To refuse a single word, give it its own line.

The guard only sees the terms it was given. Human review of every pull request that touches
`docs/` or `examples/` remains the real defence.

### D-8. Slow suites are a configuration, not a tag

`sbt test` must stay fast enough to run on every save. The slow statistical suites — SBC,
posteriordb, parameter recovery — therefore live in their own source directory, `src/slow/scala`
of each module, under an sbt configuration `slow` that extends `Test`:

- `sbt test` compiles and runs `src/test/scala` only;
- `sbt slow:test` runs the slow suites, with `Test`'s classpath available;
- `sbt -Dbayes4s.stress=true slow:test` is what `stress.yml` runs weekly, with fresh seeds and
  more draws.

A separate directory rather than tag filtering: a suite is slow or it is not, and a reader can see
which from the file's path.

### D-9. `Reference` returns `Either`, and quoting non-finite tokens respects strings

`Reference.json` was specified as returning `io.circe.Json` directly. It returns
`Either[Reference.Error, Json]` instead, because errors are values here as everywhere else, and a
missing or malformed reference file must fail a suite with a message that names the file rather
than throw. `Error` is an enum with a `message` method; the suites render it.

The reference files use Python's `Infinity`, `-Infinity` and `NaN`, which JSON does not define.
`Reference.json` quotes those tokens before parsing and `Reference.looseDouble` reads them back as
the IEEE values. The subtlety is that the same words occur **inside** JSON strings — for instance
`"every diagnostic must return NaN"` in `stan/diagnostics/expected.json`. Quoting those would
break the string and the parse. The implementation therefore splits the input on string literals
and rewrites only what lies outside them. `ReferenceSuite` asserts both halves of this, and the
mutation recorded in `docs/validation.md` shows the test that catches it.

### D-10. `GammaPoisson` will live in `distributions`, not `nowcast`

The conjugate Gamma-Poisson update and its negative-binomial predictive are needed from phase 1,
where `worked_examples.json` asserts them, while `nowcast` is not built until phase 9. They are a
property of the distributions, not of the nowcasting model, so `GammaPoisson` goes in
`distributions` and only `GammaPoissonShrinkage` stays in `nowcast`.

### D-11. Everything in the repository is written in English

Code, comments, scaladoc, documentation, scripts, commit messages. The manual is English by
design, and a published library with French comments in its sources would be inconsistent.

### D-12. scalafix runs `DisableSyntax` only, for now

`DisableSyntax` is enabled with `noVars`, `noThrows`, `noNulls`, `noReturns`, `noWhileLoops`,
`noAsInstanceOf`, `noIsInstanceOf` and `noFinalize`, and CI fails on any of them. This is the rule
the design depends on, and it is syntactic, so it works on Scala 3 with no SemanticDB.

`OrganizeImports` is **not** enabled yet: it is a semantic rule, its Scala 3 support depends on
SemanticDB being produced the way it expects, and there is almost no import in the tree to
organise. It is to be enabled in phase 1, when there is real code to check it against.

### D-13. The register of encapsulated local mutation

The one allowed exception to referential transparency is mutation created inside a pure function
that never escapes it. Every such site carries `// scalafix:ok DisableSyntax.var` (or `.while`)
with a sentence of justification, **and** a row below. `scripts/check-mutation-register.py`, run in
CI, fails if the two disagree: an annotation silences the lint, and only the register makes someone
argue the case in prose.

<!-- mutation-register:begin -->

| File | Rule | Sites | Why mutation, and why it cannot escape |
|---|---|---|---|
| `modules/core/src/main/scala/bayes4s/Fft.scala` | `var` | 3 | The stage, block and butterfly indices of an in-place radix-2 transform. An immutable butterfly allocates a pair of arrays per stage and per block: for the thousand-draw autocovariance of the diagnostics that is tens of thousands of allocations where two suffice. The arrays are created in `run`, written there, wrapped as `IArray` on the way out, and no two calls share anything. |
| `modules/core/src/main/scala/bayes4s/Fft.scala` | `while` | 3 | The three nested loops of the same transform. A tail recursion over three indices would say the same thing less clearly and no faster. |
| `modules/core/src/main/scala/bayes4s/Batch.scala` | `var` | 6 | The threaded seed and the fill index of each batch sampler. A simulation of a hundred thousand futures would otherwise allocate a `State` step per draw. `BatchSuite` asserts that each batch equals the `State` program of the same seed, element by element, so the fast path cannot drift into being a different sampler. |
| `modules/core/src/main/scala/bayes4s/Batch.scala` | `while` | 3 | The fill loop of each batch sampler, over an array created and returned by the same function. |
| `modules/ad/src/main/scala/bayes4s/Reverse.scala` | `var` | 9 | The tape of one gradient: four growable arrays — node values, edge offsets, parents, partial derivatives — two write cursors, and the three index cursors of the reverse sweep. An immutable tape would allocate a structure per elementary operation, and a realistic model's gradient is hundreds of thousands of them; this is the one place in the library where that cost would decide whether a sampler is usable at all. The tape is created in `valueAndGradient`, written only through the methods of `Tape`, read once backwards and dropped. It appears in no public signature, `Rev` is opaque outside `ad`, and `LogDensityGradientSuite` asserts that sixteen gradients computed concurrently are bit for bit the sixteen computed in sequence. |
| `modules/ad/src/main/scala/bayes4s/Reverse.scala` | `while` | 3 | The edge-filling loop of a vectorised node and the two nested loops of the reverse sweep. The sweep walks node indices downwards and, for each, its own slice of a flat edge array; written as a fold it would allocate a map of adjoints per node, which is the allocation the flat arrays exist to avoid. |

<!-- mutation-register:end -->

### D-14. Formatting: 100 columns, no alignment, scaladoc in the Scala style

`.scalafmt.conf` sets `maxColumn = 100`, `align.preset = none` and
`docstrings.style = SpaceAsterisk`. The last one matters more than it looks: scalafmt's default
rewrites every scaladoc into the javadoc shape, `/**` alone on its line and asterisks at column
2, whereas cats, fs2 and the rest of the ecosystem write

```scala
/** First line of the text.
  *
  * Continued here.
  */
```

which is what `SpaceAsterisk` produces. Settling this at phase 0 avoids reformatting every
comment in the library later.

### D-15. CI runs on `ubuntu-latest`, and the dependency-graph job is off

sbt-typelevel 0.8.7 still pins `ubuntu-22.04`, an image GitHub has deprecated; a workflow that
names a retired image does not fail a step, it fails to find a runner at all.
`githubWorkflowOSes := Seq("ubuntu-latest")` is right whether or not that image is still served.

`tlCiDependencyGraphJob := false` removes the "Submit Dependencies" job. It calls an API that
needs `contents: write`, which a repository's default `GITHUB_TOKEN` does not have, so it would
fail on every push to the default branch. It is worth turning back on, with the permission
granted, once Dependabot is configured and the repository is one whose dependency graph anyone
reads.

---

## Phase 1 — the core and the laws as values

### D-16. One flat `bayes4s` package

Every public type lives in `bayes4s`, whichever module defines it, so that `import bayes4s.*`
gives a reader `Seed`, `Normal`, `Mcmc` and `Summary` at once. The modules are a unit of
dependency and of publication, not a namespace: a reader should not have to know that `Seed` is in
`core` and `Normal` in `distributions` to write a model. Package-private machinery uses
`private[bayes4s]`.

### D-17. `digamma` is ours, `lgamma` is Commons Numbers'

`LogGamma` from Apache Commons Numbers holds 1e-13 relative against mpmath across the whole
reference grid, negatives included, so it is used as it is, with the reflection formula added for
the negative half line.

`Digamma` does not: at x = 1.001 it is 2e-10 relative away from the truth, and the reflection
formula then carries that error into every negative argument. It is replaced by the recurrence
`psi(x) = psi(x + 1) - 1/x` up to x = 20 and the Bernoulli series above, whose first unused term
there is below 1e-20. This is the only place in phase 1 where a dependency was found wanting; the
test that found it is the 72-point grid, which is why it was written first.

### D-18. The sine of a reflection is folded around the nearest integer

Both reflection formulae need `sin(pi x)`. Folding `x` into one period and then using a quarter
period identity gives `cos(pi (0.5 - r))` with an argument near pi/2, and the *absolute* error of
that argument becomes the *relative* error of a small result: at x = -0.001 it costs three digits
and `digamma` misses its target. Folding around the nearest integer instead keeps the argument of
the sine small, where its relative accuracy is what it should be.

### D-19. Cancellation is designed out of the count laws

Three identities do the work, and each exists because the textbook form loses the answer:

- `lbeta(a, b)` subtracts Stirling's approximation from each log gamma first. The plain
  `lgamma(a) + lgamma(b) - lgamma(a + b)` at a = 300 adds three terms near 1400 to get -0.14:
  eleven digits cancel and the 1e-13 target is unreachable.
- `C(k + a - 1, k)` is computed as `-log(k + a) - lbeta(k + 1, a)`, which keeps every term the size
  of the answer where the three cancelling log gammas would not. At a dispersion of 1e8 — the case
  the specification calls out — the naive form has nothing left.
- A ratio of parameters becomes a `log1p` of the *other* ratio: `alpha * (log beta - log1p(beta))`
  is written `-alpha * log1p(1 / beta)`. The first form cancels six digits and then multiplies the
  remainder by alpha, which at alpha = 1e6 is how a 1e-16 rounding becomes a 1e-10 answer.

### D-20. Where the reference is wrong: `neg_binomial_lpmf(10 | 1e6, 1e5)`

Stan is the reference for this library except where its numerics are demonstrably wrong, and this
is such a place. For that case the coefficient C(1000009, 10) is an integer, so the log mass is
computable exactly: with exact integer arithmetic and 40-digit decimal logarithms it is
`-2.07856664311089194671129470824235`.

| Source | Value | Relative error |
|---|---|---|
| exact | -2.078566643110891946... | — |
| **this library** | -2.0785666431108893 | **1.3e-15** |
| Stan Math asserts | -2.078566643108163 | 1.3e-12 |
| SciPy | -2.078566642705411 | 2.0e-10 |

The fixture recorded the two references as agreeing, because their errors have the same sign and
its threshold was looser than both. Both compute the cancelling log gammas that D-19 exists to
avoid. `StanMathDensitySuite` therefore asserts the exact value for this one case, at 1e-14, and
names the reason in place.

### D-21. Three test expectations that double arithmetic cannot witness

Each of these was written as an assertion, failed, and was found to be asking the impossible
rather than catching a defect. None of them was a tolerance widened to make a test pass; each is a
grid or a direction changed because the arithmetic cannot hold the claim, with the reason in the
test.

- **The antisymmetry of the normal quantile** can only be checked where `1 - p` recovers `p`. At
  p = 1e-12 the subtraction keeps eight digits, so comparing the two tails measures the
  representation of `1 - p` and nothing about the quantile.
- **`logit(logistic(x))`** cannot return `x` to 1e-10 beyond about 14: `logistic(30)` is
  `1 - 9.4e-14`, and a double near 1 holds that deviation to three digits. The demanding assertion
  is made on `logistic(logit(p))`, which keeps every digit even at p = 1e-300, and the saturation
  point is asserted for what it is: `logistic(300)` is exactly 1 and the round trip is `+Inf`.
- **The argument of a smooth minimum** is not worth 1e-10. The objective changes by less than a
  double's resolution once the argument is within sqrt(eps), so `brentMinimize` is held to 1e-7 on
  the argument and 1e-12 on the value. Roots are well conditioned and keep 1e-12 on the argument.

### D-22. The standard error of a sample variance comes from the sample

A variance test first used `sigma^2 sqrt(2 / n)` with a factor of three for safety, and that
underestimates the real standard error by 2.6 for a gamma of shape 0.05 and by 4.7 for a Poisson of
mean 0.0025 — both of which then failed. The suites now use `sqrt((m4 - s^4) / n)` from the
sample's own fourth moment, which assumes nothing about the law and is the right budget for every
one of them.

### D-23. A law is a value behind a trait, and three laws stand apart

`ContinuousLaw` and `DiscreteLaw` give `logPdf` or `logMass`, `mean`, `variance` and `sample`, so
that a suite can hold every law to its own moments in one loop instead of thirteen copies. The
specification sketched extension methods per law; a trait says the same thing once.

`Categorical`, `OrderedLogistic` and `Dirichlet` implement neither: the mean of a category index is
not a quantity anybody wants, and a Dirichlet draw is a vector. They carry their own shape and are
tested against their mass summing to one and against a chi-squared on their frequencies.

### D-24. A parameter must be finite, uniformly

Every smart constructor refuses a parameter that is `NaN` or infinite, and then its own
constraint. Stan's fixture is less uniform than that — it asserts that an infinite scale is
rejected for the logistic and the gamma but not for the normal — so the 46 rejections it asserts
are a subset of what this library refuses. A variate outside the support is not a parameter error:
the density is `-Inf`, which is what lets a sampler reject a proposal instead of crashing on it.

### D-25. `Seed.nextUniform` is the one uniform primitive

The conversion from 64 bits to a double in the open interval (0, 1) lives on `Seed`, so the `State`
interpreter and the batch path cannot disagree about it. The top 53 bits are placed at the centre
of their bucket rather than at its floor, which is what keeps the result away from both ends
without distorting it, and is why no density has to guard against `log(0)`.

`split` and `fold` have no external oracle: `java.util.SplittableRandom` carries a gamma per
instance while a `Seed` here is a single `Long`, so `next` is checked against the JDK bit for bit
and the other two are our own derivation, held to determinism, distinctness and the absence of
correlation. That limit is recorded in `docs/validation.md` rather than papered over.

---

## Phase 1b — multilevel laws, linear algebra, naming, vocabulary

### D-26. The Stan names, with the old ones deprecated

The naming rule was written after phase 1, and five public names contradicted it. They are renamed
and the old name stays as a `@deprecated` forwarder until v0.1:

| was | is | Stan |
|---|---|---|
| `LogExp.logistic` | `LogExp.invLogit` | `inv_logit` |
| `LogExp.logSigmoid` | `LogExp.logInvLogit` | `log_inv_logit` |
| `Special.normalQuantile` | `Special.invPhi` | `inv_Phi` |
| `Quantile.type7` | `Quantile.quantile` | `quantile` |
| `Quantile.type7OfSorted` | `Quantile.quantileOfSorted` | — |
| `Normal.standard` | `Normal.stdNormal` | `std_normal` |

`LogExp.log1mInvLogit` is new, and belongs here rather than to the automatic differentiation: the
Bernoulli on the log-odds scale needs it now.

Two consequences worth stating. First, the call sites in the existing suites were updated, which
the phase criterion could be read as forbidding — "the existing tests stay green without
modification". They do stay green, and not one assertion or tolerance moved; only the name being
called did, which is what a rename is. Leaving them on the deprecated names would have made CI
red, since it turns warnings into errors.

Second, `DeprecatedNamesSuite` is the one place where deprecation warnings are silenced, with
`@nowarn`, because calling those names is its job. It asserts that every alias returns exactly
what its replacement returns, `NaN` and the far tails included: an alias that drifted would be
worse than no alias, since code written against the old name would keep compiling and start
computing something else.

The parameters of the laws keep their own names — `Poisson(rate)`, `Binomial(trials,
probability)` — because the rule governs function names and argument order, not field names, and
because `N` and `theta` appear in public error messages where `trials` and `probability` read
better. The correspondence lives in appendix A and in the glossary.

### D-27. Linear algebra needed no mutation, and two more signatures return `Either`

The plan allowed the encapsulated mutation of 2.1 for `core.linalg` and predicted two annotated
sites. It turned out not to be needed: Cholesky and the triangular solve are folds over a `Vector`
of rows, each row built from the ones before it, and at the sizes this module targets — the
covariance of a few group effects — the difference is not measurable. **The register of mutation
sites stays at fifteen.**

Two signatures depart from the sketch in the plan, both to keep errors as values:

- `Matrix.identity(n)` returns `Either`, because there is no identity below size one and
  returning something for `identity(0)` would be a lie;
- `Linalg.lowerSolve` returns `Either`, because a right-hand side of the wrong length is a
  dimension mismatch like any other.

### D-28. `Cholesky` is an opaque type with a smart constructor

`opaque type Cholesky = Matrix` carries the invariant — lower triangular, positive diagonal — so
that `lowerSolve`, `logDetFromCholesky` and `MultiNormalCholesky` cannot be handed a matrix that is
merely square. `Cholesky.ofLower` validates a factor the caller already has, which the reference
cases need: they give `L`, they do not give the covariance to decompose.

`Linalg.cholesky` refuses an asymmetric matrix rather than silently using one triangle, and names
the **pivot** when a matrix is not positive definite. The message matters: a `NaN` diagonal would
be caught one layer down by `ofLower`, which would report a bad diagonal — true, and useless to
someone whose covariance is not positive definite.

### D-29. The LKJ constant needs no special case at `eta = 1`

The product form of Theorem 5 (Lewandowski, Kurowicka and Joe 2009),

```
log c(K, eta) = sum over k in 1..K-1 of
    (2 eta - 2 + K - k) (K - k) log 2 + (K - k) lbeta(a_k, a_k),    a_k = eta + (K-k-1)/2
```

reproduces all twenty reference constants to 1.8e-14, **including the four at `eta = 1`**. Stan
keeps a separate branch there and the reference data notes that the uniform case has its own
closed form; both are true, and neither is necessary. The separate branch is an optimisation, not
a mathematical requirement, and one formula is easier to keep right than two.

The density is `logConstant + sum over i of (2(eta - 1) + K - i - 1) log L(i,i)`, where the weight
is the exponent of the determinant and the Jacobian of `Sigma = L L'` added together. Dropping the
second half of that weight is a mistake no single case would reveal, which is why the nine
reference densities span three dimensions and four concentrations.

### D-30. A missing moment is reported, not invented

A Cauchy has no mean and a Student-t has no variance below two degrees of freedom. `mean` and
`variance` return `NaN` there, and `+Inf` where the variance is genuinely infinite (one to two
degrees of freedom). The suites then judge those laws by their whole distribution — a
Kolmogorov-Smirnov statistic against the closed-form cumulative — rather than by moments that do
not exist. A library that returned a number there would be lying in a way nothing would catch.

### D-31. Two small things that avoid a public name

`bernoulli_lpmf` is tested through `Binomial(1, theta)`, which is the same mass function exactly;
the specification lists no plain Bernoulli, and adding one would be a public law that says nothing
new.

`StudentT` and `Cauchy` have `private[bayes4s]` constructors rather than `private` ones, so that
`HalfStudentT` and `HalfCauchy` can delegate to the full law instead of duplicating its density.
The constructor stays out of the public API; only the package can reach it.

### D-32. The fixture mapping is keyed by law, function **and** parameter

`bernoulli` calls its parameter `theta` in both `lpdf` and `lpdf_logit`, where it is a probability
in the first and a log odds in the second. Keyed by law and name alone, the test mapping would
have sent a log odds to a constructor expecting a probability, and the rejection suite would have
passed while asserting one case fewer than it reported. The key is now the triple, and
`notYetImplemented` is down to a single entry: `neg_binomial_2.lpdf_log_param.eta`, the log-mean
parameterisation the specification does not ask for.

### D-33. The contract distinguishes a value outside the support from a `NaN`

Thirteen of the fourteen out-of-support cases carry a finite value the law cannot produce, and the
density is `-Inf`. One — `exponential` — carries a `NaN`, which is not a value at all and
propagates as IEEE arithmetic requires. The suite asserts each according to the contract; asserting
`-Inf` for both, as it first did, was asserting something false about one of them.

### D-34. The terminology check, and what it does not read

`scripts/check-terminology.py` reads `docs/manual/terminology.csv` and flags the avoided terms in
the manual's prose. Three exclusions, each for a reason:

- fenced code blocks and inline code spans, because `LogDensity.normal` is the very name the
  glossary tells us to keep;
- `appendix-a-*.md`, whose job is to speak Stan;
- the **table rows** of `glossary.md`, which is the list of avoided terms itself. Its prose stays
  under the rule: the page that states the convention is not exempt from following it, only from
  quoting it.

A line ending in `<!-- term-ok -->` keeps its term, for the places where the word means something
else — a draw from a random number generator is not a sample from a posterior. The escape is meant
to stay rare enough to read as a list; it is currently used nowhere.


## Phase 2 — automatic differentiation

### D-35. `Real[A]` carries its own `ClassTag`

Every operation that returns a vector has to build an `IArray[A]`, and that needs the runtime class
of `A`. The alternative was a second context bound on every signature that touches a vector —
`def apply[A: Real: ClassTag]` on `DiffFn`, on every model, on every density — a bound that says
nothing about the mathematics and that a user would have to carry forever. `Real[A]` has a `tag`
member instead: the type class that knows how to add two `A`s is a reasonable place to know what an
`A` is. The sketch in the plan has no such member, and this is the only change to it that was
needed to make the sketch compile.

`bayes4s.syntax` additionally publishes `given tagFromReal[A](using Real[A]): ClassTag[A]`, so a
model that wants `IArray.tabulate` directly can have one. It is behind the import rather than in
`Real`'s companion, so it is opt-in: a `given ClassTag` in implicit scope for every type is more
than anyone asked for.

### D-36. The vectorised primitives carry defaults, and that is what makes the oracle independent

Each vectorised primitive of `Real[A]` has a **default implementation in terms of the scalar
operations**. That is not a convenience for implementers; it is the test design. The default
*defines* what the primitive means; `Real[Double]` and the dual-number oracle inherit it; the
reverse-mode interpreter overrides it with a hand-written adjoint that pushes a single node. A
suite then compares the two, which is comparing a hand-written derivative against the derivative of
the composition it claims to equal — the thing that can be silently wrong and that no comparison of
values would see.

An interpreter that overrode a primitive *and* was used as the oracle would make the comparison
circular, which is why the oracle implements only the scalar operations. The one consequence to
accept is D-43.

### D-37. `Rev` is an opaque `Int`, and the tape lives in its companion

A node of the tape is an index into arrays owned by the current call. `opaque type Rev = Int` gives
that index a type the outside cannot see through, so no value of type `Rev` means anything outside
the gradient that made it, and `DiffFn`'s rank-2 signature stops one being kept at all.

The consequence is a file layout that looks odd and is not: `Tape` and `RevReal` live in
`object Rev`, not in `object Reverse`, because an opaque alias is transparent **only** in its own
companion, and those two are exactly the places where an index has to be read as an index.
`Reverse` is the facade and holds nothing but `value`, `valueAndGradient` and `nodeCount`.

### D-38. `lbeta` is a primitive of `Real[A]` — one addition to the plan's sketch

The trait sketched in the plan has `lgamma` and not `lbeta`, and the negative binomial, the
beta-binomial and the LKJ constant all need `log B(a, b)` of two **parameters**. Written as
`lgamma(a) + lgamma(b) - lgamma(a + b)` it loses what the law layer exists to keep: at a
concentration of 1e6 the two large terms are near 1.3e7 and their difference near 152, so the
result carries an absolute error near 3e-9. Measured, not predicted — this is the mutation recorded
in `docs/validation.md`: with the definition in place of `Special.lbeta`, the Stan Math case
`neg_binomial_lpmf(10 | 1e6, 1e5)` comes out 1.0e-9 away from the exact value, where the suite
asserts 1e-14.

So `lbeta(a, b)` is abstract on `Real[A]` and implemented by each interpreter, like `lgamma`:
`Special.lbeta` for the value, one tape node with the two digamma differences for the reverse mode,
and the same two differences carried forward by the oracle. The oracle's derivative is therefore the
same formula typed twice rather than an independent derivation, which is why `lbeta` is also in the
scalar function that `ReverseSuite` checks against central differences.

This is an addition to a public signature beyond the renamings of 2.9, so it was put to the
maintainer with the alternative — porting Stan Math's `stirling_diff` series against `Real[A]`,
which would duplicate `Special.lbeta` inside a module that holds no reference data for it.
**Confirmed on 5 November 2026: `lbeta` stays a primitive.**

### D-39. `@targetName` where the overloads erase alike

`normalSum(ys: IArray[Double], mus: IArray[A], sigma: A)` and
`normalSum(ys: IArray[Double], mus: IArray[A], sigmas: IArray[A])` are both asked for by the
specification and both erase to three `Object`s. The same holds for the three vectorised
`LogDensity.normal`. `@targetName` gives each a distinct JVM name and keeps the Scala-level
overload the specification asks for; the alternative — renaming one of them — would have put a name
in the API that the plan does not use.

### D-40. The out-of-domain contract, read on an abstract scalar

`LogDensity` and `LogMass` branch on the **primal** value of a parameter: `Real[A].value(sigma)`
decides whether to return the formula or `NaN`. Three consequences, all deliberate:

- the branch is a pure function of the inputs, so referential transparency is untouched;
- on the valid side of the branch no test is taken at all, so a gradient in the interior of the
  support is exactly the gradient of the formula;
- a `NaN` returned as a constant is a leaf of the tape, so a model evaluated at an invalid
  parameter has a log density of `NaN` and a gradient of zeros. That is harmless because the
  sampler reads the value first and rejects the proposal; nothing downstream reads the gradient of
  a rejected point.

The rule itself is uniform and slightly stricter than Stan's: every parameter must be finite, and a
scale strictly positive. Stan rejects an infinite scale for some laws and not for others, so its
fixture asserts a subset of what is refused here; `LogDensitySuite` states the extra cases
separately rather than leaving them to the fixture.

### D-41. The ordered logistic goes through Stan's `log_inv_logit_diff`

A middle category is the difference of two logistic functions, and the form that difference is
written in is the whole of its numerical content. Subtracting the two probabilities — the way the
definition reads — gives 1.3e-5 relative error for the second of four categories at a log odds of
30, and 9.4e-12 at a log odds of 15, because both probabilities are then within 1e-13 of one. The
identity used instead is Stan's, with `a = eta - cuts(k-2)` and `b = eta - cuts(k-1)`:

```
log(invLogit(a) - invLogit(b)) = logInvLogit(a) + log(1 - exp(b - a)) + log1mInvLogit(b)
```

Every term is computed where it keeps its digits, and the gap `a - b` is the difference of the two
cut points rather than of two numbers that nearly agree.

The mirror identity, `log(expm1(a - b)) + log1mInvLogit(a) + logInvLogit(b)`, is equally exact and
was what this phase wrote first. `LogDensityTotalitySuite` rejected it: `expm1` overflows above
710, so a gap of 7e9 between two cut points made the log mass `+Inf` — a probability above one —
where the answer is zero to every digit. The two forms are algebraically the same and only one of
them is total, which is the kind of thing a property test exists to find.

Two further decisions fall out of it. **Equal cut points are allowed** and leave their category
exactly no mass, `-Inf`: that is the correct limit, it keeps the function total, and `expm1(0) = 0`
produces it with no branch — where Stan's `check_ordered` refuses them. **Decreasing cut points
give `NaN`**, because they are not an ordering; the middle categories would produce it on their own
through the log of a negative number, and the check is explicit so that the first and last
categories answer the same way.

### D-42. The multivariate densities take a plain matrix, and check it

`MultiNormalCholesky` the law takes a `Cholesky`, the opaque type that carries its invariant
(D-28). `LogDensity.multiNormalCholesky` cannot: in a model the entries of the factor are
parameters, so the invariant has to be re-established at every evaluation and a type built once
says nothing about it. The matrix is checked instead — lower triangular, positive finite diagonal,
consistent lengths — and anything else is an invalid parameter, so `NaN`. The same holds for
`lkjCorrCholesky`.

### D-43. The three interpreters agree to 1e-14, not bit for bit

A vectorised override is free to compute its primitive in a different order from the default it
replaces, and `normalSum` does: the default adds `-0.5 z²` term by term, the override sums `z²` and
scales once, because it needs that sum for the adjoint of the scale anyway. The two therefore
differ in the last two bits — on the reporting-triangle model, a log density near -4357 comes out
`...735293` through the `Double` interpreter and `...735291` through the tape.

Making them agree bit for bit would mean a second pass over the observations inside the hot
primitive of a ten-thousand-row model, to buy a property nothing needs: the sampler reads the tape
and only the tape. The suites therefore assert a relative 1e-14 between interpreters, and say why.
Reproducibility (2.4) is about seeds and thread scheduling and is unaffected: the same seed and the
same interpreter give the same answer, always.

### D-44. The oracle and the test models are shared through `test->test`

The dual-number oracle is a test fixture, not a published interpreter, so it lives in `ad`'s test
sources — and `distributions` needs it, because the densities to differentiate are there.
`distributions.dependsOn(ad % "test->test")` is what shares it, which is also what the plan's
module section describes.

The two realistic models (`TestModels`) live in `distributions`' test sources for the same reason,
and `benchmarks.dependsOn(distributions % "compile->test")` is what lets the benchmarks measure
*them* rather than a third model written for the benchmark alone. A benchmark that measures
something no suite checks measures nothing.

### D-45. `LogDensityUnnormalized` waits for the sampler

The specification asks for a variant without the normalising constants, "for the sampler". There is
no sampler yet, and a second copy of nineteen densities whose only caller is hypothetical is a
second copy to keep correct. It arrives with phase 4, where the thing that benefits from it exists
and can show the benefit.

### D-46. `Real[Double]` inherits the boxing defaults, measured and left alone

The `Double` interpreter overrides only `logSumExp`. Everything else vectorised it inherits from
the defaults, which build an `IndexedSeq` of boxed doubles per primitive. The benchmark makes the
consequence visible and it is startling at first sight: on the ten-thousand-observation model,
`Reverse.value` takes 83 µs while `Reverse.valueAndGradient` — which does strictly more work —
takes 79 µs, because the tape's overrides are loops over primitives and the default is not.

It is left alone for now, deliberately. Overriding the vectorised primitives in `Real[Double]` would
add a third hand-written copy of each one, and the comparison that validates the first two would
not cover it: `Reverse.value` is used for diagnostics and for the value-only path of a
Metropolis-style sampler, neither of which exists yet. The measurement is recorded so that the
decision can be taken by the phase that has a caller to speed up.

### D-47. What "a node count independent of 10 000" means, and what it costs to keep

The criterion of the phase is a model with ten thousand observations in fifty clusters whose tape
size does not depend on the ten thousand. It is met by the varying-intercepts model —
`normalSum(ys, gather(alpha, group), sigma)` is one node for the likelihood and zero for the
indexing — and the suite asserts not that the counts are similar for 100, 10 000 and 100 000
observations but that they are **equal**.

Adding one continuous predictor breaks it, and the measurement is the point: `slope * predictor(i)`
written per observation costs three nodes a row — the constant `predictor(i)` is itself a leaf of
the tape — so the tape grows from 89 nodes to 30 103 and the gradient takes 5.8 times as long. The
fix is not a vectorised `+` on vectors, which would still be a node per element, but fusing the
linear predictor into the density, as Stan's `normal_id_glm_lpdf` does. That is a new primitive and
a new adjoint; the plan allows adding vectorised primitives when the measurement asks for it, and
this records that the measurement asks for one, for the phase that has a multilevel regression to
run.

### D-48. `LogMass.negativeBinomial` exists, and so do the vectorised overloads

The specification sketches `LogMass` with `negativeBinomial2` and not the `(alpha, beta)`
parameterisation. The law layer has both, because `GammaPoisson.predictive` returns one, and a
density layer that could not evaluate the predictive of a conjugate update the library computes
would be a gap with no argument behind it. `LogMass.negativeBinomial(k, alpha, beta)` is therefore
there too, written through the same `lbeta` and checked against the same Stan Math cases.

The vectorised overloads — `LogDensity.normal` in three shapes, `LogMass.poisson`, `poissonLog`,
`bernoulliLogit` and `binomialLogit` on vectors — are what the specification asks for in so many
words: the `...Sum` primitives of `Real[A]` are not meant to be called by a user, and these are how
a model reaches them. Each one checks the lengths and the scale and then delegates; the primitive
keeps the whole vector to one node.

## Phase 3 — transforms, named parameters and models

### D-49. `UnitInterval` is `Interval(0, 1)`, not a transform of its own

Its log Jacobian then carries a `log(1 - 0)` that is exactly zero, so there is nothing to save by
specialising it and one formula fewer to keep right. The fixture's `lub` cases include
`lower = 0, upper = 1`, so the specialisation would have been tested by the same numbers anyway.

### D-50. A matrix is column major, everywhere

Stan stores a matrix column by column and a CmdStan CSV header is in that order, so this library
uses it in all three places a matrix appears: `Block.names` lists `L.1.1, L.2.1, … L.1.2`, the
unconstrained vector holds `u(from + j * rows + i)` for entry `(i, j)`, and the flattened
constrained value of a Cholesky factor is `flat(j * k + i)`.

Picking a different order in any one of them would transpose a matrix read back from a file, with
no error anywhere — which is why one convention is stated once here and asserted in three tests.

### D-51. `ParamSpec` cannot have a cats `Semigroupal`, and the laws are tested anyway

`ParamSpec[T[_]]` abstracts over a type **constructor**: its kind is `(* -> *) -> *`, while cats'
`Semigroupal[F[_]]` wants an `F` of kind `* -> *`. No instance can be written, so `mapN` is spelled
out for the arities two to eight, over `product` and `map`.

The laws are still checked with discipline, on the one shape cats can talk about:
`ParamSpec[[A] =>> V]`, a spec whose value does not depend on the scalar. That is not a degenerate
corner — such a spec still declares blocks, still consumes its slice of the unconstrained vector
and still accumulates a log Jacobian — so `ApplicativeTests` exercises exactly the structure there
is: the layout concatenation and the Jacobian accumulation. Nineteen laws, green.

The `Eq` it needs compares layouts and values exactly and log Jacobians to 1e-12, because
floating-point addition is not associative and the associativity law reassociates exactly those
additions. A dropped block or a dropped Jacobian fails by far more than 1e-12.

### D-52. `.mapN(Params.apply)` is not available; the model writes the lambda

The plan's example writes `.mapN(NowcastParams.apply)`. Scala 3 cannot: it eta-expands a
polymorphic method with `A := Any` before checking it against a polymorphic function type, and the
error says `Found: (Any, Any, …) => Nowcast[Any]`. The model writes the wrapper instead,

```scala
.mapN[Schools]([A] => (mu: A, tau: A, theta: IArray[A]) => Schools(mu, tau, theta))
```

which is a line per model and is explained in chapter 2 of the manual, because the reason is worth
knowing: the assembling function has to work for **every** scalar, since that is what lets one
model be read by the value interpreter, the tape and the oracle. The parameter annotations cannot
be dropped either — `A` is bound by the lambda itself, so there is nothing to infer them from.

The cost is not the verbosity. The field names are declared twice, in the case class and in the
lambda, and two fields of the same type can be swapped without anything failing to compile:
`(tau, mu, theta) => Schools(tau, mu, theta)` is a model wired backwards. The rule that follows,
and that the suites keep: **a model whose blocks share a type needs a test that tells them apart** —
read a point whose blocks have different values and check each field got its own. For the eight
schools that test is already there, because only `tau` is constrained and the log Jacobian is
therefore `u(1)`: a swap makes it fail.

The two alternatives were weighed and refused. A macro building the spec from the case class cannot
use `Mirror`, which does not work on a type constructor `T[_]`, so it would mean hand-written
`Expr`/`TypeRepr` metaprogramming in a library that has none. Dropping the case class for a tuple
puts `p._1._1` in every density. **Confirmed on 5 November 2026: the lambda stays, to be looked at
again at phase 9, where the nowcasting models carry eight blocks and will say whether it hurts.**

### D-53. `ParamSpec` is sealed, and names are checked by `validated`

Every spec comes from the combinators, so `layout` and `read` agree by construction — a
hand-written instance could declare one thing and read another, and nothing would catch it.

Unique names are checked by `ParamSpec.validated`, which returns `Either`, rather than inside
`product`: a combinator that could fail would stop `mapN` being a plain applicative and would make
every model an `Either` of an `Either`. `Model.unconstrain` calls `validated` before reading
anything, so the one operation where a duplicate name does damage cannot be reached without the
check.

### D-54. `BayesError.DuplicateParameter`

A new predictable failure, so a new case: bending `Malformed` (whose job is external input) would
have made the error's own documentation wrong, and `InvalidArgument` would need a `Double` to carry
a clashing name. Adding a case to a sealed trait obliges every exhaustive match on `BayesError` to
grow a branch; none exists, in this repository or outside it, since nothing is published. Appendix
B of the manual describes it with the rest. **Confirmed on 5 November 2026.**

### D-55. `Model.values`, which the sketch does not have

Nothing generic can look inside a `T[Double]`: there is no way to turn a model's parameter object
back into named values. Two things need exactly that — a CSV row is a map from name to values, and
a round trip test needs to go from a point to names and back — so `Model.values(m, q)` reads the
blocks again and names what it finds. It is the inverse of `Model.unconstrain` and the suite
asserts the round trip over random points. Phase 5 needs it twice more: a CmdStan row *is* this
map, and `Summary` reports by name. **Confirmed on 5 November 2026.**

### D-56. `GenerativeModel` carries the data and how to read a model on other data

The sketch is `GenerativeModel(model, generative)` with a `conditionedOn(y)`, and that cannot work:
a `Model`'s likelihood closes over its data, and nothing generic can reach in and replace it. The
case class therefore holds the observed data, a `Y => Model[T]`, and the simulation; `model` is
`conditionedOn(observed)` and is still a member, so the sketch's two names survive.

### D-57. `transforms` depends on `distributions` in test scope only

`Model` is abstract and needs no density, so the published `transforms` artefact does not depend on
`distributions` and the plan's module graph is unchanged. A model to *test* it on does need
densities, so the dependency exists in `Test` only. The eight schools and the model with one block
of every constraint live in `transforms`' test sources for the same reason.

### D-58. The simplex is the isometric log-ratio, and the inverse of `sum_to_zero` was re-derived

Stan Math's `simplex_constrain` is `softmax(sum_to_zero_constrain(y))` today, not stick breaking,
and the fixture says so in as many words. The log Jacobian is `sum(log x) + 0.5 log(N + 1)`.

`sum_to_zero_constrain` is a recursion over an orthogonal basis; its inverse is derived here rather
than ported, because the recursion inverts cleanly: coordinate `N` fixes the last weight, and each
earlier coordinate fixes the next one from the running sum already known. Derived, then checked
against the fixture's fifteen cases and its simplex round trips — which is the order that matters,
since a plausible inverse that is not the inverse would have passed a weaker test.

### D-59. A saturating transform comes back as well as its inverse allows

`atanh` has derivative `cosh²(y)` at `tanh(y)`, so the last bit of a correlation becomes `cosh²(y)`
last bits of the recovered unconstrained value: at `y = 6` that factor is 3.3e4 and the round trip
can be 1e-11 out, at `y = 10` it is 2.4e8 and nothing better than 1e-8 exists. That is arithmetic,
not implementation.

The property that asserts 1e-12 therefore runs on `|y| <= 4` — a correlation of 0.9993 and a
probability of 0.982, which is the whole range a model lives in — and a second test asserts the
conditioning bound `1e-14 cosh²(y)` out to `|y| = 10`. Stating the bound is tighter than a flat
1e-12 everywhere it can be, and honest where it cannot.

### D-60. An error from `Model.unconstrain` names the block

A transform knows a value is negative; only the model knows the value was `tau`. Without
relabelling, reading a bad file reports `value = -1.0 violates value > 0.0` and leaves the user to
find which of thirty parameters it was. `Model.unconstrain` rewrites the error with the block's
name, keeping the transform's rule and the offending value, and keeps the coordinate too where the
transform named one (`ordered, value(1)`). Found while writing chapter 2 of the manual, which is
what a manual is for.

### D-61. `tanh` is not added to `Real[A]`

It is needed in one place, the Cholesky correlation factor, and it is written there from the
operations that exist: `-expm1(-2y) / (exp(-2y) + 1)` for a non-negative argument and its mirror
below zero. The short form, `2 invLogit(2y) - 1`, loses everything near zero — at `y = 1e-10` it
keeps six digits of sixteen — and a branch on the sign keeps every exponential's argument negative
so nothing overflows. One private helper beats a trait member with one caller.

### D-62. The generative part says the same thing twice, and a test closes the gap

`logPrior` evaluates a prior and `samplePrior` draws from one, and nothing in the types makes them
the same prior — the plan lists this as a risk, because a disagreement invalidates every
simulation-based calibration built on it without failing anything.

Two tests close it, and they assert different things. The **identity**: `logPrior` equals the sum
of the three law densities at any point, which is a statement about definitions with no Monte Carlo
in it. The **streams**: `samplePrior` and `simulateData` produce bit for bit what the corresponding
law values produce for the same seed, which pins the duplication to the laws that phase 1b
validated. The third comparison §5.5 asks for — against MCMC samples from `priorOnly` — needs a
sampler and arrives with phase 4.

## Phase 4 — Metropolis, HMC, NUTS and the warmup

### D-63. A `Point` carries the log density, and Stan's integrator carries its negative

Stan's leapfrog works on the potential `V = -log p` and `g = dV/dq`; this library carries the log
density and its gradient everywhere else, and changing convention at the module boundary would
have been worse than converting at one point. So `Point` holds the log density, `Point.potential`
is its negation, and the two places Stan writes `p -= by * g` are written `p += by * gradient`.

That is the whole of the conversion, and it is why the eleven leapfrog cases are asserted **in
Stan's variables**: the fixture states `V`, `q`, `p` and `g`, the suite negates two of them on the
way in and one on the way out, and a sign error there is a sampler that walks uphill and looks
entirely plausible doing it.

### D-64. `build_tree` returns a subtree, and the repository's own port settles what Stan means

`base_nuts.hpp` writes its results into eight references the caller passes in, and two of them
alias the same vector in the recursive call — so reading the C++ alone leaves it genuinely unclear
whether a junction check compares velocities or momenta. For a unit metric they are equal and the
test Stan ships cannot tell.

`reference/build_hmc_fixtures.py`, which built the fixture, is a NumPy port of that code, and the
plan says to read the reference scripts as executable specification. It settles it: the three
checks compare `p_sharp` at the boundaries and use the plain momenta inside the `rho` sums. The
recursion here returns a `Subtree` with those fields named as Stan names them, and the case
`unit_e_nuts_test.cpp` asserts matches to 1e-6 against Stan's own six digits and to 1e-12 against
the port's sixteen.

### D-65. The top-level loop's summed momenta, and where this phase is least pinned

Inside the recursion the fixture pins everything. The doubling loop above it is asserted by no
test Stan ships, and its bookkeeping of `rho` is the one place this phase had to decide rather than
port.

What is implemented: the forward and backward halves keep their own summed momenta; the initial
momentum belongs to **both**, because the starting state is the inner boundary of each; the
whole-trajectory check uses `p0 + rhoBackward + rhoForward` and each junction check uses one side's
sum plus the first momentum of the other. That is the exact analogue of the recursion's own combine
step, which is pinned, and it is the only arrangement under which `rho` is the sum of the momenta
over the states each check is about.

What stands behind it instead of a fixture: the tree depth on an easy target (mean between 1 and 4,
and a criterion that never fired would saturate at 10), the depth saturating at exactly the maximum
when the step size is tiny, the funnel reporting divergences where a Gaussian reports none, and
every statistical test in the suite. The mutation that makes the loop ignore its criterion is
caught by the variance of a standard Gaussian coming out at 0.63.

### D-66. `Adaptation.update` stays pure, and the driver re-runs the heuristic

Stan re-runs the step-size heuristic at every window end, which needs a momentum draw, so its
adaptation is not a pure function. Keeping `update(t): Either[..., Adaptation]` as the
specification sketches it means the effect has to live somewhere else, and the driver is the right
somewhere: it already has the generator.

Three members beyond the sketch make that possible: `metricChanged` says the last update
re-estimated the metric, `restarted(stepSize)` injects what the heuristic then found and sets
`mu = log(10 * stepSize)` as Stan does, and `completed` is the tuning the sampling phase uses —
`exp(xBar)`, the averaged iterate, which is not the last `exp(x)` and is the entire point of dual
averaging.

### D-67. `Mcmc.run` takes a starting point, `runNuts` takes an `Init`

The sketch gives `run` an `init: Init`. It cannot: `Init.Given` names parameters and
`Init.PriorMedian` draws from a prior, and `run` knows only a `DiffFn` — there are no names and no
prior at that level. `run` takes the unconstrained point, `Mcmc.startingPoint` turns an `Init` into
one given a model, and `runNuts` does both for you. Conflating them would have made `run` demand a
model it never uses.

### D-68. The dense metric is chosen through `AdaptationSettings`

The specification asks for `Metric.Dense` as an option and says nothing about how to ask for it.
It is `AdaptationSettings(metric = MetricShape.Dense)`, because the shape of the mass matrix is a
property of the warmup that estimates it, not of the kernel that uses it: the same NUTS kernel runs
with either. `Mcmc.run` builds the identity in the requested shape and the windows fill it in.

### D-69. `Generative.named`, without which `Init.PriorMedian` cannot exist

`Init.PriorMedian` needs prior draws **on the unconstrained space**, and a prior draw is a
`T[Double]`. Nothing generic can look inside one (D-55), so the generative part has to be able to
name its own draw: `named(p): Map[String, IArray[Double]]`, which `Model.unconstrain` then accepts.

The author of the parameter object is the only one who can write it, and they have already written
the `mapN` lambda that assembles it, so it is a mirror of something they have. Simulation-based
calibration will need exactly the same thing in phase 6, to rank a drawn parameter against its own
posterior.

### D-70. `Chain.draws` is on the unconstrained space

That is what the sampler produces, and what a diagnostic **of the sampler** should see: a tree
depth or an energy is about the space the trajectory ran in. `Model.constrain` and `Model.values`
convert, and the chapter-0 example does so in its first line. Returning constrained draws from
`Mcmc.run` was the alternative and it cannot be done at all, since `run` has no model.

### D-71. The acceptance after warmup is above the target, not at it

The specification's criterion is "mean acceptance after warmup of 0.8 ± 0.05". A faithful port does
not do that, and the measurement is in docs/validation.md: the last fifty warmup iterations average
0.77 to 0.79, which is the statistic dual averaging controls, while sampling with the step size it
leaves behind realises 0.84 to 0.93.

The reason is that the step size is restarted at the last window end and then averaged over only
fifty iterations, with `t0 = 10` deliberately slowing the early ones, so `exp(xBar)` is
conservative. Stan behaves the same way — `accept_stat__` after warmup routinely sits above
`adapt_delta` — so the criterion is implemented as the thing it is about: the realised acceptance
is above the target and below one, and **twice** the adapted step size falls below the target. That
pins the step size from both sides without asserting a number the algorithm does not produce.

### D-72. A random walk is given its own acceptance target

Dual averaging aims at whatever target it is given, and 0.8 is the right one for a Hamiltonian
method only. Driven to 0.8, a random walk gets a step size so small that it crawls: the first
version of the three-kernel test had it reporting a variance of 0.52 where the truth is 1, with an
autocorrelation estimator that cheerfully claimed eight hundred effective samples.

So the random-walk tests pass `AdaptationSettings(targetAccept = 0.234)`, the optimal for a
high-dimensional random walk, and the suite additionally checks the **span** of the draws: a chain
that has not explored can look precise to an autocorrelation estimator, and the span is what
catches it. Both facts are in the manual's chapter 3, because a user reaching for the random walk
will meet the first one.

### D-73. The back substitution lives with the metric, not in `core.linalg`

Sampling a momentum from a dense metric means solving `L' p = z`, and the specification limits
`core.linalg` to five operations "and nothing more". The upper-triangular solve is private to
`Metric`, which is its only caller. If a second caller appears it moves.

### D-74. An effective sample size in `testkit`, until the validated one exists

Every Monte Carlo threshold in this phase is `sd / sqrt(ESS)`, because a thousand correlated draws
do not carry a thousand draws' worth of information and a threshold built on the thousand fails a
correct sampler. The diagnostics module, where the validated estimator belongs, arrives in phase 5.

`testkit.Ess` is Stan's estimator — pooled within-chain autocorrelation, truncated by Geyer's
initial positive sequence — written for this phase and used **only to size thresholds, never as a
reference value**. Phase 5 validates `diagnostics` against Stan's own fourteen numbers, and these
suites point at it then. The limitation to know meanwhile: it overstates the effective sample size
for a chain that has barely moved, which is why the random-walk tests also check the span.

### D-75. The sampler needed no local mutation after all

The specification allows the leapfrog and the tree to mutate local arrays, as `core.linalg` was
allowed to in phase 1b. Neither needed it: a leapfrog step is two `IArray.tabulate`s and a subtree
is a case class, and at the sizes a gradient already costs — one tape, one target evaluation per
step — the allocation is not what the time goes on. The register of encapsulated mutation stays at
the 27 sites it had, all of them in `Fft`, `Batch` and the tape.

## Phase 5 — diagnostics and CmdStan's CSV

### D-76. The diagnostics follow Stan, and the gap with ArviZ is asserted

ArviZ 1.x computes three of these differently: the basic effective size on split chains where Stan
does not split, the upper tail with a strict inequality where Stan includes the boundary, and
`mcse_sd` from a different effective size. On the four chains Stan's own tests use, the tail
effective size of `lp__` is 1591.97 here and 1544.09 there — 3 %, and 15 % on `mcse_sd`.

Following Stan is the specification's decision; what this phase adds is that the **disagreement is
a test**. `ConvergenceSuite` asserts that our value is within Stan's tolerance of Stan's and more
than ten tolerances away from ArviZ's, for each of the five statistics where they part company. A
later change that quietly adopted ArviZ's convention — which is a one-character change in two
places — would fail there rather than be discovered by a user comparing two summaries.

### D-77. `reference/stan_analyze_reference.py` is read as the specification

The C++ in `stan/analyze/mcmc` leaves several things undetermined on the cases its own tests cover:
the chains there have an even number of draws, so the split's `floor((n+1)/2)` is invisible; no draw
sits exactly on the 95th percentile, so the inclusive comparison is invisible; and the
autocorrelations are nowhere near the floor on `tau`.

The repository's own line-by-line port settles all of it, and the plan says to read the reference
scripts that way. Every detail of `Convergence` comes from it, and the fourteen values are asserted
twice: against Stan's own assertions at Stan's tolerances, and against the port at 1e-10, which is
the sharper comparison wherever Stan's tolerance is loose.

### D-78. A diagnostic returns `NaN`, never an error

A constant chain, an infinite value, three draws: the answer is `NaN` and not a `Left`. A
diagnostic is a number a user reads in a table, and a `NaN` in that table **is** the diagnosis —
"this row cannot be summarised" — whereas an `Either` would make the caller choose between
discarding the row and discarding the table. The thresholds are Stan's: four draws for an effective
size, eight for `mcse_sd`, because a fourth moment of a shorter sample says nothing.

This is the same reasoning as the out-of-domain contract of the densities (D-33), applied to a
different kind of function.

### D-79. `Draws` is a rectangle, built only through its smart constructor

Every chain the same length, every iteration as wide as there are names, no name twice. A ragged
`Draws` would be summarised without complaint and the summary would be of something that is not a
posterior, so `Draws.of` returns `Either` and the case class constructor is private. It lives in
`core` for the reason D-5 gave: `stan-io` parses into it, `diagnostics` reads it and `predictive`
will consume it, and none of the three should depend on another for a data type.

The draws it holds are **unconstrained** when they come from `Mcmc` (D-70) and constrained when
they come from a CmdStan file, which is CmdStan's own convention. Nothing in `Draws` knows which;
`Summary.of` constrains because it is given a model, and `StanCsv` does not because it is not.

### D-80. `StanCsv.render` writes what CmdStan's reader needs, not what the data holds

`cmdstanpy.from_csv` finds the sampled rows by locating four blocks in order — a header line
beginning with `lp__`, a comment block after it, the rows, and a comment block after those — and
refuses the file outright if any is missing. It then validates the configuration it read, so a
declared warmup of zero with the adaptation engaged is rejected as well.

A `Draws` holds none of that: no step size, no mass matrix, no elapsed time. The renderer therefore
writes **placeholders** where the format needs a value — a step size of 1, a unit diagonal over the
columns that are not the sampler's own, zero seconds — rather than inventing plausible-looking
numbers, and the scaladoc says which is which. The alternative, threading a `Tuning` into
`stan-io`, would make it depend on `inference` for a comment line.

Both rules were found by running the check rather than by reading the format: the first renderer
left out the timing block and declared no warmup, and `reference/check_cmdstan_csv.py` reported
each in turn. `StanCsvSuite` now asserts all four rules, so `sbt test` catches a regression without
Python.

### D-81. The ArviZ check goes through `cmdstanpy`, because `from_cmdstan` is gone

The specification's criterion is "CSV read by `arviz.from_cmdstan`". ArviZ 1.3 has no such
function — the CmdStan reader moved out to `cmdstanpy`, and `arviz_base.from_cmdstanpy` converts
its result — so the check is done through both. That is stricter than the original: the file has to
satisfy Stan's own parser first and ArviZ's conversion second.

`reference/check_cmdstan_csv.py` does it, needs no CmdStan installation and no compiler, and also
compares the values against the originals they were rendered from. What it reported is in
docs/validation.md.

### D-82. `Summary.table` formats in the root locale

`f"$x%.4f"` formats in the **default** locale, which writes a comma for the decimal point in much
of the world: the same fit printed `-54,1903` on the machine this was written on and would print
`-54.1903` on a CI runner. A table nobody can diff between two machines is not a table.
`String.format(Locale.ROOT, ...)` everywhere, and the suite asserts that no comma appears in a
rendered table.

Found by reading the manual's own rendered output, which is the second thing chapter 2 found that
way (D-60).

### D-83. `essTailParts` is public, so that one character can be tested

The tail diagnostic is the smaller of two effective sizes, and on Stan's own chains the lower tail
is the smaller one — so the inclusive comparison in the **upper** indicator changes nothing there,
and a mutation that broke it survived the whole suite.

The two halves are therefore public, and the suite asserts the upper one on a sample built to
separate the conventions: four chains of the integers 0 to 19, whose 95th percentile is exactly 18,
reached by 96 draws and exceeded by 48. The inclusive convention gives 533 effective samples and
the exclusive one 1133.

### D-84. `LogDensityUnnormalized` is deferred a third time, now with a reason that can be measured

Phase 4's known limits said the benefit would become measurable once a sampler existed. It is, and
it is small: the normalising constants of the densities a model uses are `Real[A].const(...)`
leaves and arithmetic that does not depend on any parameter, so dropping them saves a handful of
tape nodes per density per evaluation — of the order of ten nodes against the 89 a multilevel model
already costs, and none at all against the 4955 of the reporting-triangle model.

The cost of having it is a second copy of nineteen densities to keep correct, and the phase-2
benchmark is the instrument that would justify it. Deferred until a model is large enough for the
saving to show, and this decision records the arithmetic so the question does not have to be
reopened from scratch.

## Phase 6 — the reference posteriors and simulation-based calibration

### D-85. The nine Stan models are ported by hand, and two of them cannot be line by line

The specification says the Stan programs are to be translated by hand, and the translation is what
is being tested: the reference summaries came from Stan itself on the same data and the same model,
so a disagreement is either a bug in this library or a mistranslation. Seven of the nine are
mechanical. Two are not, and both say something about the library's own limits.

**`garch11` has a bound that depends on another parameter**: `real<lower=0, upper=(1 - alpha1)>
beta1`. `Constraint` has no case for that and should not — a constraint is a value written in a
model's source, and one that closed over another parameter would have to be read from the
unconstrained vector before it could be used to read the unconstrained vector. The translation
declares `beta1Raw` on the unit interval, sets `beta1 = (1 - alpha1) beta1Raw`, and adds
`log(1 - alpha1)` to the target by hand — which is **exactly** Stan's own transform for that
declaration, term for term. The posterior is the same one, the comparison confirms it, and the
mutation that drops the Jacobian moves `alpha1`'s posterior mean by 0.047 against a budget of
0.013.

**`gp_pois_regr` needs the Cholesky factor of a parameter-dependent matrix.** §5.3 leaves a
differentiable `cholesky_decompose` out of `Real[A]` on purpose, because models are expected to
parameterise a factor directly. That is right for a correlation matrix and wrong for a Gaussian
process, whose covariance is built from its parameters. The recursion is therefore written in the
model, out of the scalar operations, and the tape differentiates the composition — no new primitive
and no hand-written adjoint. At eleven dimensions it is a few hundred nodes; at a hundred it would
be the wrong approach, and that is the honest limit to record.

### D-86. The acceptance rule is the collection's own, and both sides are noisy

`|mean_ours - mean_ref| <= 4 sqrt(mcse_ours² + mcse_ref²)`, and the same for the standard deviation
with `mcse_sd`. The reference is ten chains of a thousand thinned draws of a long Stan run — a
Monte Carlo estimate, not a truth — so the threshold is built from **both** errors. A fixed
tolerance would be either unfalsifiable or impossible depending on the parameter.

The suite asserts its own fit's convergence first, and separately: a comparison against a chain
that has not mixed says nothing, and the message should say that rather than report a gap. The
mutation that breaks the mixture's weights is caught by that guard rather than by the comparison —
R-hat 1.73 and a bulk effective size of 6 — which is the right failure to see.

### D-87. `Sbc` lives in `diagnostics`, and thins before ranking

Simulation-based calibration is not in the specification's module sketch; it appears in §6.5 as a
test procedure. It is in `diagnostics`' published API because three later phases need it — the
reduced delay model of phase 9, the component combinations of phase 10, the correlated-effects
model of phase 6b — and a procedure reimplemented in three test suites is a procedure that will
differ between them.

It is also the only test in the library that checks a generative model's **two readings against
each other** end to end: the prior as a density against the prior as a sampler, the likelihood
against the simulator, every Jacobian, and the sampler. The suite proves it has that power rather
than claiming it: a model whose density says `Gamma(3, 4)` while its simulation draws `Gamma(3, 1)`
gives a chi-squared of well past the 0.1 % point, where the honest version passes.

The samples are **thinned** before ranking, as Talts et al. require: consecutive samples of a
Markov chain are correlated, and ranking against correlated samples gives the histogram a shape of
its own. The mutation that removes the thinning is caught on the multilevel model, where the
autocorrelation is highest, and not on the two easy ones — which is exactly where it should bite.

`uniformityChiSquared` returns `NaN` when the bins do not divide the possible ranks evenly, rather
than a statistic computed against unequal expected counts, which would reject a correct sampler.

### D-88. Appendix A's table is checked by a script, not by a suite

The specification asks for the table to be "read by `StanNamesSuite`". It is read by
`scripts/check-stan-names.py` instead, which looks each Scala name up in
`modules/*/src/main/scala` as a definition, an enum case or an object.

Two reasons. The repository already runs its other two table checks that way — the register of
local mutation and the manual's vocabulary (D-7, D-34) — so this is one mechanism rather than two.
And a Scala suite would check the names **compile**, which they do by being written down, whereas
the failure worth catching is a name that was renamed and a row that was not; a grep over the
sources catches that and a compiled reference cannot, because a renamed function would stop the
suite from compiling at all and the CI error would point at the test rather than at the table.

The check is one-directional on purpose: a function this library has and the table does not is not
an error, because the table is for names Stan also has.

### D-89. ArviZ is the reference for leave-one-out and power scaling, with its source read as the specification

Stan does neither of these: `loo` lives in the R package of that name and `priorsense` in another,
and neither ships a fixture this repository can read. ArviZ does both in Python, from the same
papers (Vehtari, Simpson, Gelman, Yao, Gabry 2024 for the smoothing; Kallioinen, Paananen,
Bürkner, Vehtari 2024 for the scaling), so `reference/multilevel.json` carries `az.loo` and
`az.psense` on fixed draws and the suites match them at 1e-6.

There is no port of either algorithm to read as a specification — the earlier phases had
`reference/build_hmc_fixtures.py` and `reference/stan_analyze_reference.py` — so the installed
ArviZ source was read instead, and three behaviours in it are decisions rather than consequences
of the papers:

- the tail length is `floor(3 sqrt(n / r_eff))` when `n r_eff > 225` and `floor(n / 5)` otherwise;
- the empirical-Bayes fit of the generalised Pareto distribution uses a grid of
  `30 + floor(sqrt(n))` inverse scales and the priors `prior_bs = 3`, `prior_k = 10`, which shrink
  the shape towards 0.5;
- a smoothed weight is capped at the largest observed one, so the smoothing never invents mass.

Each of the three is a mutation in `docs/validation.md`, because each is invisible to the papers
and visible to the fixture.

### D-90. Stan's tail effective size reproduces ArviZ's `r_eff` exactly, so there is no second one

PSIS needs a relative effective number of samples per observation to choose its tail length, and
ArviZ computes it with its own effective-size function, which differs from Stan's in the upper
tail (D-80, D-83). On the four arrays of `multilevel.json` the two agree to every digit that
matters — 0.870602 and 1.013745, giving tail lengths of 203, 203, 203 and 188 — because the
divergence between them only shows up with ties and these draws are continuous. So
`Convergence.essTail` is what `PowerScaling.sensitivity` calls when the caller does not supply a
value, and no second effective size exists in the library. If a future fixture has ties, the
tail length will differ by one draw and the suite will say so.

### D-91. `tail = "both"` changes the tail length and still smooths only the right tail

ArviZ's `_ps_tail` takes a `tail` argument, and with `"both"` it halves the available draws and
floors the length at five — and then smooths the right tail only. Power scaling calls it that way.
Reading the argument as "smooth both tails" and implementing that would have been the obvious
thing and would have put every sensitivity out by a few parts in a thousand, which the 1e-6
comparison would have caught as a mystery. `Pareto.tailLength(draws, relativeEss, bothTails)`
therefore takes a flag that changes the **length** and `Pareto.smoothRightTail` is named for what
it does.

### D-92. `PointwiseLikelihood` is a trait of its own, mixed into a model, and lives next to `Loo`

A log likelihood per observation is not part of being a model — the sampler never needs it, and a
model whose likelihood does not factorise over observations (a Gaussian process, a time series with
a shared history) cannot provide one honestly. So it is a separate trait, a model declares it by
mixing it in, and cross-validation asks for `Model[T] & PointwiseLikelihood[T]`.

It lives in `Loo.scala` rather than in `transforms` with `Model`: it exists for cross-validation,
`diagnostics` is where cross-validation lives, and putting it next to `Model` would suggest every
model ought to have one.

The one law — the sum of `pointwise` is `logLikelihood` — cannot be checked by the type, so it is
asserted for every model in the test suites and stated in chapter 4. Nothing in the library can
verify it for a user's model, which is why the manual says to test it.

### D-93. `Loo.compare` takes the standard error of a difference from the pointwise differences

The elpd of two models fitted to the same observations are not independent — the same observation
is easy or hard for both — so `sqrt(se₁² + se₂²)` overstates the error of their difference, often
by a factor of several. The error is computed instead from the per-observation differences, which
is what `loo_compare` does and what makes a difference of 0.3 against an error of 1.2 readable as
"cross-validation cannot tell these apart".

The mutation is the obvious wrong version, and the test that catches it compares a model with
itself shifted by a constant: the difference is exact, so its error must be exactly zero, and the
quadrature version reports something large.

### D-94. `Hierarchy` lives in `predictive`, and pairs each sample's location with its own scale

Predicting a group that is not in the data is prediction, not inference, so `Hierarchy` is in
`predictive` alongside the simulators rather than in `nowcast`, which is one application of it, or
in `diagnostics`, which is about the fit it already has.

`newGroup(levels, scales)` takes two arrays of the same length and uses index `s` of both. The
alternative — a location and a scale drawn independently from their marginals — is a mistake that
produces plausible-looking numbers from a posterior nobody has, because the two are correlated in
every multilevel fit. Both functions return an empty array rather than an error when the lengths
disagree, which is the library's rule for a vectorised operation with nothing to compute.

### D-95. A matrix of `A` is built by `Real[A].matrixOf` or `rowsOf`, never by `IArray.tabulate`

Phase 6b found this by crashing. For an abstract `A`, the compiler cannot synthesise a `ClassTag`
that knows a row is a `double[]` rather than an `Object[]`, so
`IArray.tabulate(n)(i => IArray.tabulate(m)(...))` inside a generic method builds an array of
objects — and the first caller that has fixed `A` to `Double`, which is every model read for its
values rather than its gradient, fails the cast the compiler inserted on the way out. The failure
is a `ClassCastException` with nothing in the source to point at.

`ClassTag[A].wrap` is the one thing that knows, so `Real[A].rowTag` is `ClassTag(tag.wrap.runtimeClass)`
and `matrixOf` and `rowsOf` are built on it. `ParamSpec.matrix` and `ParamSpec.choleskyCorr` now go
through them, which is the library-side fix: before it, **any** model with a matrix parameter
crashed in `pointwise`, in `derived` and in predictive simulation, and the nine ported posteriors
never noticed because they are only ever read generically.

The test is a runtime-class assertion plus the operation that provoked it — `matVec`, whose
signature is `IArray[IArray[Double]]`, so calling it *is* the cast. Three `final` methods were
added to a public trait to fix it; that is the smallest change that stops user models hitting it,
since a `private[bayes4s]` helper would leave the trap in place for anyone writing a model outside
the library. **Confirmed on 6 November 2026: the three methods stay on `Real[A]`.**

### D-96. Simulation-based calibration ranks derived quantities, and names a quantity it cannot find

`Sbc` compared the drawn parameter against `Model.values`, which reads the declared blocks only. A
generative part that names a derived quantity — `rho`, read off a Cholesky factor — therefore had
no column to be ranked against, `exists` was false for every sample, and the rank was zero in every
replication: a correct model reported as the worst failure the test can express, with nothing
saying why. The correlated model of phase 6b is the first model to name one, which is why this
surfaced now; it gave a chi-squared of 48 on four bins where uniformity gives about 3.

The posterior values now include `derived`, and a name in `named(truth)` with no column is a
`BayesError.UnknownParameter` rather than a rank — the same choice as everywhere else in the
library, that a missing thing is an error and not a default. A length that disagrees is a
`DimensionMismatch`. With the fix the correlated model's `rho` is calibrated at 256 replications.

### D-97. The multilevel test models live in `diagnostics`' test sources, and the manual repeats them

`MultilevelModels` is in `modules/diagnostics/src/test/scala`, where the slow suites can see it
(the `slow` configuration extends `Test`) and `CalibrationModels` already lives. It is not in
`testkit`, which carries the reference data and the comparison helpers and would then need a
dependency on `transforms` and `distributions` for the sake of six test models.

The consequence is that chapter 11 cannot import them and writes its own, which is the right
outcome for a manual anyway: a chapter that says "use `MultilevelModels.varyingInterceptsNonCentred`"
teaches nothing, and a chapter whose models are compiled by mdoc proves that what it shows is what
a reader can write.

### D-98. The example where leave-one-out prefers partial pooling needs two datasets

The phase's criterion asks for "an example where LOO prefers partial pooling to both extremes".
Ranking first is easy and says little; a difference larger than its own standard error on **both**
sides turned out to be impossible, and not for want of data.

The reason is structural. The multilevel fit interpolates between the extremes — `tau` large is no
pooling, `tau` near zero is complete pooling — so it is always close to whichever extreme the data
supports, and the difference on that side is inside the noise by construction. Twenty datasets were
measured: the rejected extreme comes out at 1.8 to 9.9 standard errors, and partial pooling is
never more than 0.8 behind the leader. One configuration — a between-cluster spread of half the
within-cluster one over thirty clusters of three — genuinely rejects partial pooling, by 2.3
standard errors, because the data says `tau` is zero and the hierarchical model still pays for
estimating it.

So the suite uses two datasets, each rejecting one extreme, and asserts what is true of both:
partial pooling ranks first, and the extreme that is wrong for the data is rejected. The honest
claim is that partial pooling is the only model rejected on neither, and chapter 11 says so in
those words rather than overselling the comparison.

### D-99. `Ensemble`'s monoid is lawful across shapes, and the laws are checked to a few ulp

The specification asks for a `CommutativeMonoid[Ensemble[K]]` that sums "sample by sample, key by
key" and says nothing about two ensembles of different shapes. An instance that required equal
shapes could not be a monoid: `combine` would have nowhere to put a mismatch, since its signature
has no room for an error and `empty` has neither periods nor samples.

So `combine` is total. The periods become the run covering both (`Periods.cover`), the samples the
larger count, and a cell neither side has is zero. `Periods.cover` is associative and so is `max`,
so the instance is associative and commutative for **every** value rather than only for the ones a
simulation produces — where all ensembles share a shape and no padding ever happens.

Padding samples positionally deserves a word, because it looks like a statistical claim and is not
one. Sample `s` of two ensembles over the same run came from the same posterior sample — that is
what `Simulator.run` guarantees — which is exactly why they may be added. Two ensembles with
different sample counts did not come from one run, so the shorter contributes zero where it has
nothing; the alternative is to refuse, and a monoid cannot refuse.

The law check uses an equality that allows a few ulp while the published `Ensemble.eq` stays exact.
`Ensemble`'s monoid is associative over the reals: each cell is a sum of the same terms whichever
grouping is used. `Double` addition is not associative, so the two groupings can land one ulp apart
— measured at 1.776e-15, exactly `ulp(10.0)` for cells drawn from (-5, 5). Publishing a tolerant
`Eq` would be the wrong semantics for a value type and would hide real differences; claiming a law
`Double` does not obey would be false. The suite therefore asserts the gap is bounded by a few ulp
and never claims it is zero.

### D-100. `Simulator.run` stacks futures into rows; summing them by monoid would collapse them

§5.8 comments `run` with "somme par monoïde". Read literally — sum the per-future ensembles — it
would add future 1 to future 2 and leave a single story where there were a thousand, which
contradicts the same section's rule that quantiles are computed at the end from an ensemble.

What `run` does is place each future in its own row, which **is** the monoid sum of the per-future
ensembles once each has been placed in its own row: disjoint rows, so addition is placement. The
suite asserts exactly that — the stacking and the literal monoid fold agree cell for cell — so the
specification's sentence is satisfied rather than reinterpreted. Stacking is what the code does
because the monoid version allocates a full matrix per future to hold one row of content.

### D-101. `SimulatedFuture` exists, and a contribution outside the run is dropped

Between a `ContributionRule`, which returns contributions at offsets from an event's close, and an
`Ensemble`, which is indexed by offsets from the start of a run, something has to do the placement.
§5.8 sketches no such type, so one is added: `SimulatedFuture[K]`, a total per key per period that
events are added to one at a time. It is the smallest thing that can hold a future under
construction, and it is what makes the placement testable on cases built by hand — which the
phase's own criterion asks for.

A contribution landing outside the run is **dropped**: not clamped to the last period, not wrapped,
not collected in an overflow bucket. Clamping is the tempting choice and it is the wrong one: it
piles every delay longer than the horizon onto the final period and makes that period look busy,
which is a bias towards the end of every forecast. Dropping states what is true — the run does not
cover that period — and the suite asserts both halves, that nothing lands outside and that nothing
is clamped inside.

### D-102. A stratum's pool is the whole item array under a weight vector that is zero outside it

`TemplatePool[P]` is generic in the payload and holds `IArray[P]`. Building a sub-array per stratum
would need a `ClassTag[P]` — an `IArray` of an abstract type cannot be built without one (D-95) —
and that bound would appear in every signature that touches a pool, for the sake of copying
payloads around.

Instead a stratum's pool is the same `items` array with a weight of zero outside the stratum. The
alias table does the selecting, nothing is copied, no payload is moved, and `TemplatePool` needs
nothing of `P` at all. `StratifiedPools` keeps the stratum sizes separately, because a pool's
`size` is now the number of items rather than the size of the stratum and the mixing weight needs
the latter.

### D-103. `ModelError` carries the term, not its calibration, and the lognormal bias is documented

§5.8 asks for `eta ~ Normal(0, sigma_err)` on the log rate with "`sigma_err` calibrated by grid on
past dates". The term is here; the calibration is not, because scoring a grid of candidates against
what actually happened is a backtest, and `Backtest` arrives with the scoring module in phase 8.
Delivering a calibration without a scoring rule would mean inventing one here and replacing it
there.

What is delivered instead of a second API is a fact, asserted: multiplying a rate by `exp(eta)`
leaves the median alone and raises the mean by `exp(sigma²/2)` — 4.6 % at a sigma of 0.3. A
mean-preserving variant (`exp(eta - sigma²/2)`) was deliberately **not** added: it is not in the
specification, and which of the two a forecast wants depends on what the forecast is for. The
scaladoc and the manual say what the term does to a mean so that nobody meets the 4.6 % by
accident.

### D-104. `Periods` lives in `predictive`, and aggregating periods waits for `forecast`

The period axis is what a simulated future is indexed by, so it sits with the simulation rather
than in `core` — nothing below `predictive` has any use for a run of months. Quarters are the
calendar's own, from `java.time`: a run that starts in February starts mid-quarter and its first
quarter is short, which is the answer a report wants and not the one counting in threes gives.

Summing an ensemble's months into quarters is **not** here. It is one line for a caller who has
`Periods.quarterOf`, and §5.8 gives it to `forecast` in phase 9, where quarterly output is asked
for; adding a second aggregation API now would mean guessing its shape before its use exists.

### D-105. The `Ensemble` generators live in `predictive`'s test sources, not in `testkit`

§6.9 puts the ScalaCheck generators of law-tested types in `testkit`. For `Ensemble` that is a
cycle: `testkit` would need `predictive`, which depends on `testkit` for its own tests. Same shape
as D-97 and the same resolution — the generator is in the suite that checks the laws, where it is
also the only place it is used.

### D-106. Counts below the visibility threshold are `NaN`, not errors and not uncorrected

`ReportingTriangle.correctedCounts` divides an observed count by the share of its events that could
have been reported by now. For the newest creation period that share is near zero, and dividing by
it turns one early report into an enormous estimate — the classic way a nowcast explodes at the
right-hand edge.

Three answers were possible: refuse the whole call, return the uncorrected count, or return `NaN`
for the periods that cannot be corrected. The first punishes the periods that are fine; the second
is the dangerous one, because an uncorrected count looks like a number and will be plotted next to
corrected ones. `NaN` is the library's existing convention for "outside the domain" (2.1), and it
propagates rather than misleading.

### D-107. `Special.phi` is added to `core`: Stan's `Phi`, written through `erfc`

The Gaussian CRPS needs a standard normal cumulative function and the library had none — only
`invPhi`, its inverse, since phase 1b. So `Special.phi` is added, named by 2.9 (Stan's function is
`Phi`, and the pairing `phi`/`invPhi` says which is which), and it is a **public addition to
`core`** rather than a private helper in `scoring`, because a cumulative normal belongs with the
other special functions and every later module will want it.

It is `0.5 erfc(-x/sqrt 2)` rather than `0.5 (1 + erf(x/sqrt 2))`. The two are equal on paper and
not in floating point: at `x = -10` the value is 7.6e-24, and the `erf` form computes it as the
difference of two numbers near one, losing every digit. `Erfc` comes from
`commons-numbers-gamma`, which the build already depends on, so no dependency is added.

### D-108. The sample CRPS uses the `1/n²` estimator, not the unbiased `1/(n(n-1))`

The second term of `E|X - y| - 0.5 E|X - X'|` can be estimated two ways, and they are different
numbers: `1/n²` counts the `n` zero terms where a sample is paired with itself, `1/(n(n-1))` omits
them and is unbiased for the population quantity. properscoring, and therefore the reference values
in `python/scoring.json`, use `1/n²`; so does `scoringRules`' `crps_sample` by default.

This library follows them, and the scaladoc says so, because the two differ by a factor
`n/(n-1)` on the spread term — half a per cent at `n = 100`, far above the 1e-12 the suite asserts
— and a library that quietly chose the other one would disagree with every published score while
looking right. The mutation that switches estimators is in `docs/validation.md`.

### D-109. Both forms of the score are public, and the suite says they are one function

`Crps.energy` is `O(n²)` and nothing should call it on a real forecast; `Crps.ensemble` sorts and
is `O(n log n)`. Keeping the slow one private was tempting, and would have made the phase's own
criterion — "the sorted form equals the energy form" — a statement about a private method that a
user could not check.

Both are public. The definition is readable, the fast one is the one to use, and the property that
links them is a public property. The sorted form's trick is that `sum_i sum_j |x_i - x_j|` is
`2 sum_i (2i - n - 1) x_(i)` once sorted, which needs no special case for ties; the tied case from
the fixture is asserted to the bit.

### D-110. A tie counts as half a sample in the transform, and the bias is positive when the forecast is too high

`Calibration.pit` counts `#{x < y} + 0.5 #{x = y}`, the expectation of the randomised transform.
The alternative — counting ties in full — makes any forecast with repeated values, which is every
count forecast, pile its transforms at the top of the range and look miscalibrated when it is not.

`bias` is `1 - 2 pit`, which is scoringutils' convention: **+1 means the forecast sat entirely
above what happened**. The sign is worth stating because it is invisible when wrong — a bias
reported with the wrong sign is still a plausible-looking number — and the suite pins both ends.

### D-111. `Backtest` scores a sequence; it does not produce it

A leave-future-out backtest has two halves: refit the model at each origin so that no fit has seen
its own target, and score what each fit predicted. The second half is here. The first needs a model
and a sampler, and `scoring` depends on neither — by the module graph, and deliberately, so that
the arithmetic everyone compares against is in a module with nothing behind it.

`Backtest.run` therefore takes the forecasts as given, one per date, and the protocol that produced
them belongs to the caller. The scaladoc says so, because a "backtest" that silently scored
in-sample forecasts would flatter every model and look identical from the outside.

### D-112. The block bootstrap returns its replicates, not a standard error

`blockBootstrap` gives back one mean per replication rather than their standard deviation. A
standard error is one summary of them, a compatibility interval is another, and a histogram is the
honest thing to look at when the score distribution is skewed — which a CRPS over a short run
usually is.

Blocks, rather than single dates, because scores on neighbouring dates are correlated: a model
wrong in March is usually wrong in April. The suite measures what that costs — on an AR(1) run of
scores with `phi = 0.9`, where the true inflation over the independent standard error is about
4.4, blocks of 40 give an error more than 1.5 times the ordinary one while blocks of one reproduce
the ordinary one's blindness.

### D-113. An infinite sample makes the score `NaN`, because the two infinities do not cancel

Found by the totality test rather than reasoned out in advance. With a sample at infinity, both
`E|X - y|` and `E|X - X'|` diverge, so the score is `∞ - ∞`: indeterminate, not infinite. `NaN` is
therefore the correct value and not a shortcut, and the scaladoc says why so that nobody
"improves" it into an infinity later.

### D-114. Scored rows are generic in the date, rather than using `Periods`

`Scored[D]` and `BacktestResult[D]` take whatever the caller indexes its forecasts by — a
`YearMonth`, an `Int`, a label. `Periods` lives in `predictive` and `scoring` does not depend on
it; inventing a second period type here would be worse, and depending on `predictive` would put a
simulation module behind a scoring module that needs nothing from it.

The only thing the backtest does with a date is carry it through to the scored row, so a type
parameter costs nothing and keeps the dependency out.

### D-115. `RateComponent.project` takes the future's times, not a horizon

§5.10 sketches `project(p, group, horizon: Int, seed)`. A trend cannot be projected from a count:
"three periods ahead" is three months only if the time covariate is months, and the component has
no way to know what the caller's `t` meant — it is handed `t` for the data and nothing else.

So `project` takes `futureT: IArray[Double]`, the times to project at, and its length is the
horizon. The component stays stateless, which is what lets a model hold two of them without
either knowing about the data, and the suite checks the projection against the trend line
evaluated at exactly those times.

### D-116. `Mcmc.draws` bridges a fit to named values, because nothing did

A `Chain` carries unconstrained points; `Draws` carries named constrained ones. The first is what
the sampler needs, the second is what a CSV row is, what ArviZ reads, and what a predictive check
has to invert back into parameters. Nothing in the library turned one into the other — which means
`StanCsv.render`, shipped in phase 5, has had no way to be called on a fit since phase 5.

`Mcmc.draws(model, chains)` is that bridge, in `inference` because it is the lowest module that
sees both a `Model` and a `Chain`: `Draws` lives in `core` and knows nothing of models, and
`transforms` knows nothing of chains. It writes the **declared** blocks only, in `params.names`
order, because that is the shape `Model.unconstrain` can invert — a derived quantity has no
unconstrained point of its own.

`Checks.posteriorPredictive` reads such a row back by walking the model's own layout, block by
block, rather than by splitting names on a dot: the second works until a block's name contains one.

### D-117. The persistence gets a Beta(2, 2) prior, which cancels the stationary singularity

`Ar1Trend` initialises its wobble from the stationary distribution, whose scale is
`tau/sqrt(1 - phi²)`. With a uniform prior on `phi` over (-1, 1) that scale is unbounded at the
boundary, and the posterior inherits the singularity.

The reference run measured what that costs: **173 divergent transitions in 32 000** with a uniform
prior, at a target acceptance of 0.99, against **1** with a Beta(2, 2) on `(phi + 1)/2`. The Beta's
density is proportional to `1 - phi²`, which is exactly the reciprocal of the squared stationary
scale, so the two cancel and the geometry becomes ordinary. It also says something defensible —
that a unit root is less likely than a middling persistence — which is what a model that assumes
stationarity ought to say.

Both the Scala component and `reference/build_nowcast_reference.py` carry it, because the
cross-comparison is only meaningful if the two models are the same model.

### D-118. The hierarchical dispersion is bounded, for a numerical reason

`GammaPoissonHierarchical` declares `alpha` on `(1e-3, 1e6)` rather than merely positive.

The inferential part of that is free: as `alpha` grows the groups become identical and the marginal
becomes Poisson, so with an exposure of about ten per group nothing above a few hundred is
distinguishable from infinity. The bound at `1e6` is four orders of magnitude above anything the
data can speak to.

The numerical part is the reason it is there. Above `alpha` of about `10^13` the negative binomial's
**gradient** with respect to `log alpha` degenerates (D-119). Unbounded, a chain wandered there
during warmup and the step-size adaptation froze it at `10^-32`: the fit then reported one chain at
`alpha = 3e21` and another, correct, at 3.9, and the two methods' comparison came out as nonsense
rather than as a failure. Bounded seven orders of magnitude below the breakdown, that cannot
happen — and the fit went from 1 minute 47 to 1 second.

### D-119. The negative binomial's gradient degenerates for a huge shape: reported, not fixed

Measured while diagnosing D-118, on `LogMass.negativeBinomial(1, alpha, alpha/10)` differentiated
with respect to `log alpha`:

| `log alpha` | value | gradient | what it should be |
|---|---|---|---|
| 10 | -7.6955994936 | -1.8e-3 | correct |
| 20 | -7.6974148246 | -6.6e-7 | correct |
| 30 | -7.6974149070 | **+1.2e-2** | wrong sign, ~1e-13 |
| 38, 41, 50 | -7.6974149070 | **-2.0 exactly** | ~0 |

The **value** is right everywhere — it converges to `Poisson(1 | 10)` to the last bit, which is
what `Real[A].lbeta` was made a primitive for (D-38). It is the derivative that collapses, and a
wrong constant gradient is worse than a wrong value because the sampler follows it.

This is phase 2's component, and fixing it means reworking the adjoint of a cancelling expression —
out of this phase's scope. Phase 9 keeps its own models away from the region (D-118) and reports
the measurement here. Anyone writing a model whose dispersion can reach `10^13` should know that
the gradient there is not to be trusted.

### D-120. The predictive statistic is the arrival diagonal, not the creation row

`Checks` replicates the number of reports that **arrived** in each calendar period — the cells with
`month + delay = period` — rather than the number of events created in each month.

Creations in the recent months are precisely what nobody has observed; they are what the model is
for. Checking a model against its own estimate of them would be checking it against itself. Reports
per period is the series that exists, and a model that gets the rate right while getting the delay
wrong will match the totals and fail the diagonal, which is the failure worth catching.

### D-121. The hierarchical rates are integrated out, and the mixture is summarised by hand

`GammaPoissonHierarchical` samples only `alpha`: the per-group rates are integrated out
analytically, since `theta[j] ~ Gamma(alpha, alpha)` against a Poisson leaves a negative binomial.
That makes the fit one-dimensional and fast, and it removes `J` parameters from the geometry.

What it costs is that the rates are no longer sampled, so `theta` reconstructs each one as a
**mixture** of `Gamma(alpha + n, alpha + e)` over the samples of `alpha`, by the law of total
variance: the mean of the conditional variances plus the variance of the conditional means. The
second term is the uncertainty about `alpha` — exactly what empirical Bayes drops — and it is
asserted exactly rather than statistically, because a mutation that removed it survived every
statistical test in the suite.

### D-122. A size is its own model, and the cap is a parameter with no default

`Forecast` asks a `SizeModel[P]` how big an event is, rather than taking sizes from the template
pool. A pool says which kind of event happens; how large it is depends on the application, and the
log-normal answer — trending, depending on how late the report was — is provided because it is the
usual one.

The cap is optional and has no default. A log-normal has no largest value, so a long enough
simulation will draw something absurd, and one absurd event can dominate a total and make an
interval meaningless. Capping says sizes above some value do not happen in this application;
leaving it off says they do. Neither is the library's business to assume.

### D-123. The reference posterior records its one divergent transition rather than reseeding

`reference/build_nowcast_reference.py` asserts at most three divergent transitions in 32 000, not
zero, and the fixture's `inference` block carries the actual count — one.

Zero would have been obtainable by trying seeds until one gave it, which is exactly the practice
the repository forbids elsewhere. One divergence in 32 000, with a worst R-hat of 1.0005 and a
smallest bulk effective size of 8 076, is a sound reference; hiding it would make the fixture less
trustworthy, not more.

### D-124. `nowcast` borrows three modules in test scope only

`ad % "test->test"` for the dual-number oracle, so the count model's gradient is checked the way the
ported posteriors' are; `diagnostics % Test` for the convergence guard and `Sbc`; `stanIo % Test`
for the reference-summary parser. None of `nowcast`'s own sources depends on any of them, and the
module graph of §4 is unchanged for anything that ships.

### D-125. Calibration ranks the interpretable parameters, not the innovations

`DelayCountModel.generative`'s `named` returns `a`, `b`, `phi`, `tau`, `cut0` and `beta` — not the
twelve innovations of the AR(1) nor the increments of the cut points.

The innovations are standard normal by construction and there are as many of them as there are
months, so ranking them would multiply the number of checks, and the announced failure rate with
it, without testing anything the rate they build does not already test. The rate is ranked through
`a`, `b`, `phi` and `tau`; a mistake in how the innovations assemble into it shows up there.

### D-126. The full fit is not asserted to be wider than the shortcut at four groups

The phase's criterion asks for `GammaPoissonHierarchical` to be "notably more uncertain" than
`GammaPoissonShrinkage` with four groups. Measured over six seeds, the ratio of their interval
widths ran from **0.852 to 1.148** — the full fit is sometimes narrower.

That is not a defect, and the reason is worth stating. The shortcut's width is set by wherever its
point estimate landed, and with four groups that estimate ranged from 1.3 to 10.8 across seeds: a
large `alphaHat` makes the shortcut absurdly confident and a small one makes it wide. "More
uncertain" is therefore a statement about the **dispersion**, not a guarantee about every group's
interval, and that is what the suite asserts: `alpha`'s own relative posterior spread is 4.3 to
12.6 at four groups against 0.125 to 0.139 at two hundred, thirty to ninety times wider, and the
two methods' per-group answers differ by 12 % to 58 % against under 1.6 %.

### D-127. Nine blocks need a nested sub-spec, because `mapN` stops at eight

`ParamSpec`'s `mapN` is written out for arities two to eight. `Ar1TrendHierarchical` declares nine
blocks, so its AR(1) part — `phi`, `tau`, `eps` — is composed as a sub-spec of its own and unpacked
into the flat parameter class.

The declared names and their order are identical to a flat declaration, so nothing downstream can
tell: a CSV row, a summary table and `Model.unconstrain` all see `muA, muB, sigmaA, sigmaB, aRaw,
bRaw, phi, tau, eps` in that order. Adding a ninth arity was the alternative; nesting costs three
lines at one call site and the arity would cost thirty in a public file, for a model that is
already at the edge of what one component should declare.

### D-128. Centred and non-centred are a flag on one component, not two components

§5.10 asks for "centrée ou non centrée" as "un réglage, pas deux modèles", and that is what
`Ar1TrendHierarchical(centred = ...)` is. The two parameterisations describe the **same posterior**
and differ in one place each: how the group effects are read (`a[g]` itself, or
`muA + sigmaA * aRaw[g]`), and which prior the declared effects carry (the group prior, or a
standard Gaussian).

The suite asserts both halves of that: the rates agree to 1e-14 for parameters that describe the
same levels, and the two priors **differ** — because a flag that changed nothing would be a flag
that does nothing. The default is non-centred, which is the one that still samples when `sigmaA`
approaches zero.

### D-129. `LocalLevel` projects with `phi = 1`, which is what makes it a walk

`Ar1.project(1.0, sigma, last, horizon, seed)`: the persistence is one, so there is no pull back to
a level and the forecast is the last value plus accumulated noise. Its mean is flat and its spread
grows as the square root of the horizon — the honest statement that the further out you look the
less you know, and the exact opposite of a trend, which claims to know the direction better the
further it extrapolates.

That is also how the component answers the phase's criterion. It has no trend parameter, so it
cannot report one; the measurable version of "does not detect a trend" is that its projection does
not drift, which is asserted over 20 000 seeds at eight horizons.

### D-130. The whole group is simulated inside one future, never forecast separately and added

`Forecast.byGroup` loops over the groups **inside** each simulated future, from one sample from the
posterior. The alternative — forecast each group on its own and add the ensembles afterwards — is
easier to write and wrong: the groups share the AR(1) wobble, the delay's shape and the
hyperparameters, and simulating them in separate runs makes those shared things independent. The
total's interval then comes out too narrow, which is the same error as adding quantiles and just as
invisible.

The rule is a function of the group so that contributions can be keyed by it, which is what lets
the per-group ensemble and its `total` come out of one simulation. The suite asserts that keying
the same run two ways gives totals that agree exactly — keying is labelling, and if it changed a
single draw the two would differ.

### D-131. `ModelError.calibrate` takes its scoring rule as an argument

The scale of the model's own error is calibrated by widening past forecasts by each candidate and
keeping the one that scores best. That needs a scoring rule, and `predictive` does not depend on
`scoring` — by the module graph of §4, and usefully, since a scale calibrated under one rule is not
calibrated under another.

So the rule is a parameter: `Crps.ensemble` is what anyone would pass, and the suite passes it
through a test-scope dependency rather than a shipping one. The convention is that **lower is
better**, which CRPS, the interval score and the log score all are; a rule where higher is better
must be negated by the caller, and the scaladoc says so.

Two details the suite pins. Widening draws one `eta` **per sample**, not one per ensemble: one per
ensemble would shift it bodily and leave its width alone, which is the opposite of the intention.
And every candidate is scored on the same underlying noise, derived from the seed and the
candidate's index, so the comparison between candidates is a comparison of scales rather than of
two draws of noise — and the whole search is a function of its seed, which is asserted directly.

### D-132. `calibrate` is not generic in `F`, because it interprets `State` itself

It takes a `Seed` and returns a value, like `Ar1.project` and `Simulator.run`: a grid search is one
answer rather than a program the caller composes with, so threading an `F` through it would be
ceremony. The dead `[F[_]: Monad: Rng]` it was first written with was caught by the compiler's
unused-parameter warning under CI's fatal-warning settings, which is the second time this phase
that those settings found something a local build would not.

### D-133. Every rate component is total in its time axis, and in its group

Found by the manual: a chapter asked for the rate at times beyond the data's, and
`Ar1Trend.logRate` **threw** — it indexed the wobble by month without checking that there was an
innovation for it. That breaks 2.1's rule that a public function returns a value rather than an
exception, and it had been there since phase 9.

All three components now return `NaN` for a month whose innovation was never declared, and for a
group that does not exist. `NaN` rather than an exception because it is the library's convention
for outside-the-domain, and rather than silently reading the first group's rate because that is a
plausible-looking answer to a question about a group that is not there. The future is reached
through `project`, which is given its own times, and the manual now says so where the mistake was
tempting.

### D-134. The two rate variants cannot be separated by their predictive score, and the chapter says so

§9.3 asks chapter 8 for a worked comparison of two rate variants. It is there, and its finding is
that the comparison is **inconclusive**: measured across three trend strengths (0.6, 1.5, 3.0 a
year) and two horizons (10 and 24 periods), the ratio of the walk's mean CRPS to the trend's ran
from 0.990 to 1.002 — and at the longer horizon the walk was marginally ahead twice, on data the
trend component generated.

The reason is worth the chapter's space. A walk forecasts from the last level, which already
embodies what the trend has done, and its uncertainty widens with the horizon; on a smooth
exponential series that tracks the truth about as well as extrapolating the slope, and the score is
dominated by the rate's own uncertainty, which both inflate the same way. What does separate them is
the **parameter**: fit the trend component to walk-generated data and ask whether its trend excludes
zero, which is what `MultiGroupFitSlowSuite` asserts.

Writing a rigged example that made the trend win was the alternative. Reporting the measurement is
more useful, and it demonstrates the chapter's own cautions about short horizons and single
realisations instead of merely stating them.

### D-135. `Mcmc.run` is a fold over `Mcmc.step`, so a stream and a batch cannot drift apart

A pure program wants a whole chain in one call; a stream wants one iteration, a state, and the
chance to stop in between — because an effectful sampler must be cancellable **between**
iterations and never half way through a transition (§5.12).

Those could have been two loops. They are one: `Progress` carries the point, the adaptation and the
frozen tuning, `step` does one iteration, and `run` is a `tailRecM` over `step`. There is therefore
one implementation of the warmup schedule, of the step-size restart when the metric changes, and of
the freezing of the tuning at the warmup boundary — and "the streamed chain equals the folded
chain" is true by construction rather than by two implementations agreeing.

Rewriting phase 4's loop is the kind of change that needs evidence rather than argument. The whole
suite passed unchanged afterwards — the eleven leapfrog and `build_tree` fixtures, the nine
posteriordb reference posteriors, every SBC suite, all on fixed seeds — which is what says the
observable behaviour did not move.

`step` takes no `AdaptationSettings`: the adaptation state carries everything it needs, and the
parameter it was first written with was dead. The compiler's unused-parameter warning found that
under CI's fatal-warning settings, which is the third phase running where those settings caught
something a plain local build does not.

### D-136. `RefRng` runs the pure programs through a `Ref`, rather than reimplementing them

`RefRng.normal` is `ref.modify(seed => Rng[Rand].normal.run(seed).value)`. It does not implement a
Gaussian: it interprets the **same program** the `State` interpreter does, and only changes how the
seed is carried.

That is the hinge of the module. The library's claim is that a fit is a function of its seed, and
every test of a model is pure; if the effectful interpreter drew even slightly different numbers,
nothing run in `IO` could be reproduced by a pure test and the test suite would stop covering the
code anyone actually runs. Reimplementing the generators would make that claim rest on two
implementations agreeing — this way they cannot differ, and the suite asserts it holds for
interleaved longs, uniforms and normals, in that order, because a generator that drew the right
numbers in the wrong order would pass a test of each method alone.

### D-137. One generator per chain, seeded `seed.fold(chain)`

A `Ref` is safe to share between fibres, and sharing one would be wrong: what each chain drew would
then depend on the order the scheduler happened to run them in, and a parallel fit would not equal
a sequential one.

So `Sampler.chain` makes its own `RefRng` at `seed.fold(chainId)` — the same derivation
`Mcmc.runNuts` uses for its chains. That is what makes "parallel equals sequential, bit for bit"
hold, and the suite asserts the four-chain `IO` fit equals `Mcmc.runNuts` on the same seed value for
value, not in distribution.

The shared-`Ref` case is tested anyway, for what it is worth: eight fibres drawing from one
generator get 200 distinct values which are exactly the multiset the pure interpreter would have
produced. Which fibre got which is not asserted, because it is not knowable — and that is the
reason the sampler does not share one.

### D-138. `Mcmc.row` is public, so there is one place that knows how a point becomes a row

A `Chain` holds unconstrained points; a CSV line, an ArviZ array and a streamed `Draw` all want the
named constrained values in `params.names` order. `Mcmc.draws` had that walk inside it; the
streaming sampler needs it per iteration, and copying it into `effect` would have been two
implementations of the same layout traversal.

### D-139. One CmdStan file holds one chain, and asking otherwise is refused

`PosteriorCsv.write` takes draws of **one** chain and raises a named error for more, rather than
writing the first and dropping the rest. `writeAll` is the one to use after a fit: it writes
`<stem>-1.csv`, `<stem>-2.csv` and so on, numbered from one as CmdStan numbers them, and returns the
paths it wrote.

Silently truncating a four-chain fit to its first chain is the failure worth preventing: the file
would look right, parse fine, and summarise a quarter of the posterior. The refusal says which
method to use instead, because an error that only says no is half an error.

### D-140. A CmdStan file with a header and no draws is malformed

Found by writing the effectful reader. `StanCsv.parse` accepted a file whose only line was a
header, returning a posterior of zero draws — from which every summary comes back `NaN` with
nothing to say why. A truncated file and a mistyped path that happens to exist both look like that.

It now returns `Malformed`, which is a behaviour change to phase 5's codec: the stan-io suite
passed unchanged, since a real CmdStan file always has at least one draw. What is deliberately
**not** detected is a file whose header is not a plausible CmdStan header — any comma-separated
line is one, so claiming to catch that would be claiming more than can be done.

### D-141. `BacktestRunner` streams, and the no-peeking contract stays the caller's

The loop phase 8 left out (D-111) lives here, and it is a `Stream` because a backtest refits once
per origin — the most expensive thing the library does. A stream lets the rows be watched as they
arrive, written out one at a time, and stopped after any of them; the suite asserts that taking two
rows costs **two** refits, which is the only property that distinguishes this from a list.

The forecast is a function from an origin to an ensemble in `F`, so the refit is the caller's and
the runner knows nothing about models. That also means the runner cannot enforce the contract that
matters — no fit may see its own target — so chapter 8 states it and says what happens when it is
broken.

The rows are phase 8's `Scored[D]` rather than a new `BacktestRow`: §5.12 sketches the latter, and
`Scored` already carries exactly those fields, having been built for exactly this.
