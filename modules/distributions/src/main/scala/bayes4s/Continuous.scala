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

import cats.Monad
import cats.syntax.all.*

/** Normalising constants are included, as in Stan's `*_lpdf`. */
final case class Normal private (mu: Double, sigma: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    val z = (x - mu) / sigma
    -0.5 * z * z - math.log(sigma) - Laws.HalfLog2Pi
  def mean: Double = mu
  def variance: Double = sigma * sigma
  def sample[F[_]: Monad: Rng]: F[Double] = Rng[F].normal.map(z => mu + sigma * z)

object Normal:
  def of(mu: Double, sigma: Double): Either[BayesError.InvalidArgument, Normal] =
    for
      _ <- Laws.finite("Normal.of", "mu", mu)
      _ <- Laws.positiveFinite("Normal.of", "sigma", sigma)
    yield Normal(mu, sigma)

  /** The standard Gaussian, Stan's `std_normal`. */
  def stdNormal: Normal = Normal(0.0, 1.0)

  @deprecated("renamed to stdNormal, the Stan name (std_normal)", "0.1")
  def standard: Normal = stdNormal

/** The positive half of a normal centred on zero, which is how a scale gets a weakly informative
  * prior throughout the library.
  */
final case class HalfNormal private (sigma: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x < 0.0 then Double.NegativeInfinity
    else
      val z = x / sigma
      Laws.HalfLog2OverPi - math.log(sigma) - 0.5 * z * z
  def mean: Double = sigma * math.sqrt(2.0 / math.Pi)
  def variance: Double = sigma * sigma * (1.0 - 2.0 / math.Pi)
  def sample[F[_]: Monad: Rng]: F[Double] = Rng[F].normal.map(z => math.abs(sigma * z))

object HalfNormal:
  def of(sigma: Double): Either[BayesError.InvalidArgument, HalfNormal] =
    Laws.positiveFinite("HalfNormal.of", "sigma", sigma).map(_ => HalfNormal(sigma))

final case class LogNormal private (mu: Double, sigma: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x <= 0.0 then Double.NegativeInfinity
    else
      val logX = math.log(x)
      val z = (logX - mu) / sigma
      -0.5 * z * z - math.log(sigma) - Laws.HalfLog2Pi - logX
  def mean: Double = math.exp(mu + 0.5 * sigma * sigma)
  def variance: Double =
    val s2 = sigma * sigma
    math.expm1(s2) * math.exp(2.0 * mu + s2)
  def sample[F[_]: Monad: Rng]: F[Double] = Rng[F].normal.map(z => math.exp(mu + sigma * z))

object LogNormal:
  def of(mu: Double, sigma: Double): Either[BayesError.InvalidArgument, LogNormal] =
    for
      _ <- Laws.finite("LogNormal.of", "mu", mu)
      _ <- Laws.positiveFinite("LogNormal.of", "sigma", sigma)
    yield LogNormal(mu, sigma)

/** Shape and **rate**, as in Stan. */
final case class Gamma private (alpha: Double, beta: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x < 0.0 then Double.NegativeInfinity
    else if x == 0.0 then
      // The density at the boundary is not a limit to be guessed: it diverges below shape 1, is
      // the rate itself at shape 1, and vanishes above.
      if alpha < 1.0 then Double.PositiveInfinity
      else if alpha == 1.0 then math.log(beta)
      else Double.NegativeInfinity
    else alpha * math.log(beta) - Special.lgamma(alpha) + (alpha - 1.0) * math.log(x) - beta * x
  def mean: Double = alpha / beta
  def variance: Double = alpha / (beta * beta)
  def sample[F[_]: Monad: Rng]: F[Double] = Sampling.gamma[F](alpha).map(_ / beta)

object Gamma:
  def of(alpha: Double, beta: Double): Either[BayesError.InvalidArgument, Gamma] =
    for
      _ <- Laws.positiveFinite("Gamma.of", "alpha", alpha)
      _ <- Laws.positiveFinite("Gamma.of", "beta", beta)
    yield Gamma(alpha, beta)

final case class Beta private (alpha: Double, beta: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x < 0.0 || x > 1.0 then Double.NegativeInfinity
    else if x == 0.0 then boundary(alpha)
    else if x == 1.0 then boundary(beta)
    else
      (alpha - 1.0) * math.log(x) + (beta - 1.0) * math.log1p(-x) -
        Special.lbeta(alpha, beta)
  private def boundary(exponent: Double): Double =
    if exponent < 1.0 then Double.PositiveInfinity
    else if exponent == 1.0 then -Special.lbeta(alpha, beta)
    else Double.NegativeInfinity
  def mean: Double = alpha / (alpha + beta)
  def variance: Double =
    val total = alpha + beta
    alpha * beta / (total * total * (total + 1.0))
  def sample[F[_]: Monad: Rng]: F[Double] = Sampling.beta[F](alpha, beta)

object Beta:
  def of(alpha: Double, beta: Double): Either[BayesError.InvalidArgument, Beta] =
    for
      _ <- Laws.positiveFinite("Beta.of", "alpha", alpha)
      _ <- Laws.positiveFinite("Beta.of", "beta", beta)
    yield Beta(alpha, beta)

final case class Logistic private (mu: Double, sigma: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    val z = (x - mu) / sigma
    -z - math.log(sigma) - 2.0 * LogExp.log1pExp(-z)
  def mean: Double = mu
  def variance: Double = sigma * sigma * math.Pi * math.Pi / 3.0
  def sample[F[_]: Monad: Rng]: F[Double] =
    Rng[F].uniform.map(u => mu + sigma * LogExp.logit(u))

object Logistic:
  def of(mu: Double, sigma: Double): Either[BayesError.InvalidArgument, Logistic] =
    for
      _ <- Laws.finite("Logistic.of", "mu", mu)
      _ <- Laws.positiveFinite("Logistic.of", "sigma", sigma)
    yield Logistic(mu, sigma)
