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

import cats.Functor
import cats.Monad
import cats.syntax.all.*

/** Samplers written once against the `Rng` capability.
  *
  * Rejection loops are `tailRecM` in `F`: no `while`, no stack growth, and the same sequence of
  * uniforms whichever interpreter runs them, which is what lets [[Batch]] reproduce them exactly.
  *
  * Algorithms: gamma by Marsaglia and Tsang with Boost's correction below shape 1, Poisson by
  * inversion below mean 10 and Hormann's transformed rejection above, beta and Dirichlet through
  * gamma, categorical by binary search on the cumulative masses, and [[alias]] by Vose's tables.
  * **Example.**
  * Each generator is a `Rand` program, so they compose and the result is a function of the seed:
  *
  * ```scala
  * //{
  * import cats.Monad
  * import cats.syntax.all.*
  * //}
  * val counts: Rand[List[Int]] = Monad[Rand].replicateA(5, Sampling.poisson[Rand](3.5))
  *
  * /** A gamma-Poisson draw written as what it is: a Poisson whose rate is drawn from a Gamma. */
  * val gammaPoisson: Rand[Int] =
  *   for
  *     rate <- Sampling.gamma[Rand](2.0).map(_ / 0.5)
  *     count <- Sampling.poisson[Rand](rate)
  *   yield count
  *
  * val probability: Rand[Double] = Sampling.beta[Rand](2.0, 5.0)
  *
  * // `categorical` takes *cumulative* masses and returns a zero-based index.
  * val shelf: Rand[Int] = Sampling.categorical[Rand](IArray(0.5, 0.8, 1.0))
  *
  * val drawn: (List[Int], Int, Double, Int) =
  *   (counts, gammaPoisson, probability, shelf).tupled.runA(Seed(7L)).value
  * ```
  */
object Sampling:

  /** Standard gamma, shape > 0 and unit rate. The caller divides by the rate. */
  def gamma[F[_]: Monad: Rng](shape: Double): F[Double] =
    if shape.isNaN || shape <= 0.0 then Monad[F].pure(Double.NaN)
    else if shape < 1.0 then
      // Boost: draw with shape + 1 and shrink by u^(1/shape). Drawing at shape < 1 directly would
      // need a different squeeze, and this costs one uniform.
      for
        raised <- marsagliaTsang[F](shape + 1.0)
        u <- Rng[F].uniform
      yield raised * math.pow(u, 1.0 / shape)
    else marsagliaTsang[F](shape)

  def beta[F[_]: Monad: Rng](a: Double, b: Double): F[Double] =
    if a.isNaN || b.isNaN || a <= 0.0 || b <= 0.0 then Monad[F].pure(Double.NaN)
    else
      for
        x <- gamma[F](a)
        y <- gamma[F](b)
      yield if x + y == 0.0 then 0.0 else x / (x + y)

  def poisson[F[_]: Monad: Rng](mean: Double): F[Int] =
    if mean.isNaN || mean < 0.0 then Monad[F].pure(-1)
    else if mean == 0.0 then Monad[F].pure(0)
    else if mean < PtrsThreshold then inversion[F](mean)
    else transformedRejection[F](mean)

  def dirichlet[F[_]: Monad: Rng](alpha: IArray[Double]): F[IArray[Double]] =
    alpha.toList
      .traverse(a => gamma[F](a))
      .map { gammas =>
        val total = gammas.sum
        IArray.unsafeFromArray(gammas.map(_ / total).toArray)
      }

  /** Index drawn from cumulative probabilities, by binary search. */
  def categorical[F[_]: Functor: Rng](cumulative: IArray[Double]): F[Int] =
    Rng[F].uniform.map(u => search(cumulative, u, 0, cumulative.length - 1))

  /** Index drawn from a prepared alias table, in constant time, consuming two uniforms: one for
    * the column and one for the coin. Two rather than one so that the column and the coin are
    * independent, and so that the batch path can reproduce the sequence exactly.
    */
  def alias[F[_]: Monad: Rng](table: AliasTable): F[Int] =
    for
      column <- Rng[F].uniform
      coin <- Rng[F].uniform
    yield
      val n = table.probability.length
      val index = math.min((column * n).toInt, n - 1)
      if coin < table.probability(index) then index else table.alias(index)

  // --- internals ----------------------------------------------------------------------------

  /** Hormann's threshold: inversion is cheaper below it, rejection above. */
  private val PtrsThreshold: Double = 10.0

  private def marsagliaTsang[F[_]: Monad: Rng](shape: Double): F[Double] =
    val d = shape - 1.0 / 3.0
    val c = 1.0 / (3.0 * math.sqrt(d))
    Monad[F].tailRecM(()) { _ =>
      for
        z <- Rng[F].normal
        u <- Rng[F].uniform
      yield
        val v = 1.0 + c * z
        if v <= 0.0 then Left(())
        else
          val cube = v * v * v
          val accepted = math.log(u) < 0.5 * z * z + d - d * cube + d * math.log(cube)
          if accepted then Right(d * cube) else Left(())
    }

  /** Cumulative inversion: walk the mass function until it passes a uniform. */
  private def inversion[F[_]: Monad: Rng](mean: Double): F[Int] =
    Rng[F].uniform.map { u =>
      @annotation.tailrec
      def walk(k: Int, mass: Double, cumulative: Double): Int =
        if u <= cumulative || k > 2000 then k
        else
          val nextK = k + 1
          val nextMass = mass * mean / nextK
          walk(nextK, nextMass, cumulative + nextMass)
      val start = math.exp(-mean)
      walk(0, start, start)
    }

  /** Hormann's PTRS: transformed rejection with a squeeze, as used by Stan and NumPy. */
  private def transformedRejection[F[_]: Monad: Rng](mean: Double): F[Int] =
    val b = 0.931 + 2.53 * math.sqrt(mean)
    val a = -0.059 + 0.02483 * b
    val inverseAlpha = 1.1239 + 1.1328 / (b - 3.4)
    val boundary = 0.9277 - 3.6224 / (b - 2.0)
    val logMean = math.log(mean)
    Monad[F].tailRecM(()) { _ =>
      for
        first <- Rng[F].uniform
        second <- Rng[F].uniform
      yield
        val u = first - 0.5
        val us = 0.5 - math.abs(u)
        val k = math.floor((2.0 * a / us + b) * u + mean + 0.43)
        if us >= 0.07 && second <= boundary then Right(k.toInt)
        else if k < 0.0 || (us < 0.013 && second > us) then Left(())
        else
          val accepted = math.log(second * inverseAlpha / (a / (us * us) + b)) <=
            k * logMean - mean - Special.lgamma(k + 1.0)
          if accepted then Right(k.toInt) else Left(())
    }

  @annotation.tailrec
  private def search(cumulative: IArray[Double], u: Double, low: Int, high: Int): Int =
    if low >= high then math.min(low, cumulative.length - 1)
    else
      val middle = low + (high - low) / 2
      if u <= cumulative(middle) then search(cumulative, u, low, middle)
      else search(cumulative, u, middle + 1, high)
