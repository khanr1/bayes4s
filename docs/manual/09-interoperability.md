# 9. Interoperability

CmdStan's CSV, read and written. Nothing here touches a file: `StanCsv` is two pure functions of
lines, and the file handling is in the second half of this chapter.

A fit from CmdStan and a fit from this library hold the same thing, so what is below is mostly a
matter of names — [appendix A](appendix-a-from-stan.md) maps the API's, and the
[glossary](glossary.md) maps the prose's.

## Reading a fit

```scala mdoc:silent
import bayes4s.*

/** Four lines standing in for a file; a real one has a hundred comment lines before the header. */
val lines = List(
  "# stan_version_major = 2",
  "# model = bernoulli_model",
  "lp__,accept_stat__,stepsize__,treedepth__,n_leapfrog__,divergent__,energy__,theta",
  "# Adaptation terminated",
  "# Step size = 0.883328",
  "-6.80778,0.991604,0.883328,2,3,0,6.81551,0.208532",
  "-11.053,0.593184,0.883328,3,7,0,11.0857,0.0272877",
  "#  Elapsed Time: 0.003 seconds (Warm-up)"
)
```

```scala mdoc
StanCsv.parse(lines).map(draws => (draws.names.toList, draws.iterations))
```

Comments can appear anywhere — CmdStan writes the adaptation's step size and mass matrix **after**
the header — so the header is simply the first line that is not one. A file of several chains is
several files:

```scala mdoc
StanCsv.parse(lines).map(_.column("theta").map(_.toList))
```

`column` pools the chains, `byChain` keeps them apart (which is what every diagnostic needs), and
`flatten` gives one row per draw.

## Writing one

```scala mdoc
val rendered = Draws
  .single(IArray("lp__", "theta"), IArray(IArray(-6.8, 0.21), IArray(-11.1, 0.027)))
  .map(StanCsv.render(_))
rendered.map(_.length)
```

The round trip is exact, to the last bit, for any double including the awkward ones. What comes out
is a file CmdStan's own reader accepts, which is more demanding than it looks: `cmdstanpy` finds
the sampled rows by locating four blocks in order — a header beginning with `lp__`, a comment block
after it, the rows, and a comment block after those — and refuses a file missing any of them. The
first
version of this renderer left out the trailing block and was rejected outright.

```scala mdoc
rendered.map(_.filter(_.startsWith("#")).takeRight(4))
```

For a fit of several chains, `renderAll` gives one file per chain, as CmdStan does.

## Opening a fit in ArviZ

A rendered file is read by `cmdstanpy.from_csv`, and `arviz_base.from_cmdstanpy` turns the result
into an `InferenceData`. `reference/check_cmdstan_csv.py` does exactly that and is how the claim
above is checked rather than asserted; `docs/validation.md` records what it reported.

```python
from cmdstanpy import from_csv
import arviz_base as azb
import arviz as az

fit = from_csv(sorted(glob.glob("out/bayes4s-*.csv")), method="sample")
idata = azb.from_cmdstanpy(fit)
az.ess(idata, var_names=["theta"])
```

One caution, which [chapter 4](04-diagnosing.md) explains: ArviZ's effective sizes are not Stan's,
so a summary computed there will not match one computed here in three of its columns.

## Reading posteriordb's references

posteriordb ships a reference summary beside each posterior — ten chains of ten thousand iterations,
so its numbers are Monte Carlo estimates themselves:

```scala mdoc:silent
val summary = """
{"posterior": "example", "why": "a short one",
 "reference_info": {"diagnostics": {"nchains": 10, "ndraws": 10000,
   "r_hat": [1.0], "effective_sample_size_bulk": [9000.0],
   "effective_sample_size_tail": [8000.0], "divergent_transitions": [0.0]}},
 "parameters": {"mu[1]": {"mean": 1.3, "sd": 0.2, "q05": 1.0, "q50": 1.3, "q95": 1.6,
   "ess_bulk": 9000.0, "mcse_mean": 0.002, "mcse_sd": 0.001}}}
"""
```

```scala mdoc
PosteriordbSummary.parse(summary).map(s => (s.posterior, s.rows.map(_.cmdStanName)))
```

Note the name: posteriordb writes `mu[1]` and CmdStan writes `mu.1`, so `cmdStanName` converts and
a comparison can match them up. Phase 6 is where those comparisons happen.

## Files, with the `effect` module

Everything above is a pure codec: lines in, samples out. Reading and writing actual files is the
`effect` module's job, through `fs2.io.file` and never `java.io`.

```scala mdoc:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.io.file.Files
import bayes4s.syntax.*
```

```scala mdoc:silent
/** A location with a known scale: four observations and one parameter, so the chapter stays about
  * the sampler rather than about the model.
  */
final case class Location[A](mu: A)

val located: Model[Location] = new Model[Location]:
  def params: ParamSpec[Location] =
    ParamSpec.real("mu").map[Location]([A] => (m: A) => Location(m))
  def logPrior[A: Real](p: Location[A]): A =
    LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A])
  def logLikelihood[A: Real](p: Location[A]): A =
    val y = IArray(1.2, 0.8, 1.5, 1.1, 0.9, 1.4)
    Real[A].normalSum(y, Real[A].filled(y.length)(p.mu), 1.0.lift[A])
```

**One file holds one chain**, which is CmdStan's convention and this library's: a fit of four
chains is four files, and `readAll` puts them back together.

```scala mdoc
val threeChains = RunSettings(chains = 3, warmup = 200, samples = 100)

val roundTrip = Files[IO]
  .tempDirectory
  .use { directory =>
    for
      fitted <- Sampler[IO].run(located, threeChains, Seed(7L))
      written <- PosteriorCsv[IO].writeAll(fitted, directory, "fit")
      back <- PosteriorCsv[IO].readAll(written)
    yield (
      written.map(_.fileName.toString),
      back.chainCount,
      back.iterations,
      fitted.flatten.zip(back.flatten).forall((a, b) => a.toList == b.toList)
    )
  }
  .unsafeRunSync()
```

The last field is the claim worth making: a fit that went to disk and came back is the **same** fit,
value for value. The files are CmdStan's format, so what comes back out can equally be read by
`cmdstanpy` and handed to ArviZ, as the first half of this chapter describes.

Asking to write a whole fit into a single file is refused rather than quietly truncated to its
first chain:

```scala mdoc:silent
val twoChains = RunSettings(chains = 2, warmup = 100, samples = 50)
```

```scala mdoc
Files[IO]
  .tempDirectory
  .use { directory =>
    for
      fitted <- Sampler[IO].run(located, twoChains, Seed(8L))
      attempted <- PosteriorCsv[IO].write(fitted, directory / "everything.csv").attempt
    yield attempted.left.map(_.getMessage)
  }
  .unsafeRunSync()
```

And a file that is not a fit is an error, not an empty posterior. A header with no rows under it —
a truncated file, or a mistyped path that happened to exist — would otherwise parse into a fit of
nothing at all, from which every statistic comes back `NaN` with nothing to say why.
