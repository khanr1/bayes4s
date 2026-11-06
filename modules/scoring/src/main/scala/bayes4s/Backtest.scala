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

/** What one date's forecast scored. */
final case class Scored[D](
    date: D,
    crps: Double,
    pit: Double,
    bias: Double,
    intervalScore: Double,
    lower: Double,
    upper: Double,
    covered: Boolean
)

/** A backtest's scores, and the aggregates worth quoting.
  *
  * Every aggregate is `NaN` for an empty backtest rather than zero: nothing was measured, and a
  * zero would read as a perfect score.
  */
final case class BacktestResult[D](scored: List[Scored[D]], alpha: Double):

  def dates: Int = scored.length

  def meanCrps: Double = meanOf(_.crps)
  def meanIntervalScore: Double = meanOf(_.intervalScore)
  def meanBias: Double = meanOf(_.bias)

  /** The share of dates whose observation fell inside its own interval. */
  def coverage: Double =
    if scored.isEmpty then Double.NaN else scored.count(_.covered).toDouble / scored.length

  /** How far the coverage is from the `1 - alpha` it was aiming at. */
  def coverageGap: Double = coverage - (1.0 - alpha)

  def pitUniformity(bins: Int): Double =
    Calibration.pitUniformity(IArray.from(scored.map(_.pit)), bins)

  /** The uncertainty of a mean score, by **moving-block** bootstrap over the dates.
    *
    * Scores on neighbouring dates are correlated — a model that is wrong in March is usually wrong
    * in April — so resampling dates one at a time understates the uncertainty of their mean,
    * sometimes by a factor of several. Resampling contiguous blocks keeps the local correlation
    * inside each block, which is what the standard error is supposed to include.
    *
    * Returns one mean per replication, so the caller can take a standard deviation, a quantile, or
    * look at the whole thing.
    */
  def blockBootstrap[F[_]: Monad: Rng](
      score: Scored[D] => Double,
      blockSize: Int,
      replications: Int
  ): F[IArray[Double]] =
    val n = scored.length
    val values = IArray.from(scored.map(score))
    if n == 0 || blockSize < 1 || replications < 1 then Monad[F].pure(IArray.empty[Double])
    else
      val length = math.min(blockSize, n)
      val blocks = math.ceil(n.toDouble / length).toInt
      (0 until replications).toList
        .traverse { _ =>
          Monad[F]
            .replicateA(blocks, Rng[F].uniform)
            .map { starts =>
              // Each block starts anywhere a whole block still fits, and the concatenation is cut
              // back to the original length so that every replicate has the same weight.
              val positions = starts.map(u => math.min((u * (n - length + 1)).toInt, n - length))
              val drawn = positions.flatMap(start => (0 until length).map(k => values(start + k)))
              val kept = drawn.take(n)
              kept.sum / kept.length
            }
        }
        .map(means => IArray.from(means))

  private def meanOf(f: Scored[D] => Double): Double =
    if scored.isEmpty then Double.NaN
    else scored.foldLeft(0.0)((sum, s) => sum + f(s)) / scored.length

/** Scoring a sequence of forecasts against what happened.
  *
  * Pure, and deliberately only half of a backtest: producing the forecasts — refitting the model
  * at each origin, so that no fit has seen its own target — belongs to whoever owns the model, and
  * needs the sampler this module does not depend on. What is here is the half that must agree with
  * everyone else's arithmetic.
  */
object Backtest:

  /** One scored row per date, with the interval taken from the forecast's own quantiles.
    *
    * The central `1 - alpha` interval is the type-7 quantiles at `alpha/2` and `1 - alpha/2` of
    * the ensemble, which is the same quantile this library uses everywhere else.
    */
  def run[D](
      cases: List[(D, IArray[Double], Double)],
      alpha: Double
  ): BacktestResult[D] =
    val scored = cases.map { (date, forecast, observation) =>
      val lower = Quantile.quantile(forecast, alpha / 2.0)
      val upper = Quantile.quantile(forecast, 1.0 - alpha / 2.0)
      Scored(
        date,
        Crps.ensemble(forecast, observation),
        Calibration.pit(forecast, observation),
        Calibration.bias(forecast, observation),
        Calibration.intervalScore(lower, upper, observation, alpha),
        lower,
        upper,
        observation >= lower && observation <= upper
      )
    }
    BacktestResult(scored, alpha)
