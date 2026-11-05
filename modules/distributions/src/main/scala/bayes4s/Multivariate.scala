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

/** A law on category indices 0 to n - 1. It has no natural mean, so it is not a [[DiscreteLaw]];
  * its sampling is checked against its own mass function by a chi-squared test.
  */
final case class Categorical private (probabilities: IArray[Double]):
  def logMass(k: Int): Double =
    if k < 0 || k >= probabilities.length then Double.NegativeInfinity
    else math.log(probabilities(k))
  def sample[F[_]: Monad: Rng]: F[Int] = Sampling.categorical[F](cumulative)
  private val cumulative: IArray[Double] =
    IArray.unsafeFromArray(probabilities.toList.scanLeft(0.0)(_ + _).tail.toArray)

object Categorical:
  def of(probabilities: IArray[Double]): Either[BayesError, Categorical] =
    val n = probabilities.length
    if n == 0 then
      Left(BayesError.InvalidArgument("Categorical.of", "probabilities", 0.0, "at least one"))
    else
      probabilities.toList.zipWithIndex
        .collectFirst {
          case (p, i) if p.isNaN || p < 0.0 || p > 1.0 =>
            BayesError.InvalidArgument("Categorical.of", s"probabilities($i)", p, "in [0, 1]")
        } match
        case Some(error) => Left(error)
        case None =>
          val total = probabilities.toList.sum
          if math.abs(total - 1.0) > 1e-9 then
            Left(
              BayesError.InvalidArgument("Categorical.of", "probabilities", total, "sum to one")
            )
          else Right(Categorical(probabilities))

/** Cumulative logit: `P(y <= j) = logistic(cuts(j) - eta)`, categories 1 to cuts.length + 1, as in
  * Stan's `ordered_logistic`.
  *
  * The middle categories are differences of two logistics, taken in the log domain so that a
  * category with a mass of 1e-300 keeps its digits instead of becoming zero.
  */
final case class OrderedLogistic private (eta: Double, cuts: IArray[Double]):
  def logMass(k: Int): Double =
    val last = cuts.length + 1
    if k < 1 || k > last then Double.NegativeInfinity
    else if k == 1 then LogExp.logInvLogit(cuts(0) - eta)
    else if k == last then LogExp.logInvLogit(eta - cuts(cuts.length - 1))
    else
      LogExp.logDiffExp(
        LogExp.logInvLogit(eta - cuts(k - 2)),
        LogExp.logInvLogit(eta - cuts(k - 1))
      )
  def sample[F[_]: Monad: Rng]: F[Int] = Sampling.categorical[F](cumulative).map(_ + 1)
  private val cumulative: IArray[Double] =
    val masses = (1 to cuts.length + 1).map(k => math.exp(logMass(k)))
    IArray.unsafeFromArray(masses.scanLeft(0.0)(_ + _).tail.toArray)

object OrderedLogistic:
  /** Cuts must be finite and strictly increasing. */
  def of(eta: Double, cuts: IArray[Double]): Either[BayesError, OrderedLogistic] =
    if cuts.length == 0 then
      Left(BayesError.InvalidArgument("OrderedLogistic.of", "cuts", 0.0, "at least one cut"))
    else if eta.isNaN || eta.isInfinite then
      Left(BayesError.InvalidArgument("OrderedLogistic.of", "eta", eta, "eta must be finite"))
    else
      cuts.toList.zipWithIndex
        .collectFirst {
          case (c, i) if c.isNaN || c.isInfinite =>
            BayesError.InvalidArgument("OrderedLogistic.of", s"cuts($i)", c, "finite")
          case (c, i) if i > 0 && c <= cuts(i - 1) =>
            BayesError.InvalidArgument("OrderedLogistic.of", s"cuts($i)", c, "strictly increasing")
        } match
        case Some(error) => Left(error)
        case None => Right(OrderedLogistic(eta, cuts))

final case class Dirichlet private (alpha: IArray[Double]):
  /** `-Inf` unless x is on the simplex: the right length, strictly positive, summing to one. */
  def logPdf(x: IArray[Double]): Double =
    if x.length != alpha.length then Double.NegativeInfinity
    else if x.toList.exists(v => v.isNaN || v <= 0.0) then Double.NegativeInfinity
    else if math.abs(x.toList.sum - 1.0) > 1e-9 then Double.NegativeInfinity
    else
      val total = alpha.toList.sum
      val shaped = (0 until alpha.length).map(i => (alpha(i) - 1.0) * math.log(x(i))).sum
      val normaliser = Special.lgamma(total) - alpha.toList.map(Special.lgamma).sum
      shaped + normaliser
  def mean: IArray[Double] =
    val total = alpha.toList.sum
    alpha.map(_ / total)
  def sample[F[_]: Monad: Rng]: F[IArray[Double]] = Sampling.dirichlet[F](alpha)

object Dirichlet:
  def of(alpha: IArray[Double]): Either[BayesError, Dirichlet] =
    if alpha.length < 2 then
      Left(
        BayesError.InvalidArgument(
          "Dirichlet.of",
          "alpha",
          alpha.length.toDouble,
          "at least two components"
        )
      )
    else
      alpha.toList.zipWithIndex
        .collectFirst {
          case (a, i) if a.isNaN || a.isInfinite || !(a > 0.0) =>
            BayesError.InvalidArgument("Dirichlet.of", s"alpha($i)", a, "> 0 and finite")
        } match
        case Some(error) => Left(error)
        case None => Right(Dirichlet(alpha))
