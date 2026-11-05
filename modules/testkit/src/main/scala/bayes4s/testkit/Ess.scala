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

package bayes4s.testkit

/** An effective sample size, for sizing a Monte Carlo threshold.
  *
  * The rule of section 6.5 is that a statistical test states its standard error, and for a chain
  * that error is `sd / sqrt(ESS)` and not `sd / sqrt(n)`: a chain of a thousand correlated draws
  * can carry the information of two hundred independent ones, and a threshold built on the
  * thousand would fail a correct sampler.
  *
  * This is the estimator of Vehtari et al. (2021) as Stan implements it — the pooled
  * within-chain autocorrelation, truncated by Geyer's initial positive sequence — written here
  * because the validated one arrives with the diagnostics of phase 5. **It is used only to size
  * thresholds, never as a reference value**, and the suites of phase 5 will point at the real one
  * once it exists and is checked against Stan's own fourteen numbers.
  */
object Ess:

  /** The effective sample size of one scalar, across chains of equal length. */
  def of(chains: List[IArray[Double]]): Double =
    val m = chains.length
    if m == 0 then 0.0
    else
      val n = chains.head.length
      if n < 4 || chains.exists(_.length != n) then 0.0
      else
        val means = chains.map(c => mean(c))
        val variances = chains.map(c => variance(c))
        val within = means.length.toDouble match
          case _ => variances.sum / m
        val between =
          if m == 1 then 0.0
          else
            val grand = means.sum / m
            means.map(x => (x - grand) * (x - grand)).sum / (m - 1) * n
        val varPlus = within * (n - 1) / n.toDouble + (if m == 1 then 0.0 else between / n)
        if !(varPlus > 0.0) then 0.0
        else
          // rho(t) pooled over chains, as Stan combines them.
          val maxLag = math.min(n - 2, 2000)
          val rho = (1 to maxLag).map { t =>
            val acov = chains.map(c => autocovariance(c, t)).sum / m
            1.0 - (within - acov) / varPlus
          }.toVector
          // Geyer: sum the successive pairs while they stay positive.
          val pairs = rho.indices
            .filter(_ % 2 == 0)
            .map(i => if i + 1 < rho.length then rho(i) + rho(i + 1) else rho(i))
          val kept = pairs.takeWhile(_ > 0.0)
          val tau = kept.sum * 2.0 - (if rho.nonEmpty then rho(0) else 0.0)
          val total = (m * n).toDouble
          if tau <= -1.0 then total else total / (1.0 + 2.0 * tau)

  /** The Monte Carlo standard error of a mean, from the draws themselves. */
  def standardError(chains: List[IArray[Double]]): Double =
    val ess = of(chains)
    val pooled = chains.flatMap(_.toList)
    val grandMean = pooled.sum / pooled.length
    val spread =
      pooled.map(x => (x - grandMean) * (x - grandMean)).sum / (pooled.length - 1)
    if ess > 0.0 then math.sqrt(spread / ess) else Double.PositiveInfinity

  private def mean(xs: IArray[Double]): Double =
    (0 until xs.length).foldLeft(0.0)((sum, i) => sum + xs(i)) / xs.length

  private def variance(xs: IArray[Double]): Double =
    val m = mean(xs)
    (0 until xs.length).foldLeft(0.0)((sum, i) => sum + (xs(i) - m) * (xs(i) - m)) /
      (xs.length - 1)

  private def autocovariance(xs: IArray[Double], lag: Int): Double =
    val m = mean(xs)
    val n = xs.length
    (0 until n - lag).foldLeft(0.0)((sum, i) => sum + (xs(i) - m) * (xs(i + lag) - m)) / n
