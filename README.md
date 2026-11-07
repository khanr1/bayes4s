# bayes4s

A Bayesian modelling library for Scala 3: reverse-mode automatic differentiation, distributions
and constraint transforms, a NUTS sampler that follows Stan, convergence diagnostics, predictive
simulation, forecast scoring, and a family of nowcasting models for counts that are only partly
observed because events take time to be recorded.

The core is pure. A log density is written once against an abstract arithmetic, `Real[A]`, and
read by three interpreters: plain `Double` for values, a reverse-mode tape for gradients, and dual
numbers as an exact oracle in the tests. Randomness is a value — an immutable seed, or the `Rng[F]`
capability interpreted by `State` — so four chains run in parallel give bit-for-bit the same draws
as four chains run in sequence. Errors are values. `cats-effect`, `fs2` and the file system exist
only in the `effect` module, at the edge.

**Status: the library works and nothing is published.** Everything described above is implemented
and tested: 559 fast tests and 34 slow statistical ones, compared against Stan Math, Stan's own
sampler cases, posteriordb posteriors, mpmath, ArviZ and properscoring. It is built phase by phase
— see `docs/design.md` for every decision and `docs/validation.md` for what has actually been
verified, mutation by mutation. There is no release, no snapshot and no Maven coordinate yet.

## The manual

**[docs/manual/README.md](docs/manual/README.md)** is the manual: a quick start, the concepts, how
to write a model, sample it, diagnose it, check it, predict with it, score the prediction, pool
across groups, and draw any of it. Every code block in it is compiled against the current build by
mdoc, and every figure is drawn when the manual is built, so neither can go stale. `sbt
docs/tlSite` renders the whole thing as a browsable site under `docs/target/docs/site`.

## Quick start

```scala
import bayes4s.*
import bayes4s.syntax.*

val observed = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4, 4.7, 5.1)

final case class Params[A](mu: A, sigma: A)

val model: Model[Params] = new Model[Params]:
  def params: ParamSpec[Params] =
    (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
      .mapN[Params]([A] => (mu: A, sigma: A) => Params(mu, sigma))

  def logPrior[A: Real](p: Params[A]): A =
    LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
      LogDensity.halfNormal(p.sigma, 5.0.lift[A])

  def logLikelihood[A: Real](p: Params[A]): A =
    Real[A].normalSum(observed, Real[A].filled(observed.length)(p.mu), p.sigma)

val fitted = Mcmc.runNuts(model, RunSettings()).runA(Seed(20261105L)).value

fitted.map(chains => Summary.table(model, chains))
```

No exceptions, no global randomness, no `IO` until you ask for it. Chapter 0 of the manual walks
through the same example and what to read in its output.

## Building

Requires **sbt** and a **JDK 23 or newer** (scalafmt needs it; see `docs/design.md`, D-3). The
artefacts themselves target Java 17. Homebrew's `openjdk@25` is keg-only, so point sbt at it:

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

```
sbt test                         # fast suites
sbt slow:test                    # slow statistical suites: SBC, posteriordb, recovery
sbt -Dbayes4s.stress=true slow:test   # same, with fresh seeds and more draws
sbt docs/mdoc                    # compile every code block of the manual
sbt docs/tlSite                  # ...and render the manual as a site
```

Before a commit:

```
sbt headerCheckAll scalafmtCheckAll "scalafixAll --check" test
python3 scripts/check-private-terms.py
python3 scripts/check-mutation-register.py
python3 scripts/check-terminology.py
python3 scripts/check-stan-names.py
python3 scripts/check-manual.py
```

`scalafixAll --check` needs the quotes: it takes an argument.

The lint rules are not cosmetic. `DisableSyntax` fails the build on `var`, `while`, `throw`,
`null`, `return`, `asInstanceOf` and `isInstanceOf`. The one allowed exception is mutation created
inside a pure function that never escapes it — a tape, a leapfrog loop — and such a site must
carry `// scalafix:ok DisableSyntax.var` with a sentence of justification and be listed in
`docs/design.md`.

## Layout

```
modules/core          seeds, Rng and its State interpreter, batch sampling, errors, numerics, Draws
modules/ad            Real[A], the Double and reverse-mode interpreters, gradient checking
modules/distributions log densities and masses, distributions as values
modules/transforms    constraint transforms with their log-Jacobians, named parameters
modules/inference     models, Metropolis, HMC, NUTS, warmup adaptation
modules/diagnostics   R-hat, ESS, MCSE, E-BFMI, summaries, intervals
modules/predictive    draws, ensembles, contributions, reporting triangles, bootstrap, trends
modules/scoring       CRPS, coverage, bias, PIT, backtesting
modules/nowcast       rate and delay components, censored likelihood, forecasting, checks
modules/stan-io       CmdStan CSV in and out, posteriordb summaries
modules/effect        the only module with IO: parallel chains, streams, files
modules/testkit       not published: reference loading, tolerances, Monte Carlo helpers
modules/benchmarks    not published: JMH
modules/examples      not published: complete programs used by the manual
docs/                 the manual (mdoc), design decisions, validation record
reference/            Python scripts that generate the reference data; never run by sbt
```

## Reference data

`modules/testkit/src/main/resources/reference/` carries the values the test suites compare
against: Stan Math's own distribution tests, Stan's leapfrog, `build_tree`, adaptation and
diagnostics cases, posteriordb reference posteriors, mpmath at 50 digits for special functions and
extreme arguments, properscoring for CRPS. Each file records its provenance, and
`reference/regenerate.sh` rebuilds all of it from clones of the Stan repositories.

The rule is that a test compares only against something that shares neither code nor assumptions
with this library: an exact oracle, an analytic result, a known posterior, or a high-precision
value from another tool. Never against an earlier output of this library, which would detect
change rather than error. Where references disagree, Stan wins over ArviZ and mpmath wins over
SciPy, and the gap is documented rather than hidden behind a wider tolerance.

Data copied from Stan, Stan Math and posteriordb keeps its BSD-3-Clause licence; the texts are in
`LICENSES/`.

## Content rule

This repository is generic and personal. Nothing in it — code, names, tests, data, documentation,
examples, commit messages — relates to any real organisation. Examples use only public data or the
fictional world generated by the repository itself, with a fixed seed. A guard runs in CI:

```
export BAYES4S_PRIVATE_TERMS_KEY="<a secret phrase, kept outside this repository>"
python3 scripts/hash-private-terms.py ~/private-terms.txt   # writes .private-terms.sha256
python3 scripts/check-private-terms.py                      # fails if a digest matches
```

The plain-text list never enters the repository; only the HMAC digests do, and the key stays
outside, so the digests cannot be read back by dictionary attack. See `docs/design.md`, D-7.

## Licence

Apache 2.0. See `LICENSE`.
