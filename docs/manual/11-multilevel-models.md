# 11. Multilevel models

A multilevel model is the answer to a question that comes up as soon as data arrives in groups:
does each cluster get its own parameter, or do they all share one? Neither extreme is usually
right, and this chapter is about the middle — how to write it, how to predict a cluster you have
never seen, and how to check that the middle is really where the data wants to be.

Everything here is fake data, generated in the open so that you can see what the right answer is.

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.*
import cats.Monad
import cats.syntax.all.*

val clusters = 12
val perCluster = 4
val rows = clusters * perCluster

/** Twelve clusters, four observations each, simulated from a shared Gaussian above them. */
val (x, y, index) =
  val program = for
    effects <- Monad[Rand].replicateA(clusters, Rng[Rand].normal.map(z => 2.0 + 0.9 * z))
    slopes <- Monad[Rand].replicateA(clusters, Rng[Rand].normal.map(z => -0.5 + 0.3 * z))
    observed <- (0 until rows).toList.traverse { i =>
      val j = i % clusters
      val predictor = math.sin(i * 0.7)
      Rng[Rand].normal.map(e => effects(j) + slopes(j) * predictor + 0.6 * e)
    }
  yield observed
  val drawn = program.runA(Seed(20261105L)).value
  (
    IArray.tabulate(rows)(i => math.sin(i * 0.7)),
    IArray.from(drawn),
    IArray.tabulate(rows)(i => i % clusters)
  )
```

## Varying intercepts

The model says each cluster has its own intercept, and that the intercepts themselves come from a
Gaussian whose location and spread are parameters. `ParamSpec.vector` declares the cluster
parameters and `Real[A].gather` gives every observation its own cluster's value — that is the one
operation a multilevel likelihood needs, and it is a primitive so that its adjoint accumulates
over a cluster's observations in a single pass.

```scala mdoc:silent
final case class Varying[A](mu: A, tau: A, sigma: A, z: IArray[A])

/** Non-centred: the cluster parameters are standardised, and `mu + tau * z` rebuilds them. */
val varying: Model[Varying] & PointwiseLikelihood[Varying] =
  new Model[Varying] with PointwiseLikelihood[Varying]:
    def params: ParamSpec[Varying] =
      (
        ParamSpec.real("mu"),
        ParamSpec.positive("tau"),
        ParamSpec.positive("sigma"),
        ParamSpec.vector("z", clusters)
      ).mapN[Varying]([A] => (m: A, t: A, s: A, z: IArray[A]) => Varying(m, t, s, z))

    def logPrior[A: Real](p: Varying[A]): A =
      LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
        LogDensity.halfNormal(p.tau, 1.0.lift[A]) +
        LogDensity.halfNormal(p.sigma, 1.0.lift[A]) +
        LogDensity.normal(p.z, 0.0.lift[A], 1.0.lift[A])

    def logLikelihood[A: Real](p: Varying[A]): A =
      val levels = Real[A].vectorOf(clusters)(j => p.mu + p.tau * p.z(j))
      Real[A].normalSum(y, Real[A].gather(levels, index), p.sigma)

    /** The intercepts themselves, which are what you want to report. */
    override def derived(p: Varying[Double]): Map[String, IArray[Double]] =
      Map("alpha" -> IArray.tabulate(clusters)(j => p.mu + p.tau * p.z(j)))

    def pointwise(p: Varying[Double]): IArray[Double] =
      val levels = IArray.tabulate(clusters)(j => p.mu + p.tau * p.z(j))
      IArray.tabulate(rows)(i => LogDensity.normal(y(i), levels(index(i)), p.sigma))
```

Two things in there are worth naming. The parameterisation is **non-centred**: the declared
parameters are standardised and the intercepts are derived, which is what keeps the sampler out of
the funnel that chapter 4 diagnoses — write `ParamSpec.vector("alpha", clusters)` with
`LogDensity.normal(p.alpha, p.mu, p.tau)` in the prior instead and you have the centred version,
which is the same posterior and a much harder one to sample when `tau` can approach zero. And
`derived` is how a quantity the model computes rather than declares reaches the summary, the CSV
and simulation-based calibration.

```scala mdoc:silent
val settings = RunSettings(chains = 2, warmup = 600, samples = 1000)
val fit = Mcmc.runNuts(varying, settings).runA(Seed(4711L)).value
```

```scala mdoc
fit.map(chains => Summary.table(varying, chains).linesIterator.take(5).mkString("\n"))
```

## Comparing against both extremes

The two extremes are worth writing down, because they are what the multilevel model is a
compromise between: one intercept for everybody, and one per cluster with nothing shared.

```scala mdoc:silent
final case class Pooled[A](mu: A, sigma: A)
final case class Separate[A](alpha: IArray[A], sigma: A)

val pooled: Model[Pooled] & PointwiseLikelihood[Pooled] =
  new Model[Pooled] with PointwiseLikelihood[Pooled]:
    def params: ParamSpec[Pooled] =
      (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
        .mapN[Pooled]([A] => (m: A, s: A) => Pooled(m, s))
    def logPrior[A: Real](p: Pooled[A]): A =
      LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
        LogDensity.halfNormal(p.sigma, 1.0.lift[A])
    def logLikelihood[A: Real](p: Pooled[A]): A =
      Real[A].normalSum(y, Real[A].filled(rows)(p.mu), p.sigma)
    def pointwise(p: Pooled[Double]): IArray[Double] =
      IArray.tabulate(rows)(i => LogDensity.normal(y(i), p.mu, p.sigma))

val separate: Model[Separate] & PointwiseLikelihood[Separate] =
  new Model[Separate] with PointwiseLikelihood[Separate]:
    def params: ParamSpec[Separate] =
      (ParamSpec.vector("alpha", clusters), ParamSpec.positive("sigma"))
        .mapN[Separate]([A] => (a: IArray[A], s: A) => Separate(a, s))
    def logPrior[A: Real](p: Separate[A]): A =
      LogDensity.normal(p.alpha, 0.0.lift[A], 5.0.lift[A]) +
        LogDensity.halfNormal(p.sigma, 1.0.lift[A])
    def logLikelihood[A: Real](p: Separate[A]): A =
      Real[A].normalSum(y, Real[A].gather(p.alpha, index), p.sigma)
    def pointwise(p: Separate[Double]): IArray[Double] =
      IArray.tabulate(rows)(i => LogDensity.normal(y(i), p.alpha(index(i)), p.sigma))
```

Cross-validation needs the log likelihood per observation per sample, and the relative effective
number of samples per observation so that the smoothing knows how long a tail to replace:

```scala mdoc:silent
def crossValidate[T[_]](model: Model[T] & PointwiseLikelihood[T], seed: Long) =
  Mcmc.runNuts(model, settings).runA(Seed(seed)).value.map { chains =>
    val perChain = chains.map(c => IArray.from(c.draws.map(q => model.pointwise(Model.constrain(model, q)))))
    val total = perChain.map(_.length).sum
    val logLik = IArray.tabulate(rows)(i => IArray.from(perChain.flatMap(_.map(_(i)))))
    val relative = IArray.tabulate(rows) { i =>
      val columns = IArray.from(perChain.map(draws => IArray.from(draws.map(_(i)))))
      val ess = Convergence.essBasic(columns)
      if ess.isNaN || !(ess > 0.0) then 1.0 else ess / total
    }
    Loo.psis(logLik, relative)
  }
```

```scala mdoc
val ranking = for
  a <- crossValidate(varying, 4711L)
  b <- crossValidate(pooled, 4711L)
  c <- crossValidate(separate, 4711L)
yield Loo.compare(List("partial" -> a, "pooled" -> b, "separate" -> c))
def tenths(x: Double): Double = (x * 10.0).round / 10.0
ranking.map(_.map(r => (r.name, tenths(r.elpd), tenths(r.difference), tenths(r.se))))
```

Partial pooling comes first, and complete pooling is rejected by several standard errors: the
clusters really do differ, and a single intercept can only cover them by inflating `sigma` to
swallow the difference.

Now read the other difference honestly, because this is where cross-validation is usually
oversold. No pooling is a little behind, by less than its own standard error, and that is not a
sample-size problem you can fix with more samples from the posterior. The multilevel fit
**interpolates** between the two extremes: `tau` large shrinks it towards no pooling, `tau` near
zero towards complete pooling. It is therefore always close to whichever extreme the data supports,
and the difference on that side is inside the noise by construction. Flip the data — a small
between-cluster spread and few observations per cluster — and the two differences swap places: no
pooling is rejected and complete pooling becomes the one you cannot distinguish.

What you can say, and all you can say, is that partial pooling is the only one of the three that is
never rejected. That is the argument for it, and it is enough.

## Varying intercepts and slopes, correlated

When each cluster has both an intercept and a slope, they are usually related — clusters that start
high may rise more slowly — and a model that gives each its own Gaussian throws that away. The
arrangement that keeps it declares a vector of locations, a vector of scales, and the Cholesky
factor of the correlation between them under an LKJ prior.

```scala mdoc:silent
final case class Both[A](
    mu: IArray[A],
    tau: IArray[A],
    factor: IArray[IArray[A]],
    sigma: A,
    z: IArray[IArray[A]]
)

val correlated: Model[Both] =
  new Model[Both]:
    def params: ParamSpec[Both] =
      (
        ParamSpec.vector("mu", 2),
        ParamSpec.vector("tau", 2, Constraint.Positive),
        ParamSpec.choleskyCorr("L", 2),
        ParamSpec.positive("sigma"),
        ParamSpec.matrix("z", clusters, 2)
      ).mapN[Both](
        [A] =>
          (m: IArray[A], t: IArray[A], l: IArray[IArray[A]], s: A, z: IArray[IArray[A]]) =>
            Both(m, t, l, s, z)
      )

    def logPrior[A: Real](p: Both[A]): A =
      val r = Real[A]
      LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
        LogDensity.halfNormal(p.tau(0), 1.0.lift[A]) +
        LogDensity.halfNormal(p.tau(1), 1.0.lift[A]) +
        LogDensity.lkjCorrCholesky(p.factor, 2.0.lift[A]) +
        LogDensity.halfNormal(p.sigma, 1.0.lift[A]) +
        r.sum(r.vectorOf(clusters * 2)(k => LogDensity.normal(p.z(k / 2)(k % 2), 0.0.lift[A], 1.0.lift[A])))

    def logLikelihood[A: Real](p: Both[A]): A =
      val r = Real[A]
      val effects = effectsOf(p)
      r.normalSum(
        y,
        r.vectorOf(rows) { i =>
          val j = index(i)
          effects(j)(0) + effects(j)(1) * x(i).lift[A]
        },
        p.sigma
      )

    override def derived(p: Both[Double]): Map[String, IArray[Double]] =
      Map("rho" -> IArray(p.factor(1)(0)))

/** `mu + tau ⊙ (L z_j)`, one row per cluster: the same arrangement Stan's manual recommends. */
def effectsOf[A: Real](p: Both[A]): IArray[IArray[A]] =
  val r = Real[A]
  r.rowsOf(clusters) { j =>
    val standardised = r.matVec(p.factor, r.vector(p.z(j)(0), p.z(j)(1)))
    r.vectorOf(2)(k => p.mu(k) + p.tau(k) * standardised(k))
  }
```

`Real[A].rowsOf` is what builds a matrix of the interpreter's own scalar. Writing
`IArray.tabulate(clusters)(...)` there compiles and then fails at run time the first time the model
is read at `Double` rather than at the tape's scalar, because the compiler cannot tell, inside a
generic method, that a row of `A` is an array of doubles rather than an array of objects.
`rowsOf` and `matrixOf` know; nothing else does.

```scala mdoc
val both = Mcmc.runNuts(correlated, settings).runA(Seed(99L)).value
both.map(chains => Summary.table(correlated, chains).linesIterator.take(4).mkString("\n"))
```

The correlation reaches the summary as `rho`, through `derived`, because `L` is a factor and the
number anyone wants is the off-diagonal entry of the correlation it builds.

## Predicting a cluster you have not seen

A cluster that is not in the data has no parameter of its own, and the model still says exactly
what to do: draw one from the population the fit describes. `Hierarchy.newGroup` does that, one
effect per sample from the posterior.

```scala mdoc
val unseen = fit.map { chains =>
  val samples = chains.flatMap(_.draws).map(q => Model.constrain(varying, q))
  val mu = IArray.from(samples.map(_.mu))
  val tau = IArray.from(samples.map(_.tau))
  val drawn = Hierarchy.newGroup[Rand](mu, tau).runA(Seed(7L)).value
  (Quantile.quantile(drawn, 0.05), Quantile.quantile(drawn, 0.5), Quantile.quantile(drawn, 0.95))
}
```

The pairing is the point. Sample `s` uses `mu(s)` **and** `tau(s)`, never a location from one
sample with a spread from another: those two numbers are correlated in the posterior, and crossing
them describes a posterior nobody has. For correlated effects, `Hierarchy.newGroupCorrelated`
takes the scales and the Cholesky factor the same way, sample by sample.

This is also the clearest place to see what full Bayes buys over plugging in point estimates. Take
the posterior means of `mu` and `tau`, use those for every sample, and the spread of the predicted
cluster is narrower — it has lost the uncertainty about the population itself:

```scala mdoc
val plugged = fit.map { chains =>
  val samples = chains.flatMap(_.draws).map(q => Model.constrain(varying, q))
  val n = samples.length
  val muBar = samples.map(_.mu).sum / n
  val tauBar = samples.map(_.tau).sum / n
  val full = Hierarchy
    .newGroup[Rand](IArray.from(samples.map(_.mu)), IArray.from(samples.map(_.tau)))
    .runA(Seed(7L))
    .value
  val point = Hierarchy
    .newGroup[Rand](IArray.fill(n)(muBar), IArray.fill(n)(tauBar))
    .runA(Seed(7L))
    .value
  def spread(xs: IArray[Double]) =
    val mean = (0 until xs.length).foldLeft(0.0)((s, i) => s + xs(i)) / xs.length
    math.sqrt((0 until xs.length).foldLeft(0.0)((s, i) => s + (xs(i) - mean) * (xs(i) - mean)) / (xs.length - 1))
  (spread(full), spread(point))
}
```

The gap is small here, because twelve clusters pin the population down reasonably well. With four
clusters it is not small, and it is always in the same direction: empirical Bayes — fitting the
population and then treating it as known — reports an interval narrower than the evidence
supports.

## Whether the prior on the scale is deciding

The parameter a multilevel model is most often wrong about is `tau`, the spread between clusters,
because the number of clusters is the sample size for it. Twelve clusters is not many, and the
honest thing is to ask whether the prior is doing the deciding. That is what power scaling answers,
without refitting:

```scala mdoc
fit.map(chains => PowerScaling.analyse(varying, chains).filter(s => s.name == "mu" || s.name == "tau" || s.name == "sigma"))
```

Read it with chapter 4's caution in mind: a reading above the threshold on the prior side is not a
verdict that the prior is wrong, it is a statement that your conclusion about `tau` is partly a
statement about your prior. With few clusters that is unavoidable and worth saying out loud; the
answer is a prior you are willing to defend, not a wider one.

## What to check before believing any of this

- The sampler first: `Rhat`, the effective number of samples, and the divergent transitions of
  chapter 4. A multilevel model that diverges is usually asking for the non-centred form.
- `pointwise` must sum to `logLikelihood`. Test it; it is two lines and it catches the mistake that
  would make every number in this chapter wrong.
- Simulation-based calibration, which is the only check that reads the prior as a density **and**
  as a sampler and makes them agree. The correlated model above is calibrated under
  `Sbc.run`, and the correlation is ranked along with the parameters because it is `derived`.
