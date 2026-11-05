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

/** The heavy-tailed scalar laws the multilevel models use as priors on a scale, and the
  * exponential.
  *
  * `HalfStudentT` and `HalfCauchy` are the two shorthands the naming rule allows: Stan writes the
  * full law with `<lower=0>`, which a value cannot express on its own.
  *
  * A heavy tail means a missing moment, and the laws say so rather than inventing one: a Cauchy
  * has no mean, and a Student-t has no variance below two degrees of freedom. `mean` and
  * `variance` return `NaN` there, which is what a total function must do, and the suites judge
  * those laws by their whole distribution instead.
  */
final case class StudentT private[bayes4s] (nu: Double, mu: Double, sigma: Double)
    extends ContinuousLaw:
  def logPdf(x: Double): Double =
    val z = (x - mu) / sigma
    Special.lgamma(0.5 * (nu + 1.0)) - Special.lgamma(0.5 * nu) -
      0.5 * math.log(nu) - Laws.HalfLogPi - math.log(sigma) -
      0.5 * (nu + 1.0) * math.log1p(z * z / nu)
  def mean: Double = if nu > 1.0 then mu else Double.NaN
  def variance: Double =
    if nu > 2.0 then sigma * sigma * nu / (nu - 2.0)
    else if nu > 1.0 then Double.PositiveInfinity
    else Double.NaN

  /** A normal scaled by a chi-squared, which is what a Student-t is. */
  def sample[F[_]: Monad: Rng]: F[Double] =
    for
      z <- Rng[F].normal
      g <- Sampling.gamma[F](0.5 * nu)
    yield mu + sigma * z * math.sqrt(0.5 * nu / g)

object StudentT:
  def of(nu: Double, mu: Double, sigma: Double): Either[BayesError.InvalidArgument, StudentT] =
    for
      _ <- Laws.positiveFinite("StudentT.of", "nu", nu)
      _ <- Laws.finite("StudentT.of", "mu", mu)
      _ <- Laws.positiveFinite("StudentT.of", "sigma", sigma)
    yield StudentT(nu, mu, sigma)

final case class Cauchy private[bayes4s] (mu: Double, sigma: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    val z = (x - mu) / sigma
    -Laws.LogPi - math.log(sigma) - math.log1p(z * z)

  /** A Cauchy has no mean, and none is invented. */
  def mean: Double = Double.NaN
  def variance: Double = Double.NaN
  def sample[F[_]: Monad: Rng]: F[Double] =
    Rng[F].uniform.map(u => mu + sigma * math.tan(math.Pi * (u - 0.5)))

object Cauchy:
  def of(mu: Double, sigma: Double): Either[BayesError.InvalidArgument, Cauchy] =
    for
      _ <- Laws.finite("Cauchy.of", "mu", mu)
      _ <- Laws.positiveFinite("Cauchy.of", "sigma", sigma)
    yield Cauchy(mu, sigma)

/** The positive half of a Student-t centred on zero. */
final case class HalfStudentT private (nu: Double, sigma: Double) extends ContinuousLaw:
  private val full: StudentT = StudentT(nu, 0.0, sigma)
  def logPdf(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x < 0.0 then Double.NegativeInfinity
    else Laws.Log2 + full.logPdf(x)
  def mean: Double =
    if nu > 1.0 then
      sigma * math.exp(
        Laws.Log2 + 0.5 * (math.log(nu) - Laws.LogPi) +
          Special.lgamma(0.5 * (nu + 1.0)) - math.log(nu - 1.0) - Special.lgamma(0.5 * nu)
      )
    else Double.NaN

  /** The second moment of the half law is the variance of the full one: E[X^2] = E[T^2]. */
  def variance: Double =
    if nu > 2.0 then full.variance - mean * mean else Double.NaN
  def sample[F[_]: Monad: Rng]: F[Double] = full.sample[F].map(math.abs)

object HalfStudentT:
  def of(nu: Double, sigma: Double): Either[BayesError.InvalidArgument, HalfStudentT] =
    for
      _ <- Laws.positiveFinite("HalfStudentT.of", "nu", nu)
      _ <- Laws.positiveFinite("HalfStudentT.of", "sigma", sigma)
    yield HalfStudentT(nu, sigma)

final case class HalfCauchy private (sigma: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x < 0.0 then Double.NegativeInfinity
    else
      val z = x / sigma
      Laws.Log2 - Laws.LogPi - math.log(sigma) - math.log1p(z * z)
  def mean: Double = Double.NaN
  def variance: Double = Double.NaN
  def sample[F[_]: Monad: Rng]: F[Double] =
    Cauchy(0.0, sigma).sample[F].map(math.abs)

object HalfCauchy:
  def of(sigma: Double): Either[BayesError.InvalidArgument, HalfCauchy] =
    Laws.positiveFinite("HalfCauchy.of", "sigma", sigma).map(_ => HalfCauchy(sigma))

/** Rate parameterisation, as in Stan, where the argument is called `beta`. */
final case class Exponential private (beta: Double) extends ContinuousLaw:
  def logPdf(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x < 0.0 then Double.NegativeInfinity
    else math.log(beta) - beta * x
  def mean: Double = 1.0 / beta
  def variance: Double = 1.0 / (beta * beta)

  /** Inversion, which is exact because the uniform never reaches either end. */
  def sample[F[_]: Monad: Rng]: F[Double] = Rng[F].uniform.map(u => -math.log(u) / beta)

object Exponential:
  def of(beta: Double): Either[BayesError.InvalidArgument, Exponential] =
    Laws.positiveFinite("Exponential.of", "beta", beta).map(_ => Exponential(beta))
