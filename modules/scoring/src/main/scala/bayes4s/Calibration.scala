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

/** Whether a forecast's shape is right, as opposed to how close it landed.
  *
  * A score says how good a forecast was; these say **how** it was wrong, which is what tells you
  * what to change. The probability integral transform is the centre of it: where the observation
  * fell inside the forecast distribution. Over many periods those values must be uniform, and the
  * way they are not is the diagnosis — piled at the ends means too narrow, piled in the middle
  * means too wide, piled at one end means shifted.
  * **Example.** A good average score can hide a model that is systematically too wide, too narrow or
  * shifted. These read the shape of the wrongness rather than its size:
  *
  * ```scala
  * val forecast = IArray(1.0, 2.0, 3.0, 4.0)
  *
  * // Where the observation fell inside the forecast. Over many periods it has to be uniform; a tie
  * // counts as half, which is what keeps a count forecast off the bin edges.
  * val middle: Double = Calibration.pit(forecast, 2.5)
  * val above: Double = Calibration.pit(forecast, 5.0)
  *
  * // The same thing in one number, from -1 to +1. Positive means the forecast sat above what
  * // happened.
  * val tooHigh: Double = Calibration.bias(forecast, 0.0)
  *
  * // The width of an interval plus a penalty for missing it, scaled by 2/alpha: sharpness, subject
  * // to being right.
  * val inside: Double = Calibration.intervalScore(10.0, 20.0, 15.0, 0.1)
  * val missed: Double = Calibration.intervalScore(10.0, 20.0, 8.0, 0.1)
  * ```
  */
object Calibration:

  /** The probability integral transform of an ensemble forecast.
    *
    * `(#{x < y} + 0.5 #{x = y}) / n`: a tie counts as half, which is the expectation of the
    * randomised transform. Without that, a forecast with repeated values — any count forecast —
    * piles its transforms on bin edges and looks miscalibrated when it is not.
    */
  def pit(forecast: IArray[Double], observation: Double): Double =
    val n = forecast.length
    if n == 0 || observation.isNaN then Double.NaN
    else
      val below = (0 until n).count(i => forecast(i) < observation)
      val equal = (0 until n).count(i => forecast(i) == observation)
      (below + 0.5 * equal) / n

  /** `1 - 2 pit`: +1 for a forecast entirely above what happened, -1 for entirely below.
    *
    * The convention is scoringutils', and the sign is the thing to be careful about: positive
    * means the forecast was too high, because the observation fell below it.
    */
  def bias(forecast: IArray[Double], observation: Double): Double =
    1.0 - 2.0 * pit(forecast, observation)

  /** Winkler's interval score for a central `1 - alpha` interval.
    *
    * `(u - l) + (2/alpha)(l - y)` when the observation falls below, `(u - l) + (2/alpha)(y - u)`
    * when above, and the width alone when inside. It rewards sharpness subject to being right, and
    * it equals `2/alpha` times the sum of the pinball losses at `alpha/2` and `1 - alpha/2` —
    * which is the bridge between reporting intervals and scoring quantiles, and is asserted in the
    * suite against a hand-written pinball loss.
    */
  def intervalScore(lower: Double, upper: Double, observation: Double, alpha: Double): Double =
    if !(alpha > 0.0 && alpha < 1.0) || upper < lower || lower.isNaN || upper.isNaN ||
      observation.isNaN
    then Double.NaN
    else
      val width = upper - lower
      val belowPenalty = if observation < lower then (2.0 / alpha) * (lower - observation) else 0.0
      val abovePenalty = if observation > upper then (2.0 / alpha) * (observation - upper) else 0.0
      width + belowPenalty + abovePenalty

  /** The share of observations inside their own intervals, boundaries counting as inside. */
  def coverage(
      lower: IArray[Double],
      upper: IArray[Double],
      observations: IArray[Double]
  ): Double =
    val n = observations.length
    if n == 0 || lower.length != n || upper.length != n then Double.NaN
    else
      val inside =
        (0 until n).count(i => observations(i) >= lower(i) && observations(i) <= upper(i))
      inside.toDouble / n

  /** Counts of transforms per bin, over `[0, 1]`, with 1 in the last bin rather than past it. */
  def pitHistogram(pits: IArray[Double], bins: Int): IArray[Int] =
    if bins < 1 then IArray.empty[Int]
    else
      val counts = (0 until pits.length).foldLeft(Vector.fill(bins)(0)) { (collected, i) =>
        val p = pits(i)
        if p.isNaN || p < 0.0 || p > 1.0 then collected
        else
          val bin = math.min((p * bins).toInt, bins - 1)
          collected.updated(bin, collected(bin) + 1)
      }
      IArray.from(counts)

  /** The chi-squared statistic of the transforms' uniformity, with `bins - 1` degrees of freedom.
    *
    * `NaN` when there are no bins or nothing to bin: a statistic computed from nothing would be a
    * number nobody could read.
    */
  def pitUniformity(pits: IArray[Double], bins: Int): Double =
    if bins < 1 then Double.NaN
    else
      val counts = pitHistogram(pits, bins)
      val total = (0 until counts.length).foldLeft(0)((sum, b) => sum + counts(b))
      if total == 0 then Double.NaN
      else
        val expected = total.toDouble / bins
        (0 until bins).foldLeft(0.0) { (sum, b) =>
          val gap = counts(b) - expected
          sum + gap * gap / expected
        }
