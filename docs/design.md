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

**This is an addition to a public signature beyond the renamings of 2.9, and it is flagged as such:
the trait is new in this phase, so nothing was broken, but the decision is open to review.**

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
model be read by the value interpreter, the tape and the oracle.

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
have made the error's own documentation wrong. Appendix B of the manual describes it with the rest.

### D-55. `Model.values`, which the sketch does not have

Nothing generic can look inside a `T[Double]`: there is no way to turn a model's parameter object
back into named values. Two things need exactly that — a CSV row is a map from name to values, and
a round trip test needs to go from a point to names and back — so `Model.values(m, q)` reads the
blocks again and names what it finds. It is the inverse of `Model.unconstrain` and the suite
asserts the round trip over random points.

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
