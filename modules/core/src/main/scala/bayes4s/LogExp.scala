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

/** Numerically careful combinations of logarithms and exponentials, total on all of `Double`.
  *
  * Every function here exists because its textbook form breaks somewhere a sampler actually goes.
  * `1 / (1 + exp(-x))` overflows at x = -800; `log(1 - exp(x))` loses all its digits as x
  * approaches 0; `log(sum(exp(x)))` overflows long before the answer does.
  */
object LogExp:

  /** log(sum(exp(xs))) without overflow. An empty vector, or one that is all `-Inf`, is `-Inf`. */
  def logSumExp(xs: IArray[Double]): Double =
    val largest = xs.foldLeft(Double.NegativeInfinity)((acc, x) => math.max(acc, x))
    if largest.isNaN then Double.NaN
    else if largest == Double.NegativeInfinity then Double.NegativeInfinity
    else if largest == Double.PositiveInfinity then Double.PositiveInfinity
    else largest + math.log(xs.foldLeft(0.0)((acc, x) => acc + math.exp(x - largest)))

  def logSumExp(a: Double, b: Double): Double =
    if a.isNaN || b.isNaN then Double.NaN
    else
      val largest = math.max(a, b)
      val smallest = math.min(a, b)
      if largest == Double.NegativeInfinity then Double.NegativeInfinity
      else if largest == Double.PositiveInfinity then Double.PositiveInfinity
      else largest + log1pExp(smallest - largest)

  /** log(1 + exp(x)), the softplus. Finite and correct at x = +/-800. */
  def log1pExp(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x > 0.0 then x + math.log1p(math.exp(-x))
    else math.log1p(math.exp(x))

  /** log(1 - exp(x)) for x < 0, by Machler's two branches; `NaN` for x > 0, `-Inf` at 0. */
  def log1mExp(x: Double): Double =
    if x.isNaN || x > 0.0 then Double.NaN
    else if x == 0.0 then Double.NegativeInfinity
    else if x > -Ln2 then math.log(-math.expm1(x))
    else math.log1p(-math.exp(x))

  /** log(exp(a) - exp(b)) for a >= b; `-Inf` when they are equal, `NaN` when a < b. */
  def logDiffExp(a: Double, b: Double): Double =
    if a.isNaN || b.isNaN then Double.NaN
    else if a < b then Double.NaN
    else if a == b then Double.NegativeInfinity
    else if a == Double.PositiveInfinity then Double.PositiveInfinity
    else a + log1mExp(b - a)

  /** 1 / (1 + exp(-x)), computed on whichever side cannot overflow. Stan's `inv_logit`. */
  def invLogit(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x >= 0.0 then 1.0 / (1.0 + math.exp(-x))
    else
      val e = math.exp(x)
      e / (1.0 + e)

  /** log of the inverse logit, accurate in the far left tail where `log(invLogit(x))` underflows.
    * Stan's `log_inv_logit`.
    */
  def logInvLogit(x: Double): Double = if x.isNaN then Double.NaN else -log1pExp(-x)

  /** log(1 - invLogit(x)), Stan's `log1m_inv_logit`.
    *
    * The complement of [[logInvLogit]], and the other half of what a Bernoulli on the log-odds
    * scale needs: it must stay exact at eta = 25, where going through the probability leaves
    * nothing.
    */
  def log1mInvLogit(x: Double): Double = if x.isNaN then Double.NaN else -log1pExp(x)

  /** The log odds. `NaN` outside [0, 1], infinite at the ends. */
  def logit(p: Double): Double =
    if p.isNaN || p < 0.0 || p > 1.0 then Double.NaN
    else if p == 0.0 then Double.NegativeInfinity
    else if p == 1.0 then Double.PositiveInfinity
    else math.log(p) - math.log1p(-p)

  // --- deprecated aliases, kept until v0.1 -------------------------------------------------
  // Renamed to the Stan names the naming rule requires. They forward, so they cannot drift.

  @deprecated("renamed to invLogit, the Stan name (inv_logit)", "0.1")
  def logistic(x: Double): Double = invLogit(x)

  @deprecated("renamed to logInvLogit, the Stan name (log_inv_logit)", "0.1")
  def logSigmoid(x: Double): Double = logInvLogit(x)

  private val Ln2: Double = math.log(2.0)
