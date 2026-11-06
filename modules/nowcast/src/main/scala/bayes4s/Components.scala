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

/** How the rate of creation moves over time.
  *
  * A component, not a model: it declares its own parameters, its own prior, how it turns them into
  * a log rate per month, how to draw it from the prior, and how to carry it into the future.
  * Trying a different story about the rate means writing another one of these, not rewriting the
  * count model.
  */
trait RateComponent[P[_]]:
  def params(groups: Int, months: Int): ParamSpec[P]
  def logPrior[A: Real](p: P[A]): A
  def logRate[A: Real](p: P[A], group: Int, t: IArray[Double]): IArray[A]
  def samplePrior[F[_]: Monad: Rng](groups: Int, months: Int): F[P[Double]]

  /** The parameters worth naming in a report, a CSV row, or a calibration rank.
    *
    * The component decides, because only it knows which of its parameters mean anything on their
    * own: an AR(1)'s innovations are standard normal by construction and there are as many of them
    * as there are periods, so ranking them would multiply the number of checks without testing
    * anything the rate they build does not already test (docs/design.md).
    */
  def named(p: P[Double]): Map[String, IArray[Double]]

  /** The log rate at times the data does not cover.
    *
    * It takes the future **times**, not a horizon, because a trend cannot be projected without
    * knowing when the future is: a horizon of three is three months only if the time axis is
    * months, and the component has no way to know that (docs/design.md).
    */
  def project(p: P[Double], group: Int, futureT: IArray[Double], seed: Seed): IArray[Double]

/** How long reporting takes: a distribution over delays `0..maxLag` for a cohort created at `t`. */
trait DelayComponent[P[_]]:
  def params(groups: Int, maxLag: Int): ParamSpec[P]
  def logPrior[A: Real](p: P[A]): A
  def logPmf[A: Real](p: P[A], group: Int, t: Double): IArray[A]
  def samplePrior[F[_]: Monad: Rng](groups: Int, maxLag: Int): F[P[Double]]

  /** The parameters worth naming, as in [[RateComponent.named]]. */
  def named(p: P[Double]): Map[String, IArray[Double]]

/** A level and a trend per group, plus an AR(1) wobble shared by all of them. */
final case class Ar1TrendParams[A](
    a: IArray[A],
    b: IArray[A],
    phi: A,
    tau: A,
    eps: IArray[A]
)

/** `log lambda[g, m] = a[g] + b[g] t[m] + z[m]`, with `z` an AR(1) in non-centred form.
  *
  * The innovations `eps` are the declared parameters and `z` is built from them, which is what
  * keeps the sampler out of the funnel when `tau` can approach zero — the same choice as the
  * non-centred multilevel models of chapter 11.
  *
  * `z[0]` is drawn from the **stationary** distribution, `tau/sqrt(1 - phi²)`, rather than started
  * at zero. Starting at zero says the first month of the data is known exactly, which is false and
  * shows up as an artificially certain first month in every simulation from the prior.
  */
final case class Ar1Trend(
    levelPrior: (Double, Double) = (0.0, 2.0),
    trendPrior: (Double, Double) = (0.0, 1.0),
    wobbleScalePrior: Double = 0.5
) extends RateComponent[Ar1TrendParams]:

  def params(groups: Int, months: Int): ParamSpec[Ar1TrendParams] =
    (
      ParamSpec.vector("a", groups),
      ParamSpec.vector("b", groups),
      ParamSpec.bounded("phi", -1.0, 1.0),
      ParamSpec.positive("tau"),
      ParamSpec.vector("eps", months)
    ).mapN[Ar1TrendParams]([A] =>
      (a: IArray[A], b: IArray[A], phi: A, tau: A, eps: IArray[A]) =>
        Ar1TrendParams(a, b, phi, tau, eps)
    )

  /** The prior on the persistence is a Beta(2, 2) on `(phi + 1)/2`, and that is load-bearing.
    *
    * Its density is proportional to `1 - phi²`, which **exactly cancels** the stationary scale's
    * `1/sqrt(1 - phi²)` blow-up at the boundary. A uniform prior on (-1, 1) looks more honest and
    * is not: it leaves a singularity at a unit root, and the reference run of this model reported
    * 173 divergent transitions in 32 000 from it — against 1 with the Beta (docs/validation.md).
    * It also says something sensible, that a unit root is less likely than a middling
    * persistence, which is what a stationary model is for.
    */
  def logPrior[A: Real](p: Ar1TrendParams[A]): A =
    val persistence =
      LogDensity.beta((p.phi + 1.0.lift[A]) / 2.0.lift[A], 2.0.lift[A], 2.0.lift[A])
    LogDensity.normal(p.a, levelPrior._1.lift[A], levelPrior._2.lift[A]) +
      LogDensity.normal(p.b, trendPrior._1.lift[A], trendPrior._2.lift[A]) +
      persistence +
      LogDensity.halfNormal(p.tau, wobbleScalePrior.lift[A]) +
      LogDensity.normal(p.eps, 0.0.lift[A], 1.0.lift[A])

  def logRate[A: Real](p: Ar1TrendParams[A], group: Int, t: IArray[Double]): IArray[A] =
    val z = Ar1Trend.wobbleOf(p.phi, p.tau, p.eps)
    Real[A].vectorOf(t.length)(m => p.a(group) + p.b(group) * t(m).lift[A] + z(m))

  def samplePrior[F[_]: Monad: Rng](groups: Int, months: Int): F[Ar1TrendParams[Double]] =
    for
      a <- Monad[F].replicateA(groups, Rng[F].normal.map(z => levelPrior._1 + levelPrior._2 * z))
      b <- Monad[F].replicateA(groups, Rng[F].normal.map(z => trendPrior._1 + trendPrior._2 * z))
      // `2 Beta(2, 2) - 1`, matching the prior above.
      phi <- Sampling.beta[F](2.0, 2.0).map(x => 2.0 * x - 1.0)
      tau <- Rng[F].normal.map(z => math.abs(wobbleScalePrior * z))
      eps <- Monad[F].replicateA(months, Rng[F].normal)
    yield Ar1TrendParams(IArray.from(a), IArray.from(b), phi, tau, IArray.from(eps))

  def named(p: Ar1TrendParams[Double]): Map[String, IArray[Double]] =
    Map("a" -> p.a, "b" -> p.b, "phi" -> IArray(p.phi), "tau" -> IArray(p.tau))

  def project(
      p: Ar1TrendParams[Double],
      group: Int,
      futureT: IArray[Double],
      seed: Seed
  ): IArray[Double] =
    if futureT.length == 0 then IArray.empty[Double]
    else
      val z = Ar1Trend.wobbleOf[Double](p.phi, p.tau, p.eps)
      val last = if z.length == 0 then 0.0 else z(z.length - 1)
      // The wobble continues from where the data left it, which is the most informative thing the
      // fit knows about the present; restarting at zero would throw it away.
      val ahead = Ar1.project(p.phi, p.tau, last, futureT.length, seed)
      IArray.tabulate(futureT.length)(h => p.a(group) + p.b(group) * futureT(h) + ahead(h))

object Ar1Trend:

  /** The wobble itself, for a test or a diagnostic to look at. */
  def wobble(phi: Double, tau: Double, eps: IArray[Double]): IArray[Double] =
    wobbleOf[Double](phi, tau, eps)

  /** `z[0] = tau/sqrt(1 - phi²) eps[0]`, then `z[m] = phi z[m-1] + tau eps[m]`. */
  private[bayes4s] def wobbleOf[A: Real](phi: A, tau: A, eps: IArray[A]): IArray[A] =
    val r = Real[A]
    if eps.length == 0 then r.vectorOf(0)(_ => r.const(0.0))
    else
      val stationary = tau / r.sqrt(1.0.lift[A] - phi * phi)
      val built = (1 until eps.length).foldLeft(Vector(stationary * eps(0))) { (collected, m) =>
        collected :+ (phi * collected(m - 1) + tau * eps(m))
      }
      r.vectorOf(eps.length)(m => built(m))

/** Cut points on the log-odds scale and a drift in time. */
final case class OrderedLogitDriftParams[A](cut0: A, increments: IArray[A], beta: A)

/** `F[m](k) = invLogit(cut[k] - beta t[m])`, with the cut points increasing by construction.
  *
  * The delay distribution is an ordered categorical one: the cut points say how much mass has
  * arrived by each delay, and `beta` tilts them with time so that reporting getting slower — or
  * faster — is something the model can say. **A positive `beta` means slower**: it pushes mass
  * towards longer delays as `t` grows.
  *
  * The probabilities are the differences of the cumulative function, and they are computed through
  * the ordered logistic of `LogDensity`, which is Stan's identity for the difference of two
  * `inv_logit`s and keeps its digits where the naive subtraction does not.
  */
final case class OrderedLogitDrift(
    firstCutPrior: (Double, Double) = (0.0, 2.0),
    incrementPrior: Double = 1.0,
    driftPrior: Double = 1.0
) extends DelayComponent[OrderedLogitDriftParams]:

  def params(groups: Int, maxLag: Int): ParamSpec[OrderedLogitDriftParams] =
    (
      ParamSpec.real("cut0"),
      ParamSpec.vector("inc", math.max(maxLag - 1, 0), Constraint.Positive),
      ParamSpec.real("beta")
    ).mapN[OrderedLogitDriftParams]([A] =>
      (cut0: A, inc: IArray[A], beta: A) => OrderedLogitDriftParams(cut0, inc, beta)
    )

  def logPrior[A: Real](p: OrderedLogitDriftParams[A]): A =
    val r = Real[A]
    val increments = r.sum(
      r.vectorOf(p.increments.length)(k =>
        LogDensity.halfNormal(p.increments(k), incrementPrior.lift[A])
      )
    )
    LogDensity.normal(p.cut0, firstCutPrior._1.lift[A], firstCutPrior._2.lift[A]) +
      increments +
      LogDensity.normal(p.beta, 0.0.lift[A], driftPrior.lift[A])

  def logPmf[A: Real](p: OrderedLogitDriftParams[A], group: Int, t: Double): IArray[A] =
    val r = Real[A]
    val cuts = OrderedLogitDrift.cutsOf(p)
    val eta = p.beta * t.lift[A]
    r.vectorOf(cuts.length + 1)(k => LogDensity.orderedLogistic(k + 1, eta, cuts))

  def samplePrior[F[_]: Monad: Rng](
      groups: Int,
      maxLag: Int
  ): F[OrderedLogitDriftParams[Double]] =
    for
      cut0 <- Rng[F].normal.map(z => firstCutPrior._1 + firstCutPrior._2 * z)
      inc <- Monad[F].replicateA(
        math.max(maxLag - 1, 0),
        Rng[F].normal.map(z => math.abs(incrementPrior * z))
      )
      beta <- Rng[F].normal.map(_ * driftPrior)
    yield OrderedLogitDriftParams(cut0, IArray.from(inc), beta)

  def named(p: OrderedLogitDriftParams[Double]): Map[String, IArray[Double]] =
    Map("cut0" -> IArray(p.cut0), "beta" -> IArray(p.beta))

object OrderedLogitDrift:

  /** The cut points, for a test or a report to read. */
  def cutPoints[A: Real](p: OrderedLogitDriftParams[A]): IArray[A] = cutsOf(p)

  /** `cut[0] = cut0`, `cut[k] = cut0 + sum of the first k increments`. */
  private[bayes4s] def cutsOf[A: Real](p: OrderedLogitDriftParams[A]): IArray[A] =
    val r = Real[A]
    val running = r.cumulativeSum(p.increments)
    r.vectorOf(p.increments.length + 1)(k => if k == 0 then p.cut0 else p.cut0 + running(k - 1))
