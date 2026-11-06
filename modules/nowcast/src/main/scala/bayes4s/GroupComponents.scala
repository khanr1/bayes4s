/*
 * Copyright 2026 bayes4s contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bayes4s

import bayes4s.syntax.{*, given}
import cats.Monad
import cats.syntax.all.*

/** Levels and trends per group, drawn around their population's own, with a shared wobble. */
final case class HierarchicalAr1Params[A](
    muA: IArray[A],
    muB: IArray[A],
    sigmaA: A,
    sigmaB: A,
    aRaw: IArray[A],
    bRaw: IArray[A],
    phi: A,
    tau: A,
    eps: IArray[A]
)

/** Groups that borrow from each other, in one, two or many populations.
  *
  * `a[g] ~ Gaussian(muA[population(g)], sigmaA)` and likewise for the trend, with the AR(1) wobble
  * shared by everybody. One population is the partially pooled case: a group with little of its
  * own is pulled towards the average of the others. Two populations is the structure §5.10 asks
  * for — each has its own level and trend, and what they share is the **spread** of their groups
  * and the wobble, which is the common level above them.
  *
  * `centred` is a flag, not a second model. Both parameterisations describe the same posterior:
  * centred holds `a[g]` itself and puts the group prior on it, non-centred holds the standardised
  * effect with a standard Gaussian prior and rebuilds `a[g]`. They differ only in which
  * coordinates the sampler walks in — and that difference decides whether it can walk at all when
  * `sigmaA` approaches zero, which is why the default is non-centred and why the manual says how
  * to choose.
  */
final case class Ar1TrendHierarchical(
    population: IArray[Int],
    centred: Boolean = false,
    levelPrior: (Double, Double) = (0.0, 2.0),
    trendPrior: (Double, Double) = (0.0, 1.0),
    spreadPrior: Double = 0.5,
    wobbleScalePrior: Double = 0.5
) extends RateComponent[HierarchicalAr1Params]:

  private val populations: Int = if population.length == 0 then 1 else population.max + 1

  /** Nine blocks, and `mapN` stops at eight: the shared wobble is composed as its own sub-spec
    * and unpacked here, so the declared names and their order are exactly as if it were flat
    * (docs/design.md).
    */
  def params(groups: Int, months: Int): ParamSpec[HierarchicalAr1Params] =
    val wobble =
      (
        ParamSpec.bounded("phi", -1.0, 1.0),
        ParamSpec.positive("tau"),
        ParamSpec.vector("eps", months)
      ).mapN[Ar1TrendHierarchical.Shared]([A] =>
        (phi: A, tau: A, eps: IArray[A]) => Ar1TrendHierarchical.Shared(phi, tau, eps)
      )
    (
      ParamSpec.vector("muA", populations),
      ParamSpec.vector("muB", populations),
      ParamSpec.positive("sigmaA"),
      ParamSpec.positive("sigmaB"),
      ParamSpec.vector("aRaw", groups),
      ParamSpec.vector("bRaw", groups),
      wobble
    ).mapN[HierarchicalAr1Params]([A] =>
      (
          muA: IArray[A],
          muB: IArray[A],
          sigmaA: A,
          sigmaB: A,
          aRaw: IArray[A],
          bRaw: IArray[A],
          shared: Ar1TrendHierarchical.Shared[A]
      ) =>
        HierarchicalAr1Params(
          muA,
          muB,
          sigmaA,
          sigmaB,
          aRaw,
          bRaw,
          shared.phi,
          shared.tau,
          shared.eps
        )
    )

  def logPrior[A: Real](p: HierarchicalAr1Params[A]): A =
    val r = Real[A]
    val top =
      LogDensity.normal(p.muA, levelPrior._1.lift[A], levelPrior._2.lift[A]) +
        LogDensity.normal(p.muB, trendPrior._1.lift[A], trendPrior._2.lift[A]) +
        LogDensity.halfNormal(p.sigmaA, spreadPrior.lift[A]) +
        LogDensity.halfNormal(p.sigmaB, spreadPrior.lift[A])
    // The group effects: a standard Gaussian on the standardised ones, or the group's own prior on
    // the levels themselves. This is the only place the flag changes anything.
    val effects =
      if centred then
        r.sum(r.vectorOf(p.aRaw.length) { g =>
          LogDensity.normal(p.aRaw(g), p.muA(populationOf(g)), p.sigmaA) +
            LogDensity.normal(p.bRaw(g), p.muB(populationOf(g)), p.sigmaB)
        })
      else
        LogDensity.normal(p.aRaw, 0.0.lift[A], 1.0.lift[A]) +
          LogDensity.normal(p.bRaw, 0.0.lift[A], 1.0.lift[A])
    top + effects +
      LogDensity.beta((p.phi + 1.0.lift[A]) / 2.0.lift[A], 2.0.lift[A], 2.0.lift[A]) +
      LogDensity.halfNormal(p.tau, wobbleScalePrior.lift[A]) +
      LogDensity.normal(p.eps, 0.0.lift[A], 1.0.lift[A])

  /** Total, as [[Ar1Trend.logRate]] is: months beyond the declared innovations give `NaN`. */
  def logRate[A: Real](p: HierarchicalAr1Params[A], group: Int, t: IArray[Double]): IArray[A] =
    val r = Real[A]
    val z = Ar1Trend.wobbleOf(p.phi, p.tau, p.eps)
    val known = group >= 0 && group < p.aRaw.length
    val (a, b) = if known then effectsOf(p, group) else (r.const(Double.NaN), r.const(Double.NaN))
    r.vectorOf(t.length)(m =>
      if m >= z.length || !known then r.const(Double.NaN) else a + b * t(m).lift[A] + z(m)
    )

  def samplePrior[F[_]: Monad: Rng](groups: Int, months: Int): F[HierarchicalAr1Params[Double]] =
    for
      muA <- Monad[F].replicateA(
        populations,
        Rng[F].normal.map(z => levelPrior._1 + levelPrior._2 * z)
      )
      muB <- Monad[F].replicateA(
        populations,
        Rng[F].normal.map(z => trendPrior._1 + trendPrior._2 * z)
      )
      sigmaA <- Rng[F].normal.map(z => math.abs(spreadPrior * z))
      sigmaB <- Rng[F].normal.map(z => math.abs(spreadPrior * z))
      aRaw <- Monad[F].replicateA(groups, Rng[F].normal)
      bRaw <- Monad[F].replicateA(groups, Rng[F].normal)
      phi <- Sampling.beta[F](2.0, 2.0).map(x => 2.0 * x - 1.0)
      tau <- Rng[F].normal.map(z => math.abs(wobbleScalePrior * z))
      eps <- Monad[F].replicateA(months, Rng[F].normal)
    yield
      // Drawn in the parameterisation the component declares: standardised unless centred.
      val levels =
        if centred then IArray.tabulate(groups)(g => muA(populationOf(g)) + sigmaA * aRaw(g))
        else IArray.from(aRaw)
      val trends =
        if centred then IArray.tabulate(groups)(g => muB(populationOf(g)) + sigmaB * bRaw(g))
        else IArray.from(bRaw)
      HierarchicalAr1Params(
        IArray.from(muA),
        IArray.from(muB),
        sigmaA,
        sigmaB,
        levels,
        trends,
        phi,
        tau,
        IArray.from(eps)
      )

  def named(p: HierarchicalAr1Params[Double]): Map[String, IArray[Double]] =
    Map(
      "muA" -> p.muA,
      "muB" -> p.muB,
      "sigmaA" -> IArray(p.sigmaA),
      "sigmaB" -> IArray(p.sigmaB),
      "phi" -> IArray(p.phi),
      "tau" -> IArray(p.tau)
    )

  def project(
      p: HierarchicalAr1Params[Double],
      group: Int,
      futureT: IArray[Double],
      seed: Seed
  ): IArray[Double] =
    if futureT.length == 0 then IArray.empty[Double]
    else
      val z = Ar1Trend.wobbleOf[Double](p.phi, p.tau, p.eps)
      val last = if z.length == 0 then 0.0 else z(z.length - 1)
      val ahead = Ar1.project(p.phi, p.tau, last, futureT.length, seed)
      val (a, b) = effectsOf[Double](p, group)
      IArray.tabulate(futureT.length)(h => a + b * futureT(h) + ahead(h))

  /** The group's own level and trend, however they are parameterised. */
  private def effectsOf[A: Real](p: HierarchicalAr1Params[A], group: Int): (A, A) =
    val which = populationOf(group)
    if centred then (p.aRaw(group), p.bRaw(group))
    else (p.muA(which) + p.sigmaA * p.aRaw(group), p.muB(which) + p.sigmaB * p.bRaw(group))

  private def populationOf(group: Int): Int =
    if population.length == 0 then 0
    else if group < 0 || group >= population.length then 0
    else population(group)

object Ar1TrendHierarchical:

  /** The AR(1) part, as a sub-spec: `mapN` stops at eight blocks and this component has nine. */
  final case class Shared[A](phi: A, tau: A, eps: IArray[A])

  /** Checked: every group belongs to a population that exists. */
  def of(population: IArray[Int], populations: Int): Either[BayesError, Ar1TrendHierarchical] =
    val offending =
      (0 until population.length).find(g => population(g) < 0 || population(g) >= populations)
    offending match
      case Some(g) =>
        Left(
          BayesError.InvalidArgument(
            "Ar1TrendHierarchical.of",
            s"population($g)",
            population(g).toDouble,
            s"a population below $populations"
          )
        )
      case None => Right(Ar1TrendHierarchical(population))

/** A starting level per group and a random walk shared by all of them. */
final case class LocalLevelParams[A](level0: IArray[A], sigma: A, eps: IArray[A])

/** `log lambda[g, m] = level0[g] + sigma * (eps[0] + ... + eps[m])`: a random walk, no trend.
  *
  * The alternative story to `Ar1Trend`, and the one to reach for when a series moves but does not
  * move *in a direction*. A walk's forecast is its last level: flat in expectation, with a spread
  * that grows as the square root of the horizon, which is the honest statement that the further
  * out you look the less you know — and the opposite of a trend, which claims to know the
  * direction better the further out you go.
  *
  * Fitted to data that is not trending it cannot invent a trend, because it has no parameter for
  * one. That is the phase's criterion, and it is checked both ways: this component's projection
  * does not drift, and `Ar1Trend` fitted to the same data puts zero inside its trend's interval.
  */
final case class LocalLevel(
    levelPrior: (Double, Double) = (0.0, 2.0),
    stepPrior: Double = 0.5
) extends RateComponent[LocalLevelParams]:

  def params(groups: Int, months: Int): ParamSpec[LocalLevelParams] =
    (
      ParamSpec.vector("level0", groups),
      ParamSpec.positive("sigma"),
      ParamSpec.vector("eps", months)
    ).mapN[LocalLevelParams]([A] =>
      (level0: IArray[A], sigma: A, eps: IArray[A]) => LocalLevelParams(level0, sigma, eps)
    )

  def logPrior[A: Real](p: LocalLevelParams[A]): A =
    LogDensity.normal(p.level0, levelPrior._1.lift[A], levelPrior._2.lift[A]) +
      LogDensity.halfNormal(p.sigma, stepPrior.lift[A]) +
      LogDensity.normal(p.eps, 0.0.lift[A], 1.0.lift[A])

  /** Total, as [[Ar1Trend.logRate]] is: months beyond the declared innovations give `NaN`. */
  def logRate[A: Real](p: LocalLevelParams[A], group: Int, t: IArray[Double]): IArray[A] =
    val r = Real[A]
    val walk = r.cumulativeSum(p.eps)
    val known = group >= 0 && group < p.level0.length
    val start = if known then p.level0(group) else r.const(Double.NaN)
    r.vectorOf(t.length)(m =>
      if m >= walk.length || !known then r.const(Double.NaN) else start + p.sigma * walk(m)
    )

  def samplePrior[F[_]: Monad: Rng](groups: Int, months: Int): F[LocalLevelParams[Double]] =
    for
      level0 <- Monad[F].replicateA(
        groups,
        Rng[F].normal.map(z => levelPrior._1 + levelPrior._2 * z)
      )
      sigma <- Rng[F].normal.map(z => math.abs(stepPrior * z))
      eps <- Monad[F].replicateA(months, Rng[F].normal)
    yield LocalLevelParams(IArray.from(level0), sigma, IArray.from(eps))

  def named(p: LocalLevelParams[Double]): Map[String, IArray[Double]] =
    Map("level0" -> p.level0, "sigma" -> IArray(p.sigma))

  def project(
      p: LocalLevelParams[Double],
      group: Int,
      futureT: IArray[Double],
      seed: Seed
  ): IArray[Double] =
    if futureT.length == 0 then IArray.empty[Double]
    else
      val walk = Real[Double].cumulativeSum(p.eps)
      val start =
        if p.level0.length == 0 then 0.0 else p.level0(math.min(group, p.level0.length - 1))
      val last = start + p.sigma * (if walk.length == 0 then 0.0 else walk(walk.length - 1))
      // A walk continues from where it is, with no pull back to anything: `phi = 1`.
      Ar1.project(1.0, p.sigma, last, futureT.length, seed)

/** Common cut points, plus one offset per group. */
final case class GroupedDelayParams[A](
    cut0: A,
    increments: IArray[A],
    beta: A,
    delta: IArray[A],
    sigmaCut: A
)

/** `cut[g] = cut + delta[g]`, with `delta[g] ~ Gaussian(0, sigmaCut)`.
  *
  * One reporting speed per group, drawn around a common one. A positive offset raises every cut
  * point together, which moves mass towards the short delays: that group reports faster. The
  * shape of the delay distribution is shared and only its position moves, which is the assumption
  * worth knowing about — a group that is slow at the start and fast at the end is not expressible
  * here, and would need its own increments.
  */
final case class OrderedLogitDriftByGroup(
    firstCutPrior: (Double, Double) = (0.0, 2.0),
    incrementPrior: Double = 1.0,
    driftPrior: Double = 1.0,
    offsetPrior: Double = 0.5
) extends DelayComponent[GroupedDelayParams]:

  def params(groups: Int, maxLag: Int): ParamSpec[GroupedDelayParams] =
    (
      ParamSpec.real("cut0"),
      ParamSpec.vector("inc", math.max(maxLag - 1, 0), Constraint.Positive),
      ParamSpec.real("beta"),
      ParamSpec.vector("delta", groups),
      ParamSpec.positive("sigmaCut")
    ).mapN[GroupedDelayParams]([A] =>
      (cut0: A, inc: IArray[A], beta: A, delta: IArray[A], sigmaCut: A) =>
        GroupedDelayParams(cut0, inc, beta, delta, sigmaCut)
    )

  def logPrior[A: Real](p: GroupedDelayParams[A]): A =
    val r = Real[A]
    val increments = r.sum(
      r.vectorOf(p.increments.length)(k =>
        LogDensity.halfNormal(p.increments(k), incrementPrior.lift[A])
      )
    )
    LogDensity.normal(p.cut0, firstCutPrior._1.lift[A], firstCutPrior._2.lift[A]) +
      increments +
      LogDensity.normal(p.beta, 0.0.lift[A], driftPrior.lift[A]) +
      LogDensity.normal(p.delta, 0.0.lift[A], p.sigmaCut) +
      LogDensity.halfNormal(p.sigmaCut, offsetPrior.lift[A])

  def logPmf[A: Real](p: GroupedDelayParams[A], group: Int, t: Double): IArray[A] =
    val r = Real[A]
    val running = r.cumulativeSum(p.increments)
    val offset =
      if p.delta.length == 0 then r.const(0.0) else p.delta(math.min(group, p.delta.length - 1))
    val cuts =
      r.vectorOf(p.increments.length + 1)(k =>
        (if k == 0 then p.cut0 else p.cut0 + running(k - 1)) + offset
      )
    val eta = p.beta * t.lift[A]
    r.vectorOf(cuts.length + 1)(k => LogDensity.orderedLogistic(k + 1, eta, cuts))

  def samplePrior[F[_]: Monad: Rng](groups: Int, maxLag: Int): F[GroupedDelayParams[Double]] =
    for
      cut0 <- Rng[F].normal.map(z => firstCutPrior._1 + firstCutPrior._2 * z)
      inc <- Monad[F].replicateA(
        math.max(maxLag - 1, 0),
        Rng[F].normal.map(z => math.abs(incrementPrior * z))
      )
      beta <- Rng[F].normal.map(_ * driftPrior)
      sigmaCut <- Rng[F].normal.map(z => math.abs(offsetPrior * z))
      delta <- Monad[F].replicateA(groups, Rng[F].normal.map(_ * sigmaCut))
    yield GroupedDelayParams(cut0, IArray.from(inc), beta, IArray.from(delta), sigmaCut)

  def named(p: GroupedDelayParams[Double]): Map[String, IArray[Double]] =
    Map("cut0" -> IArray(p.cut0), "beta" -> IArray(p.beta), "sigmaCut" -> IArray(p.sigmaCut))
