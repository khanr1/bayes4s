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
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** An AR(1) state projected into the future.
  *
  * The recursion is `z_{t+1} = phi z_t + tau e`, with `e` standard Gaussian: the state the fit
  * ended at is carried forward, decaying towards zero at rate `phi` while the noise accumulates.
  * At horizon `h` the state has mean `phi^h z_last` and variance `tau² (1 - phi^2h)/(1 - phi²)`,
  * which is what the suite checks it against.
  *
  * Total, as every numeric function here is: `|phi| > 1` is not refused, it explodes, which is
  * what that recursion does; a `NaN` parameter gives a path of `NaN`.
  */
object Ar1:

  def project(phi: Double, tau: Double, zLast: Double, horizon: Int, seed: Seed): IArray[Double] =
    if horizon <= 0 then IArray.empty[Double]
    else
      val program = Monad[Rand].replicateA(horizon, Rng[Rand].normal)
      val noise = program.runA(seed).value
      val path = noise.foldLeft((Vector.empty[Double], zLast)) { case ((collected, state), e) =>
        val next = phi * state + tau * e
        (collected :+ next, next)
      }
      IArray.from(path._1)

/** A trend applied to an amount: `amount * exp(kappa * years)`.
  *
  * `kappa` is a log-scale slope per year, one value per sample from the posterior, and `years` is
  * how far into the future the amount falls. On the log scale the trend is linear, which is why it
  * compounds: two years is one year squared.
  */
object TrendScaling:

  def scaled(amount: Double, kappa: Double, years: Double): Double =
    amount * math.exp(kappa * years)

  /** Whole months, as years. A month is a twelfth; nothing here counts days. */
  def yearsBetween(from: YearMonth, to: YearMonth): Double =
    ChronoUnit.MONTHS.between(from, to).toDouble / 12.0

  /** The draw paired with future `index`, **cyclically**.
    *
    * There are usually fewer samples from the posterior than simulated futures, and the pairing
    * has to be stated because the alternatives change the answer: clamping every extra future to
    * the last sample would give that sample too much weight, and drawing a fresh one would average
    * away the very uncertainty the samples carry. Cycling gives each sample as near to an equal
    * share of the futures as the two counts allow.
    *
    * `NaN` when there is nothing to pair with, or for a future that does not exist.
    */
  def at(draws: IArray[Double], index: Int): Double =
    if draws.length == 0 || index < 0 then Double.NaN else draws(index % draws.length)
