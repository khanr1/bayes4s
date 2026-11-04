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
