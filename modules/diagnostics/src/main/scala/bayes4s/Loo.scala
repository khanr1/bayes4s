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

/** What leave-one-out cross-validation came to, per observation and in total. */
final case class LooResult(
    elpd: Double,
    se: Double,
    pLoo: Double,
    pointwiseElpd: IArray[Double],
    paretoK: IArray[Double]
):
  /** The observations whose importance weights cannot be trusted.
    *
    * A shape above the threshold means the leave-one-out posterior is too far from the full one
    * for reweighting to reach it — usually an outlier, or an observation the model cannot explain.
    * The elpd of such an observation is not an estimate of anything, and reporting the total
    * without saying which ones they were would hide it.
    */
  def problematic(threshold: Double): List[Int] =
    (0 until paretoK.length).filter(i => !(paretoK(i) <= threshold)).toList

/** One model against the best of a set. */
final case class LooComparison(name: String, elpd: Double, difference: Double, se: Double)

/** The log likelihood of a model, observation by observation.
  *
  * Cross-validation needs to know what each observation contributed, which `logLikelihood` alone
  * cannot say. The two have to agree — the sum of the parts is the whole — and that is the
  * obligatory test: a model whose `pointwise` left an observation out would give a leave-one-out
  * estimate for a model nobody fitted.
  */
trait PointwiseLikelihood[T[_]]:
  def pointwise(p: T[Double]): IArray[Double]

/** Pareto-smoothed importance sampling leave-one-out cross-validation.
  *
  * Vehtari, Gelman and Gabry (2017) for the method, Vehtari et al. (2024) for the smoothing. The
  * reference values of `multilevel.json` come from ArviZ, so ArviZ's arithmetic is the
  * specification: the weights are the negated log likelihood, their right tail is replaced by the
  * fitted Pareto quantiles, and the pointwise estimate is the log of the weighted average.
  *
  * The standard error is the one everyone reports and it is worth knowing what it is: the spread
  * of the pointwise estimates times the square root of their number, which assumes the
  * observations are independent given the parameters. It says nothing about the Monte Carlo error
  * of the fit, and on a sample with an outlier it can be larger than the estimate itself.
  */
object Loo:

  /** The threshold on the Pareto shape, which tightens as the sample grows.
    *
    * `min(1 - 1/log10(S), 0.7)`: at four thousand draws it is 0.7, and at a hundred it is 0.5.
    */
  def kThreshold(draws: Int): Double =
    if draws < 10 then 0.7 else math.min(1.0 - 1.0 / math.log10(draws.toDouble), 0.7)

  /** `logLik(i)(s)` is the log likelihood of observation `i` at posterior draw `s`.
    *
    * `relativeEss` is one effective sample size per observation, divided by the number of draws:
    * it sets how much of the tail is smoothed. For a single chain ArviZ uses 1 for every
    * observation, and the caller is the one who knows how the draws were produced, which is why it
    * is an argument and not a guess.
    */
  def psis(logLik: IArray[IArray[Double]], relativeEss: IArray[Double]): LooResult =
    val observations = logLik.length
    if observations == 0 then
      LooResult(Double.NaN, Double.NaN, Double.NaN, IArray.empty, IArray.empty)
    else
      val draws = logLik(0).length
      val perObservation = IArray.tabulate(observations) { i =>
        val weights = IArray.tabulate(draws)(s => -logLik(i)(s))
        val tail = Pareto.tailLength(draws, relativeEss(i), bothTails = false)
        val (smoothed, k) = Pareto.smoothRightTail(weights, tail)
        val elpd = LogExp.logSumExp(IArray.tabulate(draws)(s => smoothed(s) + logLik(i)(s)))
        val lpd = LogExp.logSumExp(logLik(i)) - math.log(draws.toDouble)
        (elpd, k, lpd - elpd)
      }
      val elpdPointwise = IArray.tabulate(observations)(i => perObservation(i)._1)
      val k = IArray.tabulate(observations)(i => perObservation(i)._2)
      val elpd = (0 until observations).foldLeft(0.0)((sum, i) => sum + elpdPointwise(i))
      val pLoo = (0 until observations).foldLeft(0.0)((sum, i) => sum + perObservation(i)._3)
      val se =
        if observations < 2 then 0.0
        else
          val mean = elpd / observations
          val spread =
            (0 until observations).foldLeft(0.0) { (sum, i) =>
              val gap = elpdPointwise(i) - mean
              sum + gap * gap
            } / (observations - 1.0)
          math.sqrt(observations * spread)
      LooResult(elpd, se, pLoo, elpdPointwise, k)

  /** The same, with one effective sample size for every observation. */
  def psis(logLik: IArray[IArray[Double]], relativeEss: Double): LooResult =
    psis(logLik, IArray.fill(logLik.length)(relativeEss))

  /** Each model against the best one, with the standard error of the difference.
    *
    * The error of a difference is **not** the difference of the errors: the two models are scored
    * on the same observations, so their pointwise estimates are correlated and the error has to be
    * computed from the pointwise differences. Reporting the elpds' own errors instead is the usual
    * mistake and it overstates the uncertainty by a lot.
    */
  def compare(models: List[(String, LooResult)]): List[LooComparison] =
    if models.isEmpty then Nil
    else
      val best = models.maxBy((_, result) => result.elpd)
      models
        .sortBy((_, result) => -result.elpd)
        .map { (name, result) =>
          val difference = result.elpd - best._2.elpd
          val se =
            if name == best._1 then 0.0
            else if result.pointwiseElpd.length != best._2.pointwiseElpd.length then Double.NaN
            else
              val n = result.pointwiseElpd.length
              val gaps = IArray.tabulate(n)(i => result.pointwiseElpd(i) - best._2.pointwiseElpd(i))
              val mean = (0 until n).foldLeft(0.0)((sum, i) => sum + gaps(i)) / n
              val spread = (0 until n).foldLeft(0.0) { (sum, i) =>
                val gap = gaps(i) - mean
                sum + gap * gap
              } / (n - 1.0)
              math.sqrt(n * spread)
          LooComparison(name, result.elpd, difference, se)
        }
