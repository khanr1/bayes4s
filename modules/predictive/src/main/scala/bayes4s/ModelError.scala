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
  * `sigmaError` is meant to be calibrated on dates already past, over a grid, by scoring each
  * candidate against what actually happened. That needs a backtest, which arrives with the scoring
  * module, so this carries the term and not its calibration (docs/design.md).
  */
object ModelError:

  /** One error term. `NaN` for a negative spread, `0` for none at all. */
  def draw[F[_]: Functor: Rng](sigmaError: Double): F[Double] =
    if sigmaError.isNaN || sigmaError < 0.0 then Rng[F].normal.map(_ => Double.NaN)
    else if sigmaError == 0.0 then Rng[F].normal.map(_ => 0.0)
    else Rng[F].normal.map(_ * sigmaError)

  /** A rate under an error term: `rate * exp(eta)`. */
  def applied(rate: Double, eta: Double): Double = rate * math.exp(eta)
