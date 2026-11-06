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

/** The generalised Pareto distribution, fitted to the tail of a sample.
  *
  * The one piece of machinery shared by Pareto-smoothed importance sampling and by power scaling:
  * both reweight a posterior, both need the heavy tail of the weights replaced by something with a
  * finite variance, and both report the fitted shape as a diagnostic — a `k` above 0.7 says the
  * reweighting cannot be trusted.
  *
  * Written from Vehtari, Simpson, Gelman, Yao and Gabry (2024), *Pareto smoothed importance
  * sampling*, as ArviZ implements it: the empirical-Bayes estimator with a weakly informative
  * prior on the shape, which is what makes the estimate usable from a couple of hundred tail
  * draws. The reference values of `multilevel.json` come from ArviZ, so its arithmetic is the
  * specification here.
  */
object Pareto:

  /** The fitted shape and scale. */
  final case class Fit(k: Double, sigma: Double)

  /** The number of draws to smooth, from `psis_tail_length`.
    *
    * `3 sqrt(n / r_eff)` once the sample is big enough to support it, and a fifth of it otherwise.
    * `both` caps the tail at half the sample and at five draws; `right` does not, which is the
    * asymmetry ArviZ has and this follows.
    */
  def tailLength(draws: Int, relativeEss: Double, bothTails: Boolean): Int =
    val wanted =
      if draws * relativeEss > 225.0 then math.floor(3.0 * math.sqrt(draws / relativeEss))
      else math.floor(draws / 5.0)
    if !bothTails then wanted.toInt
    else
      val half = draws / 2
      val capped = if wanted > half then half.toDouble else wanted
      math.max(capped, 5.0).toInt

  /** The empirical-Bayes fit of Vehtari et al. (2024) to a **sorted** array of excesses.
    *
    * The prior on the shape is a Gaussian centred at a half with weight `prior_k = 10`, and the
    * grid over the inverse scale is the one the paper's appendix gives. A first quartile no larger
    * than the minimum means the sample is nowhere near a generalised Pareto, and the fit says so
    * with a `NaN` rather than returning a number.
    */
  def fit(sortedExcesses: IArray[Double]): Fit =
    val n = sortedExcesses.length
    if n < 2 then Fit(Double.NaN, Double.NaN)
    else
      val priorScale = 3.0
      val priorShape = 10.0
      val grid = 30 + math.sqrt(n.toDouble).toInt
      val quartile = sortedExcesses(((n / 4.0 + 0.5) - 1).toInt)
      if quartile <= sortedExcesses(0) then Fit(Double.NaN, Double.NaN)
      else
        val largest = sortedExcesses(n - 1)
        val inverseScales = IArray.tabulate(grid) { j =>
          (1.0 - math.sqrt(grid / (j + 1.0 - 0.5))) / (priorScale * quartile) + 1.0 / largest
        }
        def shapeAt(b: Double): Double =
          (0 until n).foldLeft(0.0)((sum, i) => sum + math.log1p(-b * sortedExcesses(i))) / n
        val shapes = IArray.tabulate(grid)(j => shapeAt(inverseScales(j)))
        val profile = IArray.tabulate(grid) { j =>
          n * (math.log(-inverseScales(j) / shapes(j)) - shapes(j) - 1.0)
        }
        val total = LogExp.logSumExp(profile)
        val raw = IArray.tabulate(grid)(j => math.exp(profile(j) - total))
        // Weights below ten machine epsilons contribute nothing and can be dropped, which is what
        // keeps the posterior mean of the inverse scale from being dominated by rounding.
        val kept = (0 until grid).filter(j => raw(j) >= 10.0 * 2.220446049250313e-16)
        val mass = kept.foldLeft(0.0)((sum, j) => sum + raw(j))
        val posteriorInverseScale =
          kept.foldLeft(0.0)((sum, j) => sum + inverseScales(j) * raw(j) / mass)
        val shape = shapeAt(posteriorInverseScale)
        val sigma = -shape / posteriorInverseScale
        val k = (n * shape + priorShape * 0.5) / (n + priorShape)
        if k.isNaN then Fit(Double.PositiveInfinity, Double.NaN) else Fit(k, sigma)

  /** The quantile function of the generalised Pareto distribution. */
  def inverse(p: Double, fit: Fit, location: Double): Double =
    if !(fit.sigma > 0.0) then Double.NaN
    else if fit.k == 0.0 then location - fit.sigma * math.log1p(-p)
    else location + fit.sigma * math.expm1(-fit.k * math.log1p(-p)) / fit.k

  /** Log weights with their right tail replaced by the fitted Pareto quantiles, normalised.
    *
    * The shape of the fit comes back with them, because it is the diagnostic: the smoothing makes
    * the weights usable, and `k` says whether the answer they give should be believed.
    */
  def smoothRightTail(logWeights: IArray[Double], tailDraws: Int): (IArray[Double], Double) =
    val n = logWeights.length
    if n == 0 || tailDraws < 5 || tailDraws >= n then (normalised(logWeights), Double.NaN)
    else
      val largest =
        (0 until n).foldLeft(Double.NegativeInfinity)((m, i) => math.max(m, logWeights(i)))
      val shifted = IArray.tabulate(n)(i => logWeights(i) - largest)
      val order = IArray.from((0 until n).sortBy(shifted.apply))
      val tail = IArray.tabulate(tailDraws)(i => shifted(order(n - tailDraws + i)))
      val cutoff = shifted(order(n - tailDraws - 1))
      val highest = tail(tailDraws - 1)
      val lowest = tail(0)
      if math.abs(highest - lowest) < java.lang.Double.MIN_NORMAL then
        (normalised(shifted), Double.NaN)
      else
        // On the weight scale, where a generalised Pareto is what the theory is about.
        val excesses = IArray.tabulate(tailDraws)(i => math.exp(tail(i)) - math.exp(cutoff))
        val fitted = fit(excesses)
        val smoothed =
          if !fitted.k.isFinite then None
          else
            Some(IArray.tabulate(tailDraws) { i =>
              val p = (i + 0.5) / tailDraws
              val value = math.log(inverse(p, fitted, math.exp(cutoff)))
              // A smoothed weight above the largest observed one would invent mass.
              if value > highest then highest else value
            })
        val replaced = smoothed.fold(shifted) { values =>
          val byPosition = (0 until tailDraws).map(i => order(n - tailDraws + i) -> values(i)).toMap
          IArray.tabulate(n)(i => byPosition.getOrElse(i, shifted(i)))
        }
        (normalised(replaced), fitted.k)

  private def normalised(logWeights: IArray[Double]): IArray[Double] =
    val total = LogExp.logSumExp(logWeights)
    IArray.tabulate(logWeights.length)(i => logWeights(i) - total)
