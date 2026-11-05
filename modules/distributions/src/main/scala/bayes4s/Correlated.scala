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

/** A multivariate Gaussian given by the Cholesky factor of its covariance.
  *
  * The factor rather than the covariance, because that is what a model parameterises: sampling a
  * covariance matrix directly means sampling something that must stay positive definite, and the
  * factor makes that free. Not a [[ContinuousLaw]]: its variable is a vector.
  */
final case class MultiNormalCholesky private[bayes4s] (mu: IArray[Double], l: Cholesky):

  def logPdf(y: IArray[Double]): Double =
    if y.length != mu.length then Double.NegativeInfinity
    else if y.toList.exists(_.isNaN) then Double.NaN
    else
      val deviation = IArray.tabulate(y.length)(i => y(i) - mu(i))
      Linalg.lowerSolve(l, deviation) match
        case Left(_) => Double.NaN
        case Right(z) =>
          -0.5 * y.length * Laws.Log2Pi - Linalg.logDetFromCholesky(l) -
            0.5 * z.toList.foldLeft(0.0)((s, v) => s + v * v)

  def mean: IArray[Double] = mu

  /** `mu + L z` for a standard Gaussian z. The product is written out rather than routed through
    * `matVec`, because the factor is triangular and because the dimensions cannot be wrong here.
    */
  def sample[F[_]: Monad: Rng]: F[IArray[Double]] =
    Monad[F].replicateA(mu.length, Rng[F].normal).map { z =>
      IArray.tabulate(mu.length) { i =>
        mu(i) + (0 to i).foldLeft(0.0)((s, j) => s + l.matrix(i, j) * z(j))
      }
    }

object MultiNormalCholesky:
  def of(mu: IArray[Double], l: Cholesky): Either[BayesError, MultiNormalCholesky] =
    if mu.length != l.dimension then
      Left(BayesError.DimensionMismatch("MultiNormalCholesky.of", l.dimension, mu.length))
    else
      mu.toList.zipWithIndex
        .collectFirst {
          case (m, i) if m.isNaN || m.isInfinite =>
            BayesError.InvalidArgument("MultiNormalCholesky.of", s"mu($i)", m, "finite")
        }
        .toLeft(MultiNormalCholesky(mu, l))

/** The LKJ prior on the Cholesky factor of a correlation matrix (Lewandowski, Kurowicka and Joe
  * 2009).
  *
  * `eta = 1` is uniform over correlation matrices; above 1 it concentrates towards the identity,
  * which is how a model says "correlations exist but are probably not extreme".
  *
  * The density is the constant plus a weighted sum of the logs of the diagonal: the weight
  * `2(eta - 1) + K - i - 1` is the exponent of the determinant and the Jacobian of `Sigma = L L'`
  * together, and dropping the second half of it is a mistake that no single case would reveal.
  */
final case class LkjCorrCholesky private[bayes4s] (dimension: Int, eta: Double):

  /** The log normalising constant, by the product form of Theorem 5.
    *
    * Written once for every concentration, including `eta = 1`: the product form needs no separate
    * branch there, which the twenty reference constants confirm.
    */
  val logConstant: Double =
    -(1 until dimension).foldLeft(0.0) { (accumulated, k) =>
      val shape = eta + (dimension - k - 1) / 2.0
      val weight = (dimension - k).toDouble
      accumulated + (2.0 * eta - 2.0 + dimension - k) * weight * Laws.Log2 +
        weight * Special.lbeta(shape, shape)
    }

  def logPdf(l: Cholesky): Double =
    if l.dimension != dimension then Double.NegativeInfinity
    else
      logConstant + (0 until dimension).foldLeft(0.0) { (accumulated, i) =>
        accumulated + (2.0 * (eta - 1.0) + dimension - i - 1) * math.log(l.diagonal(i))
      }

  /** The onion method: a two by two block, then one row at a time, each row a point drawn
    * uniformly on a sphere and scaled by a Beta-distributed radius.
    *
    * A draw whose last diagonal entry underflows to zero is not a factor, so it is rejected and
    * redrawn. That has probability zero and keeps the function total, which a clamp would not.
    */
  def sample[F[_]: Monad: Rng]: F[Cholesky] =
    Monad[F].tailRecM(())(_ => drawOnce[F].map(_.toRight(())))

  private def padded(entries: Vector[Double]): Vector[Double] =
    entries ++ Vector.fill(dimension - entries.length)(0.0)

  private def drawOnce[F[_]: Monad: Rng]: F[Option[Cholesky]] =
    val first = eta + (dimension - 2) / 2.0
    val start = Sampling.beta[F](first, first).map { b =>
      val r = 2.0 * b - 1.0
      Vector(padded(Vector(1.0)), padded(Vector(r, math.sqrt(1.0 - r * r))))
    }
    val grown = (2 until dimension).toList.foldLeft(start) { (accumulated, k) =>
      for
        previous <- accumulated
        shape = first - (k - 1) / 2.0
        radius <- Sampling.beta[F](k / 2.0, shape)
        direction <- Monad[F].replicateA(k, Rng[F].normal)
      yield
        val norm = math.sqrt(direction.foldLeft(0.0)((s, x) => s + x * x))
        val scaled = direction.map(x => math.sqrt(radius) * x / norm)
        previous :+ padded(scaled.toVector :+ math.sqrt(1.0 - radius))
    }
    grown.map { rows =>
      Matrix
        .ofRows(IArray.unsafeFromArray(rows.map(r => IArray.unsafeFromArray(r.toArray)).toArray))
        .flatMap(Cholesky.ofLower)
        .toOption
    }

object LkjCorrCholesky:
  def of(dimension: Int, eta: Double): Either[BayesError, LkjCorrCholesky] =
    if dimension < 2 then
      Left(
        BayesError.InvalidArgument(
          "LkjCorrCholesky.of",
          "dimension",
          dimension.toDouble,
          "at least two: a correlation needs two variables"
        )
      )
    else
      Laws
        .positiveFinite("LkjCorrCholesky.of", "eta", eta)
        .map(_ => LkjCorrCholesky(dimension, eta))
