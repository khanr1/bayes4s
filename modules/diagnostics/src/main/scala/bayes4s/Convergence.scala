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

/** The convergence diagnostics of Vehtari et al. (2021), **as Stan implements them**.
  *
  * The qualification is the whole of this file. ArviZ 1.x computes three of these differently —
  * basic ESS on split chains where Stan does not split, the upper tail with `>` where Stan uses
  * `>=`, and `mcse_sd` from a different ESS — and differs from Stan by 0.5 % to 15 % as a result.
  * The fixture records both so the gap is documented rather than discovered: this library follows
  * Stan, and `reference/stan_analyze_reference.py` is the line-by-line port that the fourteen
  * values Stan's own tests assert are reproduced from.
  *
  * Orientation: a sample is `IArray[IArray[Double]]` with `chains(c)(i)` the i-th draw of chain
  * `c`. Stan's C++ and the port both put draws down the columns instead; the numbers are the same
  * and the indices read better this way.
  *
  * Everything here returns `NaN` rather than an error for a sample it cannot summarise — a
  * constant chain, an infinite value, too few draws. A diagnostic is a number you look at, and a
  * `NaN` in a summary table is the diagnosis.
  * **Example.**
  * One statistic at a time, for when you have columns rather than a fit. Each takes one row per
  * chain:
  *
  * ```scala
  * val chains: IArray[IArray[Double]] =
  *   IArray(
  *     IArray.tabulate(500)(i => math.sin(i * 0.1)),
  *     IArray.tabulate(500)(i => math.sin(i * 0.1 + 1.0))
  *   )
  *
  * // Rank-normalised, so it still means something for a posterior with no variance.
  * val rhat: Double = Convergence.rhatRank(chains)
  *
  * // How many independent samples the correlated ones are worth, in the middle and in the tails.
  * val bulk: Double = Convergence.essBulk(chains)
  * val tail: Double = Convergence.essTail(chains)
  *
  * // A `NaN` is a diagnosis rather than an error: a constant chain has no variance to compare.
  * val constant: Double = Convergence.rhatRank(IArray(IArray.fill(100)(1.0), IArray.fill(100)(1.0)))
  * ```
  */
object Convergence:

  /** Stan's biased autocovariance: divided by `N`, through an FFT zero-padded to a power of two.
    *
    * Biased on purpose. The unbiased estimator divided by `N - t` has a variance that grows with
    * the lag, and Geyer's truncation of the resulting sequence is the thing that would break.
    */
  def autocovariance(y: IArray[Double]): IArray[Double] =
    val n = y.length
    if n == 0 then IArray.empty[Double]
    else
      val size = if 2 * n <= 1 then 1 else Integer.highestOneBit(2 * n - 1) * 2
      val mean = (0 until n).foldLeft(0.0)((sum, i) => sum + y(i)) / n
      val padded = IArray.tabulate(size)(i => if i < n then y(i) - mean else 0.0)
      val transformed = for
        spectrum <- Fft.forwardReal(padded)
        power = IArray.tabulate(size)(i =>
          spectrum.re(i) * spectrum.re(i) + spectrum.im(i) * spectrum.im(i)
        )
        back <- Fft.inverse(power, IArray.fill(size)(0.0))
      yield IArray.tabulate(n)(i => back.re(i) / n)
      // The only way `Fft` can refuse is a length that is not a power of two, which `size` is by
      // construction; a `NaN` series is its own answer.
      transformed.getOrElse(IArray.fill(n)(Double.NaN))

  /** Each chain cut in two: halves of `floor(n/2)`, the second starting at `floor((n+1)/2)`.
    *
    * An odd number of draws therefore drops the middle one, and the two halves are the same
    * length. Stan does it this way and the choice changes the numbers.
    */
  def splitChains(chains: IArray[IArray[Double]]): IArray[IArray[Double]] =
    if chains.isEmpty then chains
    else
      val n = chains(0).length
      val half = n / 2
      val tail = (n + 1) / 2
      IArray.tabulate(chains.length * 2) { k =>
        val chain = chains(k / 2)
        if k % 2 == 0 then IArray.tabulate(half)(i => chain(i))
        else IArray.tabulate(half)(i => chain(tail + i))
      }

  /** The rank normalisation: average ranks over all the draws together, then the Gaussian
    * quantile of `(r - 3/8) / (S + 1/4)`.
    *
    * It is what makes R-hat and ESS mean something for a parameter whose posterior has no
    * variance — a Cauchy scale, a funnel's neck — because the transformed sample always does.
    */
  def rankTransform(chains: IArray[IArray[Double]]): IArray[IArray[Double]] =
    val total = chains.toList.map(_.length).sum
    val pooled = for
      c <- (0 until chains.length).toList
      i <- 0 until chains(c).length
    yield ((c, i), chains(c)(i))
    val ranks = averageRanks(pooled.map(_._2))
    val byPosition = pooled.map(_._1).zip(ranks).toMap
    IArray.tabulate(chains.length) { c =>
      IArray.tabulate(chains(c).length) { i =>
        Special.invPhi((byPosition((c, i)) - 0.375) / (total + 0.25))
      }
    }

  /** Ranks from one upwards, ties sharing their average: `rankdata(method = "average")`. */
  private def averageRanks(values: List[Double]): List[Double] =
    val sorted = values.zipWithIndex.sortBy(_._1)
    val assigned = sorted
      .foldLeft((List.empty[(Int, Double)], 0)) { case ((collected, from), _) =>
        if from >= sorted.length then (collected, from)
        else
          val value = sorted(from)._1
          val until = (from until sorted.length).takeWhile(i => sorted(i)._1 == value).length
          val average = (from + 1 + from + until).toDouble / 2.0
          (
            collected ++ (from until from + until).map(i => (sorted(i)._2, average)),
            from + until
          )
      }
      ._1
      .toMap
    values.indices.toList.map(assigned)

  /** Stan's basic R-hat on the sample as given: `sqrt((B/W + n - 1) / n)`. */
  def rhat(chains: IArray[IArray[Double]]): Double =
    if chains.length < 2 || chains(0).length < 2 then Double.NaN
    else
      val n = chains(0).length
      val means = IArray.tabulate(chains.length)(c => mean(chains(c)))
      val between = n * variance(means)
      val within = mean(IArray.tabulate(chains.length)(c => variance(chains(c))))
      math.sqrt((between / within + n - 1.0) / n)

  /** R-hat on the split chains: Stan's `rhat_basic`, what `stansummary` prints as `R_hat`. */
  def rhatBasic(chains: IArray[IArray[Double]]): Double = rhat(splitChains(chains))

  /** The rank-normalised R-hat: the larger of the bulk and the tail.
    *
    * The tail one is the bulk statistic applied to `|x - median|`, which is what notices a chain
    * that agrees with the others about the middle and not about the spread.
    */
  def rhatRank(chains: IArray[IArray[Double]]): Double =
    val (bulk, tail) = rhatRankParts(chains)
    if bulk.isNaN || tail.isNaN then Double.NaN else math.max(bulk, tail)

  def rhatRankParts(chains: IArray[IArray[Double]]): (Double, Double) =
    val split = splitChains(chains)
    if !isFiniteAndVaries(split) then (Double.NaN, Double.NaN)
    else
      val median = Quantile.quantile(pooled(split), 0.5)
      val folded = IArray.tabulate(split.length) { c =>
        IArray.tabulate(split(c).length)(i => math.abs(split(c)(i) - median))
      }
      (rhat(rankTransform(split)), rhat(rankTransform(folded)))

  /** The effective number of samples, by Geyer's initial positive and monotone sequence.
    *
    * Written as `ess.hpp` writes it, including the floor at `1 / log10(S)`: without it a sample
    * whose autocorrelations happen to sum to something tiny reports more effective samples than
    * it has draws.
    */
  def ess(chains: IArray[IArray[Double]]): Double =
    if chains.isEmpty || chains(0).length < 4 then Double.NaN
    else
      val draws = chains(0).length
      val count = chains.length
      val acov = IArray.tabulate(count)(c => autocovariance(chains(c)))
      val chainVariance =
        IArray.tabulate(count)(c => acov(c)(0) * draws / (draws - 1.0))
      val within = mean(chainVariance)
      val varPlus =
        val base = within * (draws - 1.0) / draws
        if count > 1 then base + variance(IArray.tabulate(count)(c => mean(chains(c)))) else base
      def correlation(lag: Int): Double =
        1.0 - (within - mean(IArray.tabulate(count)(c => acov(c)(lag)))) / varPlus
      val first = correlation(1)
      // The sequence is walked two lags at a time: positive while the pair sums above zero, and
      // then flattened so that it never rises again.
      def walk(
          t: Int,
          even: Double,
          odd: Double,
          rho: Vector[Double]
      ): (Int, Double, Vector[Double]) =
        if !(t < draws - 4 && even + odd > 0.0 && !(even + odd).isNaN) then (t, even, rho)
        else
          val nextEven = correlation(t + 1)
          val nextOdd = correlation(t + 2)
          val kept =
            if nextEven + nextOdd >= 0.0 then rho.updated(t + 1, nextEven).updated(t + 2, nextOdd)
            else rho
          val monotone =
            if kept(t + 1) + kept(t + 2) > kept(t - 1) + kept(t) then
              val average = (kept(t - 1) + kept(t)) / 2.0
              kept.updated(t + 1, average).updated(t + 2, average)
            else kept
          walk(t + 2, nextEven, nextOdd, monotone)
      val start = Vector.fill(draws)(0.0).updated(0, 1.0).updated(1, first)
      val (maxT, lastEven, rho) = walk(1, 1.0, first, start)
      val closed = if lastEven > 0.0 then rho.updated(maxT + 1, lastEven) else rho
      val total = (count * draws).toDouble
      val sum = (0 until maxT).foldLeft(0.0)((acc, i) => acc + closed(i))
      val tau = math.max(-1.0 + 2.0 * sum + closed(maxT + 1), 1.0 / math.log10(total))
      total / tau

  /** Stan's `ess_basic`: on the **unsplit** chains, which is where ArviZ 1.x differs. */
  def essBasic(chains: IArray[IArray[Double]]): Double =
    if !isFiniteAndVaries(chains) then Double.NaN else ess(chains)

  /** The bulk effective number of samples: the rank-normalised sample, split. */
  def essBulk(chains: IArray[IArray[Double]]): Double =
    val split = splitChains(chains)
    if !isFiniteAndVaries(split) || split.isEmpty || split(0).length < 4 then Double.NaN
    else ess(rankTransform(split))

  /** The tail effective number of samples: the smaller of the two tail indicators. */
  def essTail(chains: IArray[IArray[Double]]): Double =
    val (below, above) = essTailParts(chains)
    if below.isNaN then above
    else if above.isNaN then below
    else math.min(below, above)

  /** The two halves of the tail diagnostic: the effective sizes of `x <= q05` and of `x >= q95`.
    *
    * The upper indicator is `x >= q95`, not `x > q95`. That is one character, it is Stan, and on a
    * sample with draws sitting exactly on the 95th percentile it is the difference between 533 and
    * 1123 effective samples — so the two halves are public, and the suite asserts the upper one
    * where a tie makes the conventions part company.
    */
  def essTailParts(chains: IArray[IArray[Double]]): (Double, Double) =
    val split = splitChains(chains)
    if !isFiniteAndVaries(split) || split.isEmpty || split(0).length < 4 then
      (Double.NaN, Double.NaN)
    else
      val all = pooled(split)
      val low = Quantile.quantile(all, 0.05)
      val high = Quantile.quantile(all, 0.95)
      def indicator(f: Double => Boolean): Double =
        ess(IArray.tabulate(split.length) { c =>
          IArray.tabulate(split(c).length)(i => if f(split(c)(i)) then 1.0 else 0.0)
        })
      (indicator(_ <= low), indicator(_ >= high))

  /** The Monte Carlo standard error of the mean, from the **unsplit** effective sample size. */
  def mcseMean(chains: IArray[IArray[Double]]): Double =
    if chains.isEmpty || chains(0).length < 4 || !isFiniteAndVaries(chains) then Double.NaN
    else
      val all = pooled(chains)
      val average = mean(all)
      val spread =
        (0 until all.length).foldLeft(0.0)((sum, i) => sum + squared(all(i) - average)) /
          (all.length - 1.0)
      math.sqrt(spread / ess(chains))

  /** The Monte Carlo standard error of the standard deviation.
    *
    * The effective sample size of `|x - mean|` on the split chains, then a Taylor expansion. Eight
    * draws at least, because the fourth moment of a shorter sample says nothing.
    */
  def mcseSd(chains: IArray[IArray[Double]]): Double =
    if chains.isEmpty || chains(0).length < 8 || !isFiniteAndVaries(chains) then Double.NaN
    else
      val average = mean(pooled(chains))
      val deviations = IArray.tabulate(chains.length) { c =>
        IArray.tabulate(chains(c).length)(i => math.abs(chains(c)(i) - average))
      }
      val size = ess(splitChains(deviations))
      val all = pooled(deviations)
      val second = mean(IArray.tabulate(all.length)(i => squared(all(i))))
      val fourth = mean(IArray.tabulate(all.length)(i => squared(squared(all(i)))))
      math.sqrt((fourth - squared(second)) / size / second / 4.0)

  /** A sample a diagnostic can say anything about: finite, and moving in every chain. */
  def isFiniteAndVaries(chains: IArray[IArray[Double]]): Boolean =
    chains.nonEmpty && chains(0).nonEmpty && (0 until chains.length).forall { c =>
      val chain = chains(c)
      val finite = (0 until chain.length).forall(i => chain(i).isFinite)
      finite && ((0 until chain.length).map(chain.apply).max >
        (0 until chain.length).map(chain.apply).min)
    }

  private def pooled(chains: IArray[IArray[Double]]): IArray[Double] =
    val count = chains.length
    val draws = if chains.isEmpty then 0 else chains(0).length
    IArray.tabulate(count * draws)(k => chains(k / draws)(k % draws))

  private def squared(x: Double): Double = x * x

  private def mean(xs: IArray[Double]): Double =
    (0 until xs.length).foldLeft(0.0)((sum, i) => sum + xs(i)) / xs.length

  /** The sample variance, dividing by `n - 1`. */
  private def variance(xs: IArray[Double]): Double =
    val average = mean(xs)
    (0 until xs.length).foldLeft(0.0)((sum, i) => sum + squared(xs(i) - average)) /
      (xs.length - 1.0)
