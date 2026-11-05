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

/** The count laws parameterised by log-odds.
  *
  * They never pass through the probability: `logInvLogit(eta)` and `log1mInvLogit(eta)` stay exact
  * at eta = 25, where `log(invLogit(25))` has nothing left. SciPy gets this wrong, and the
  * reference here is mpmath.
  */
final case class BernoulliLogit private (eta: Double) extends DiscreteLaw:
  def logMass(k: Int): Double = k match
    case 1 => LogExp.logInvLogit(eta)
    case 0 => LogExp.log1mInvLogit(eta)
    case _ => Double.NegativeInfinity
  def mean: Double = LogExp.invLogit(eta)

  /** Through the logs, so that a log odds of 25 gives 1.4e-11 and not zero. */
  def variance: Double = math.exp(LogExp.logInvLogit(eta) + LogExp.log1mInvLogit(eta))
  def sample[F[_]: Monad: Rng]: F[Int] =
    Rng[F].uniform.map(u => if u < LogExp.invLogit(eta) then 1 else 0)

object BernoulliLogit:
  def of(eta: Double): Either[BayesError.InvalidArgument, BernoulliLogit] =
    Laws.finite("BernoulliLogit.of", "eta", eta).map(_ => BernoulliLogit(eta))

final case class BinomialLogit private (trials: Int, eta: Double) extends DiscreteLaw:
  def logMass(k: Int): Double =
    if k < 0 || k > trials then Double.NegativeInfinity
    else
      Laws.logBinomialCoefficient(trials.toDouble, k.toDouble) +
        k * LogExp.logInvLogit(eta) + (trials - k) * LogExp.log1mInvLogit(eta)
  def mean: Double = trials * LogExp.invLogit(eta)
  def variance: Double =
    trials * math.exp(LogExp.logInvLogit(eta) + LogExp.log1mInvLogit(eta))
  def sample[F[_]: Monad: Rng]: F[Int] =
    Counts.binomialByInversion[F](trials, LogExp.invLogit(eta))

object BinomialLogit:
  def of(trials: Int, eta: Double): Either[BayesError.InvalidArgument, BinomialLogit] =
    if trials < 0 then
      Left(BayesError.InvalidArgument("BinomialLogit.of", "trials", trials.toDouble, "trials >= 0"))
    else Laws.finite("BinomialLogit.of", "eta", eta).map(_ => BinomialLogit(trials, eta))

/** A binomial whose probability is itself Beta: the over-dispersed count.
  *
  * Written with `lbeta`, which is where the precision of a large concentration lives, exactly as
  * for the negative binomial.
  */
final case class BetaBinomial private (trials: Int, alpha: Double, beta: Double)
    extends DiscreteLaw:
  def logMass(k: Int): Double =
    if k < 0 || k > trials then Double.NegativeInfinity
    else
      Laws.logBinomialCoefficient(trials.toDouble, k.toDouble) +
        Special.lbeta(k + alpha, trials - k + beta) - Special.lbeta(alpha, beta)
  def mean: Double = trials * alpha / (alpha + beta)
  def variance: Double =
    val total = alpha + beta
    trials * alpha * beta * (total + trials) / (total * total * (total + 1.0))
  def sample[F[_]: Monad: Rng]: F[Int] =
    Sampling.beta[F](alpha, beta).flatMap(p => Counts.binomialByInversion[F](trials, p))

object BetaBinomial:
  def of(
      trials: Int,
      alpha: Double,
      beta: Double
  ): Either[BayesError.InvalidArgument, BetaBinomial] =
    if trials < 0 then
      Left(BayesError.InvalidArgument("BetaBinomial.of", "trials", trials.toDouble, "trials >= 0"))
    else
      for
        _ <- Laws.positiveFinite("BetaBinomial.of", "alpha", alpha)
        _ <- Laws.positiveFinite("BetaBinomial.of", "beta", beta)
      yield BetaBinomial(trials, alpha, beta)
