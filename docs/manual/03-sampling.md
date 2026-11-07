# 3. Sampling

The sampler is a pure function of its seed. That is the whole of this chapter: what that buys, how
to steer it, and what it tells you about its own work.

## A chain is a value

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.*

/** A Gaussian correlated at 0.7 in four dimensions, written through its precision. */
val correlated: DiffFn = new DiffFn:
  def apply[A: Real](x: IArray[A]): A =
    val r = Real[A]
    val rho = 0.7
    val scale = 1.0 / (1.0 - rho)
    val weight = rho / (1.0 + 3.0 * rho)
    val squares = r.dot(x, x)
    val total = r.sum(x)
    -((squares - total * total * weight) * scale * 0.5)

val start = IArray(0.0, 0.0, 0.0, 0.0)

val program = Mcmc.run[Rand](
  Nuts.kernel[Rand](correlated, NutsSettings()),
  correlated,
  start,
  warmup = 1000,
  samples = 1000,
  AdaptationSettings()
)
```

`program` has run nothing. It is a description, and `Rand` is `State[Seed, *]`: give it a seed and
it gives you a chain.

```scala mdoc
val chain = program.runA(Seed(1234L)).value.map(_.draws.length)
```

The same seed gives the same chain, always:

```scala mdoc
val twice = (program.runA(Seed(1234L)).value, program.runA(Seed(1234L)).value)
twice._1.map(_.draws.head.toList) == twice._2.map(_.draws.head.toList)
```

and a different seed gives a different one. Four chains come from four seeds derived from one, so
a run is reproducible whether its chains are computed one after another here or at the same time by
the `effect` module — which chapter 9 covers. Bit for bit, in both cases.

## What the warmup does

**[Warmup](glossary.md)** is the iterations the sampler spends settling and tuning itself, which
are then discarded; it is what other traditions call burn-in. Two things are learnt at once, and  <!-- term-ok --><!-- naming the term the glossary replaces -->
both are reported.

The **step size** is learnt by dual averaging towards an acceptance statistic of 0.8. The number
that comes out is deliberately conservative, so the acceptance you see afterwards is usually a
little above the target rather than at it.

The **mass matrix** is learnt in windows that double — iterations 99, 149, 249, 449 and 949 of a
thousand-iteration warmup — each window estimating the variances of what the chain has just seen.
That is why a warmup cannot be shortened to a hundred iterations and still adapt: below twenty
there is no metric adaptation at all, and the windows need room.

```scala mdoc
program.runA(Seed(1234L)).value.map(_.tuning)
```

A diagonal mass matrix is the default and holds one number per parameter. When the posterior is
strongly correlated — group effects against their own scale, most often — a dense one is worth its
cost:

```scala mdoc:silent
val dense = Mcmc.run[Rand](
  Nuts.kernel[Rand](correlated, NutsSettings()),
  correlated,
  start,
  warmup = 1000,
  samples = 1000,
  AdaptationSettings(metric = MetricShape.Dense)
)
```

On a target correlated at 0.95 in ten dimensions that choice was worth a factor of forty in
effective samples per gradient; at 0.7 in four it is worth much less. Measure before reaching for
it.

## Where a chain starts

```scala mdoc:silent
val settings = RunSettings(
  chains = 4,
  warmup = 1000,
  samples = 1000,
  init = Init.Uniform(2.0)
)
```

Three choices. `Init.Uniform(2.0)` is Stan's: uniform on `(-2, 2)` **on the unconstrained space**,
so a positive parameter comes out positive whatever is drawn. `Init.Given(values)` starts from
named values you supply, and refuses them if they are outside their constraints.
`Init.PriorMedian(n)` samples `n` parameter vectors from the prior and starts at their middle —
which is what to use when a model has a latent rate and a start far from the posterior would leave
the sampler nowhere.

## What a transition reports

Every transition carries the same information CmdStan writes to its CSV, and reading it is how you
find out whether to believe the fit:

```scala mdoc
program.runA(Seed(1234L)).value.map(_.transitions.head)
```

- `divergent` — the energy error passed its threshold. One is a warning; many mean the posterior
  has a region the sampler cannot integrate, and no number of samples fixes it.
- `treeDepth` and `leapfrogSteps` — how far the trajectory had to go. A depth at the maximum of ten
  means the sampler was still going when it was stopped.
- `acceptStat` — the average Metropolis probability over the trajectory.
- `energy` — what chapter 4 turns into E-BFMI.
- `stepSize` — fixed during sampling, and varying during warmup.

```scala mdoc
program.runA(Seed(1234L)).value.map { c =>
  (
    c.transitions.count(_.divergent),
    c.transitions.map(_.treeDepth).max,
    c.transitions.map(_.acceptStat).sum / c.transitions.length
  )
}
```

## The simpler samplers

Two more kernels, and both earn their place by being simpler rather than better.

```scala mdoc:silent
val staticHmc = Hmc.kernel[Rand](correlated, leapfrogSteps = 12)
val randomWalk = RandomWalkMetropolis.kernel[Rand](correlated, scale = 1.0)
```

`Hmc` runs a fixed number of leapfrog steps and has no tree: when it works and the no-U-turn
sampler does not, the fault is in the tree. `RandomWalkMetropolis` needs no gradient at all: when
it works and `Hmc` does not, the fault is in the gradient. They are debugging instruments, and one
caution comes with the second — a random walk wants an acceptance near 0.23, not the 0.8 a
Hamiltonian method wants, so give it `AdaptationSettings(targetAccept = 0.234)` or it will crawl.

## When a fit fails

`Mcmc.run` returns an `Either`, and the two failures it reports are worth knowing:

```scala mdoc
Mcmc
  .run[Rand](
    Nuts.kernel[Rand](correlated, NutsSettings()),
    correlated,
    IArray(Double.NaN, 0.0, 0.0, 0.0),
    10,
    10,
    AdaptationSettings()
  )
  .runA(Seed(1L))
  .value
```

A starting point where the density is not a number is refused rather than sampled. The other is a
step-size search that reaches its bounds, which says the target is improper where the chain
started — a model to look at, not a setting to change.

## The same sampler in `IO`

Everything above is pure: a function of a seed, interpreted by `State`, single-threaded. The
`effect` module interprets the same algebra in `IO`, which buys three things — chains on separate
fibres, chains as streams, and cancellation.

```scala mdoc:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
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

```scala mdoc
val fitted = Sampler[IO]
  .run(located, RunSettings(chains = 4, warmup = 300, samples = 200), Seed(20261107L))
  .unsafeRunSync()
(fitted.chainCount, fitted.iterations, fitted.names.take(3).toList)
```

**The parallel run gives the same numbers as the pure one.** Not the same distribution — the same
doubles:

```scala mdoc
val pure = for
  chains <- Mcmc.runNuts(located, RunSettings(chains = 4, warmup = 300, samples = 200))
    .runA(Seed(20261107L))
    .value
  draws <- Mcmc.draws(located, chains)
yield draws
pure.map(expected => expected.flatten.zip(fitted.flatten).forall((a, b) => a.toList == b.toList))
```

That is not a coincidence of this model. Chain `c` is driven by a generator seeded `seed.fold(c)`,
so the numbers it generates cannot depend on the order the scheduler happened to run the fibres
in, and
`RefRng` runs the *same* pure programs the `State` interpreter does rather than reimplementing
them. A fit in `IO` is therefore reproducible from a pure test, which is what lets every other
chapter in this manual stay pure.

## A chain as a stream

`chain` yields one `Draw` per iteration, warmup included, so a long fit can be watched, written
out as it goes, or stopped:

```scala mdoc
Sampler[IO]
  .chain(located, RunSettings(chains = 1, warmup = 50, samples = 50), chainId = 0, Seed(5L))
  .filter(!_.warmup)
  .map(_.values.head)
  .take(5)
  .compile
  .toList
  .unsafeRunSync()
```

Each `Draw` carries its own transition, so the divergent transitions and tree depths of the
**warmup** are
visible too — which the batched interface throws away, and which is exactly what you want when a
fit is behaving badly.

Cancellation lands between iterations, never inside a transition. A run cancelled after a tenth of
a second stops within a tenth of a second, whatever its horizon:

```scala mdoc
import scala.concurrent.duration.*
Sampler[IO]
  .run(located, RunSettings(chains = 4, warmup = 1000, samples = 100000), Seed(5L))
  .map(d => s"finished with ${d.iterations} iterations")
  .timeoutTo(100.millis, IO.pure("cancelled"))
  .unsafeRunSync()
```

## Errors at the edge

The core returns `Either[BayesError, _]` and never throws. The edge raises those in `F`:
`BayesError` extends `NoStackTrace` for exactly that, so nothing is wrapped, renamed, or caught and
ignored.

```scala mdoc
Sampler[IO]
  .run(
    located,
    RunSettings(chains = 1, warmup = 5, samples = 5,
      init = Init.Given(
        Map("mu" -> IArray(Double.NaN), "tau" -> IArray(1.0), "unused" -> IArray(0.0))
      )),
    Seed(1L)
  )
  .attempt
  .unsafeRunSync()
  .left
  .map(_.getMessage)
```
