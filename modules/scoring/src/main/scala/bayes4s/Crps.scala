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

/** The continuous ranked probability score: how far a whole predictive distribution is from the
  * one number that happened.
  *
  * `CRPS = E|X - y| - 0.5 E|X - X'|`, with `X` and `X'` independent draws from the forecast. It is
  * in the units of the observation, it is zero only for a forecast certain and right, and it is
  * **proper**: no forecaster improves it by reporting anything but their honest belief. A forecast
  * that is too wide pays for the spread; one that is confident and wrong pays much more.
  *
  * Two implementations of the one function. [[energy]] is the definition and costs `O(n²)`;
  * [[ensemble]] sorts and costs `O(n log n)`, which is what anything with a thousand samples per
  * period must use. They are the same function, and a suite says so rather than a comment.
  *
  * The estimator of the second term is `1/n²`, which is properscoring's and is what the reference
  * values were produced with. The `1/(n(n-1))` version is the unbiased one and is a different
  * number; mixing them is a quiet way to disagree with every published score.
  * **Example.** Three forms of the same score. Lower is better, and it is in the units of the thing
  * being forecast:
  *
  * ```scala
  * // A forecast as samples: the usual case, and `ensemble` sorts rather than comparing every pair.
  * val forecast = IArray.tabulate(1000)(i => 10.0 + 2.0 * Special.invPhi((i + 0.5) / 1000.0))
  * val good: Double = Crps.ensemble(forecast, 10.3)
  * val badlyPlaced: Double = Crps.ensemble(forecast, 18.0)
  *
  * // `energy` is the definition, and costs O(n^2). The two agree.
  * val sameAnswer: Double = Crps.energy(IArray(1.0, 1.0, 1.0, 4.0), 1.0)
  *
  * // The closed form for a Gaussian forecast, which knows nothing about samples — the useful thing
  * // to check a sampler against.
  * val exact: Double = Crps.gaussian(mu = 10.0, sigma = 2.0, observation = 10.3)
  * ```
  */
object Crps:

  /** The sorted form, `O(n log n)`.
    *
    * `NaN` for an empty forecast, for a `NaN` anywhere, and for a non-finite sample: both terms of
    * the score then diverge and their difference is indeterminate rather than infinite.
    *
    * With the sample sorted, `sum_i sum_j |x_i - x_j|` is `2 sum_i (2i - n - 1) x_(i)` over
    * one-based `i`, because each gap is counted by how many pairs straddle it. That identity is
    * the whole trick, and it needs no special case for ties.
    */
  def ensemble(forecast: IArray[Double], observation: Double): Double =
    val n = forecast.length
    if n == 0 || observation.isNaN then Double.NaN
    else
      val sorted = IArray.from(forecast.toList.sorted)
      if sorted.exists(_.isNaN) then Double.NaN
      else
        val distance =
          (0 until n).foldLeft(0.0)((sum, i) => sum + math.abs(sorted(i) - observation)) / n
        val spread =
          (0 until n).foldLeft(0.0)((sum, i) => sum + (2.0 * (i + 1) - n - 1) * sorted(i)) /
            (n.toDouble * n.toDouble)
        distance - spread

  /** The definition, `O(n²)`: `E|X - y| - 0.5 E|X - X'|` with the `1/n²` estimator. */
  def energy(forecast: IArray[Double], observation: Double): Double =
    val n = forecast.length
    if n == 0 || observation.isNaN then Double.NaN
    else
      val distance =
        (0 until n).foldLeft(0.0)((sum, i) => sum + math.abs(forecast(i) - observation)) / n
      val pairs = (0 until n).foldLeft(0.0) { (sum, i) =>
        sum + (0 until n).foldLeft(0.0)((inner, j) => inner + math.abs(forecast(i) - forecast(j)))
      }
      distance - pairs / (2.0 * n.toDouble * n.toDouble)

  /** The closed form for a Gaussian forecast.
    *
    * `sigma [ z (2 Phi(z) - 1) + 2 phi(z) - 1/sqrt(pi) ]` with `z = (y - mu)/sigma`. A forecast of
    * no width at all is a point forecast, and scores the distance.
    */
  def gaussian(mu: Double, sigma: Double, observation: Double): Double =
    if sigma < 0.0 || sigma.isNaN || mu.isNaN || observation.isNaN then Double.NaN
    else if sigma == 0.0 then math.abs(observation - mu)
    else
      val z = (observation - mu) / sigma
      val density = math.exp(-0.5 * z * z) / math.sqrt(2.0 * math.Pi)
      sigma * (z * (2.0 * Special.phi(z) - 1.0) + 2.0 * density - 1.0 / math.sqrt(math.Pi))
