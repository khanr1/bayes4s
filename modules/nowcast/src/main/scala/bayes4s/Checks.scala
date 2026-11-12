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

import cats.syntax.all.*

/** A statistic of the data, as it was observed and as the model replicates it.
  *
  * `replicated(r)(period)` is replication `r`'s value of the statistic in that period, and
  * `observed(period)` is what actually happened. Everything a predictive check says comes from
  * comparing the two — and the comparison is made **per period**, because a model can get the
  * total right while being wrong about every month in it.
  */
final case class PredictiveSample(
    statistic: String,
    observed: IArray[Double],
    replicated: IArray[IArray[Double]]
):

  def periods: Int = observed.length
  def replications: Int = replicated.length

  /** The central band of the replications, period by period. */
  def band(mass: Double): IArray[(Double, Double)] =
    if !(mass > 0.0 && mass < 1.0) || replications == 0 then
      IArray.fill(periods)((Double.NaN, Double.NaN))
    else
      val lower = (1.0 - mass) / 2.0
      IArray.tabulate(periods) { p =>
        val column = IArray.tabulate(replications)(r => replicated(r)(p))
        (Quantile.quantile(column, lower), Quantile.quantile(column, 1.0 - lower))
      }

  /** How many periods fell outside the band. About `(1 - mass)` of them should, in a model that
    * fits: zero is as much a warning as half, and means the replications are too wide.
    */
  def outsideBand(mass: Double): Int =
    val bands = band(mass)
    (0 until periods).count { p =>
      val (low, high) = bands(p)
      low.isNaN || observed(p) < low || observed(p) > high
    }

/** Predictive checks for a count model, from the prior and from the posterior.
  *
  * The statistic is the number of reports that **arrived** in each calendar period — the cells with
  * `month + delay = period`, summed over the visible part of the grid. That is the series a
  * nowcast is read against: not how many events were created, which nobody has seen yet, but how
  * many reports came in, which everybody has.
  * **Example.** The two predictive checks. The first costs nothing and needs no fit; the second needs
  * the fit and compares the model with the data it was given:
  *
  * ```scala
  * //{
  * import java.time.YearMonth
  * val months = 18
  * val maxLag = 5
  * val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
  * val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)
  * val blank = DelayCountData.blank(months, maxLag, t, visible)
  * //}
  * val priorCheck: Either[BayesError, PredictiveSample] = blank.map { data =>
  *   Checks.priorPredictive(DelayCountModel(Ar1Trend(), OrderedLogitDrift(), data), draws = 200, Seed(1L))
  * }
  *
  * // Simulated totals spanning orders of magnitude mean the priors are doing nothing — which is
  * // something to find out before fitting rather than after.
  * val spread: Either[BayesError, (Double, Double)] = priorCheck.map { sample =>
  *   val totals = IArray.tabulate(sample.replications)(r =>
  *     (0 until sample.periods).foldLeft(0.0)((sum, p) => sum + sample.replicated(r)(p))
  *   )
  *   (Quantile.quantile(totals, 0.05), Quantile.quantile(totals, 0.95))
  * }
  *
  * val posteriorCheck: Either[BayesError, Int] = blank.flatMap { data =>
  *   val model = DelayCountModel(Ar1Trend(), OrderedLogitDrift(), data)
  *   for
  *     chains <- Mcmc.runNuts(model, RunSettings(chains = 2, warmup = 400, samples = 500))
  *       .runA(Seed(7L))
  *       .value
  *     posterior <- Mcmc.draws(model, chains)
  *     sample <- Checks.posteriorPredictive(model, posterior, Seed(99L))
  *   // Read it in both directions: about a tenth of the periods should fall outside a 90 % band, and
  *   // zero is as much a warning as half.
  *   yield sample.outsideBand(0.9)
  * }
  * ```
  */
object Checks:

  /** Rates and counts simulated from the priors alone.
    *
    * What it is for: finding out that the priors imply a thousand times more events than anyone
    * has ever seen, before fitting anything. A prior predictive check that looks reasonable is
    * weak evidence; one that looks absurd is strong evidence, and it costs nothing.
    */
  def priorPredictive[R[_], D[_]](
      model: DelayCountModel[R, D],
      draws: Int,
      seed: Seed
  ): PredictiveSample =
    val generative = DelayCountModel.generative(model.rate, model.delay, model.data)
    val program = (0 until math.max(draws, 0)).toList.traverse { _ =>
      for
        parameters <- generative.generative.samplePrior[Rand]
        simulated <- generative.generative.simulateData[Rand](parameters)
      yield arrivals(simulated)
    }
    val replicated = program.runA(seed).value
    PredictiveSample("reports per period", arrivals(model.data), IArray.from(replicated))

  /** The same statistic, replicated from the posterior rather than the prior.
    *
    * Each sample from the posterior is turned back into parameters and used to simulate a whole
    * grid of counts, so the replications carry both the uncertainty about the parameters and the
    * Poisson noise. A check that used only the posterior mean would be too narrow and would
    * reject a model that fits.
    */
  def posteriorPredictive[R[_], D[_]](
      model: DelayCountModel[R, D],
      posterior: Draws,
      seed: Seed
  ): Either[BayesError, PredictiveSample] =
    val generative = DelayCountModel.generative(model.rate, model.delay, model.data)
    val points = posterior.flatten
    points.toList.zipWithIndex
      .traverse { (row, i) =>
        // The row is read by the model's own layout rather than by parsing the names: a block of
        // `size` values in order, which is exactly how `Mcmc.draws` wrote it. Splitting names on a
        // dot would work until a block's name contained one.
        val grouped = model.params.layout
          .foldLeft((Map.empty[String, IArray[Double]], 0)) { case ((collected, from), block) =>
            val values = IArray.tabulate(block.size)(i => row(from + i))
            (collected + (block.name -> values), from + block.size)
          }
          ._1
        Model
          .unconstrain(model, grouped)
          .map { at =>
            val parameters = Model.constrain(model, at)
            generative.generative
              .simulateData[Rand](parameters)
              .map(arrivals)
              .runA(seed.fold(i.toLong))
              .value
          }
      }
      .map(replicated =>
        PredictiveSample("reports per period", arrivals(model.data), IArray.from(replicated))
      )

  /** Reports that arrived in each calendar period: the visible cells with `month + delay = p`. */
  private[bayes4s] def arrivals(data: DelayCountData): IArray[Double] =
    val periods = data.months + data.maxLag
    IArray.tabulate(periods) { p =>
      (0 until data.groups).foldLeft(0.0) { (sum, g) =>
        sum + (0 until data.months).foldLeft(0.0) { (inner, m) =>
          val k = p - m
          if k < 0 || k > data.maxLag || !data.visible.isVisible(m, k) then inner
          else inner + data.counts(g)(m)(k)
        }
      }
    }
