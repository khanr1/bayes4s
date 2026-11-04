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

import scala.annotation.tailrec

import cats.Monad
import cats.syntax.all.*

final case class Poisson private (rate: Double) extends DiscreteLaw:
  def logMass(k: Int): Double =
    if k < 0 then Double.NegativeInfinity
    else if rate == 0.0 then if k == 0 then 0.0 else Double.NegativeInfinity
    else k * math.log(rate) - rate - Special.lgamma(k + 1.0)
  def mean: Double = rate
  def variance: Double = rate
  def sample[F[_]: Monad: Rng]: F[Int] = Sampling.poisson[F](rate)

object Poisson:
  /** A rate of exactly 0 is allowed: all the mass sits at 0, which is what a Poisson thinned by a
    * probability of zero has to mean, and the density says so without a `0 * log 0`.
    */
  def of(rate: Double): Either[BayesError.InvalidArgument, Poisson] =
    Laws.nonNegativeFinite("Poisson.of", "rate", rate).map(_ => Poisson(rate))

/** Poisson on the log scale, which keeps the precision Stan's `poisson_log` keeps: a log-rate of
  * -700 is an ordinary number, while its exponential is not.
  */
final case class PoissonLog private (logRate: Double) extends DiscreteLaw:
  def logMass(k: Int): Double =
    if k < 0 then Double.NegativeInfinity
    else k * logRate - math.exp(logRate) - Special.lgamma(k + 1.0)
  def mean: Double = math.exp(logRate)
  def variance: Double = math.exp(logRate)
  def sample[F[_]: Monad: Rng]: F[Int] = Sampling.poisson[F](math.exp(logRate))

object PoissonLog:
  def of(logRate: Double): Either[BayesError.InvalidArgument, PoissonLog] =
    Laws.finite("PoissonLog.of", "logRate", logRate).map(_ => PoissonLog(logRate))

/** Mean and dispersion, Stan's `neg_binomial_2`. Variance is `mu + mu^2 / phi`.
  *
  * Written through `lbeta` so that a dispersion of 1e8, where the distribution is a Poisson in all
  * but name, does not lose the digits that three cancelling lgammas would.
  */
final case class NegBinomial2 private (mu: Double, phi: Double) extends DiscreteLaw:
  def logMass(k: Int): Double =
    if k < 0 then Double.NegativeInfinity
    else
      // Each ratio is turned into a log1p of the *other* side's ratio, which never cancels: at
      // phi = 1e8 the pair (log mu - log(mu + phi)) agrees to six digits and keeps none.
      Counts.logRisingCoefficient(k, phi) - k * math.log1p(phi / mu) - phi * math.log1p(mu / phi)
  def mean: Double = mu
  def variance: Double = mu + mu * mu / phi
  def sample[F[_]: Monad: Rng]: F[Int] =
    Sampling.gamma[F](phi).flatMap(g => Sampling.poisson[F](g * mu / phi))

object NegBinomial2:
  def of(mu: Double, phi: Double): Either[BayesError.InvalidArgument, NegBinomial2] =
    for
      _ <- Laws.positiveFinite("NegBinomial2.of", "mu", mu)
      _ <- Laws.positiveFinite("NegBinomial2.of", "phi", phi)
    yield NegBinomial2(mu, phi)

/** Shape and inverse scale, Stan's `neg_binomial`: the Gamma-Poisson predictive. */
final case class NegBinomial private (alpha: Double, beta: Double) extends DiscreteLaw:
  def logMass(k: Int): Double =
    if k < 0 then Double.NegativeInfinity
    else
      Counts.logRisingCoefficient(k, alpha) - alpha * math.log1p(1.0 / beta) -
        k * math.log1p(beta)
  def mean: Double = alpha / beta
  def variance: Double = alpha * (1.0 + beta) / (beta * beta)
  def sample[F[_]: Monad: Rng]: F[Int] =
    Sampling.gamma[F](alpha).flatMap(g => Sampling.poisson[F](g / beta))

object NegBinomial:
  def of(alpha: Double, beta: Double): Either[BayesError.InvalidArgument, NegBinomial] =
    for
      _ <- Laws.positiveFinite("NegBinomial.of", "alpha", alpha)
      _ <- Laws.positiveFinite("NegBinomial.of", "beta", beta)
    yield NegBinomial(alpha, beta)

final case class Binomial private (trials: Int, probability: Double) extends DiscreteLaw:
  def logMass(k: Int): Double =
    if k < 0 || k > trials then Double.NegativeInfinity
    else if probability == 0.0 then if k == 0 then 0.0 else Double.NegativeInfinity
    else if probability == 1.0 then if k == trials then 0.0 else Double.NegativeInfinity
    else
      Laws.logBinomialCoefficient(trials.toDouble, k.toDouble) + k * math.log(probability) +
        (trials - k) * math.log1p(-probability)
  def mean: Double = trials * probability
  def variance: Double = trials * probability * (1.0 - probability)
  def sample[F[_]: Monad: Rng]: F[Int] = Counts.binomialByInversion[F](trials, probability)

object Binomial:
  def of(trials: Int, probability: Double): Either[BayesError.InvalidArgument, Binomial] =
    if trials < 0 then
      Left(
        BayesError.InvalidArgument("Binomial.of", "trials", trials.toDouble, "trials >= 0")
      )
    else
      Laws
        .unitInterval("Binomial.of", "probability", probability)
        .map(_ => Binomial(trials, probability))

/** Shared machinery for the count laws. */
private[bayes4s] object Counts:

  /** log of the rising factorial coefficient C(k + a - 1, k), through `lbeta`.
    *
    * `lgamma(k + a) - lgamma(a) - lgamma(k + 1)` is the same number and loses it: at a = 1e8 the
    * first two terms are near 1.7e9 and their difference is near 18.
    */
  def logRisingCoefficient(k: Int, a: Double): Double =
    if k == 0 then 0.0 else -math.log(k + a) - Special.lbeta(k + 1.0, a)

  /** Inversion through the mass recurrence, which costs about `trials * probability` steps rather
    * than `trials`: a thousand trials at a probability of 0.001 takes a couple of steps.
    */
  def binomialByInversion[F[_]: Monad: Rng](trials: Int, probability: Double): F[Int] =
    if trials == 0 || probability == 0.0 then Monad[F].pure(0)
    else if probability == 1.0 then Monad[F].pure(trials)
    else
      val flipped = probability > 0.5
      val p = if flipped then 1.0 - probability else probability
      val odds = p / (1.0 - p)
      val start = math.pow(1.0 - p, trials.toDouble)
      Rng[F].uniform.map { u =>
        @tailrec
        def walk(k: Int, mass: Double, cumulative: Double): Int =
          if u <= cumulative || k >= trials then k
          else
            val next = k + 1
            val nextMass = mass * odds * (trials - k).toDouble / next.toDouble
            walk(next, nextMass, cumulative + nextMass)
        val drawn = walk(0, start, start)
        if flipped then trials - drawn else drawn
      }
