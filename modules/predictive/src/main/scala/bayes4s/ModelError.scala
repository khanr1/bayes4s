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

/** The model's own error: `eta ~ Gaussian(0, sigmaError)` on the log rate.
  *
  * Every component of a forecast explains something; what is left over is this. It is a term on
  * the **log** scale, so it multiplies rather than adds, which is what makes it the same size
  * relative to a small period as to a large one — and makes two of them one on their sum.
  *
  * The thing to know about it: multiplying a rate by `exp(eta)` leaves the median where it was and
  * raises the **mean** by `exp(sigmaError²/2)`, because that is what a lognormal does. At a sigma
  * of 0.3 every total comes out 4.6 % high. Whether to want that depends on what the forecast is
  * for — it is the honest mean of a multiplicative error — but it is never what someone expects by
  * accident, so this says it rather than leaving it to be discovered.
  *
  * `sigmaError` is calibrated on dates already past by [[ModelError.calibrate]], which widens every
  * past forecast by each candidate scale and keeps the one that scores best. The scoring rule is
  * the caller's, so this module needs no dependency on `scoring` (docs/design.md).
  * **Example.**
  * The error a model does not know it has: drawn **once per future**, applied to every amount in it,
  * and calibrated on months the fit did not see:
  *
  * ```scala
  * //{
  * import cats.syntax.all.*
  * //}
  * // One multiplier per future, on the log scale.
  * val perFuture: Rand[Double] = ModelError.draw[Rand](0.2)
  *
  * val widened: Rand[Double] = perFuture.map(error => ModelError.applied(100.0, error))
  *
  * // The scale that scores best on dates already past. The rule is the caller's, and lower is
  * // better — a scale calibrated under one rule is not calibrated under another.
  * val past: List[(IArray[Double], Double)] =
  *   List((IArray(95.0, 100.0, 105.0), 102.0), (IArray(98.0, 103.0, 108.0), 99.0))
  * val calibrated: Either[BayesError, ModelError.Calibration] =
  *   ModelError.calibrate(
  *     past,
  *     grid = IArray(0.0, 0.1, 0.2, 0.4),
  *     score = (ensemble, observed) => math.abs(ensemble.sum / ensemble.length - observed),
  *     seed = Seed(5L)
  *   )
  * ```
  */
object ModelError:

  /** One error term. `NaN` for a negative spread, `0` for none at all. */
  def draw[F[_]: Functor: Rng](sigmaError: Double): F[Double] =
    if sigmaError.isNaN || sigmaError < 0.0 then Rng[F].normal.map(_ => Double.NaN)
    else if sigmaError == 0.0 then Rng[F].normal.map(_ => 0.0)
    else Rng[F].normal.map(_ * sigmaError)

  /** A rate under an error term: `rate * exp(eta)`. */
  def applied(rate: Double, eta: Double): Double = rate * math.exp(eta)

  /** An ensemble widened by the error term: one `eta` per sample, not one per ensemble.
    *
    * Per sample, because the error is about what the model does not explain and a different
    * future would have a different amount of it. One `eta` for the whole ensemble would shift it
    * bodily and leave its width alone, which is the opposite of the intention.
    */
  def widen[F[_]: Monad: Rng](forecast: IArray[Double], sigmaError: Double): F[IArray[Double]] =
    if sigmaError.isNaN || sigmaError < 0.0 then
      Monad[F].pure(IArray.fill(forecast.length)(Double.NaN))
    else if sigmaError == 0.0 then Monad[F].pure(forecast)
    else
      Monad[F]
        .replicateA(forecast.length, Rng[F].normal)
        .map(noise =>
          IArray.tabulate(forecast.length)(i => applied(forecast(i), noise(i) * sigmaError))
        )

  /** What the grid search found: the best scale, and every candidate's score. */
  final case class Calibration(sigmaError: Double, scores: IArray[(Double, Double)])

  /** The error scale that scores best on dates already past.
    *
    * Each candidate widens every past forecast, the widened ensembles are scored against what
    * actually happened, and the candidate with the best mean score wins. The scoring rule is the
    * caller's — `Crps.ensemble` is the one to pass — because a scale calibrated under one rule is
    * not calibrated under another, and because `predictive` does not depend on `scoring`.
    *
    * **Lower is better**: the rule is read as a loss, which is what CRPS, the interval score and
    * the log score all are. A rule where higher is better must be negated by the caller.
    *
    * A function of its seed and nothing else, like `Ar1.project` and `Simulator.run`: the
    * randomness is interpreted by `State` inside, because a grid search is one answer rather than
    * a program the caller composes with.
    *
    * This is honest only on dates the model did not see when it was fitted. Calibrating an error
    * term on the data the fit already used will find that almost no error is needed, which is the
    * same mistake as scoring in-sample predictions (chapter 8).
    */
  def calibrate(
      forecasts: List[(IArray[Double], Double)],
      grid: IArray[Double],
      score: (IArray[Double], Double) => Double,
      seed: Seed
  ): Either[BayesError, Calibration] =
    if forecasts.isEmpty then
      Left(
        BayesError.InvalidArgument("ModelError.calibrate", "forecasts", 0.0, "at least one date")
      )
    else if grid.length == 0 then
      Left(
        BayesError.InvalidArgument("ModelError.calibrate", "grid", 0.0, "at least one candidate")
      )
    else
      val scored = IArray.tabulate(grid.length) { g =>
        val candidate = grid(g)
        // Each candidate is scored on the same widening seeds, so the comparison between them is
        // not a comparison of two draws of noise.
        val program = forecasts.zipWithIndex.traverse { case ((ensemble, observed), i) =>
          widen[Rand](ensemble, candidate).map(widened => score(widened, observed))
        }
        val perDate = program.runA(seed.fold(g.toLong)).value
        (candidate, perDate.sum / perDate.length)
      }
      val best = (0 until scored.length).foldLeft(0) { (bestSoFar, g) =>
        val (_, here) = scored(g)
        val (_, there) = scored(bestSoFar)
        if here.isNaN then bestSoFar else if there.isNaN || here < there then g else bestSoFar
      }
      Right(Calibration(scored(best)._1, scored))
