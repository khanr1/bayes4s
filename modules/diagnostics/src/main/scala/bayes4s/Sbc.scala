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
import cats.data.State
import cats.syntax.all.*

/** The ranks of simulation-based calibration, one list per parameter.
  *
  * A rank is how many posterior samples fell below the parameter that generated the data. If the
  * model, the sampler and the generative part agree, those ranks are **uniform** over
  * `0 .. thinnedDraws`, and any departure is a miscalibration: a prior written twice and
  * differently, a Jacobian missing, a sampler that cannot reach part of the posterior.
  */
final case class SbcRanks(
    ranks: Map[String, IArray[Int]],
    thinnedDraws: Int,
    replications: Int
):
  /** Pearson's statistic against uniformity, per parameter.
    *
    * `thinnedDraws + 1` values are possible, so the bins divide evenly only when `bins` divides
    * it; otherwise some bins hold one more value than others, the expected counts are unequal,
    * and the statistic would reject a correct sampler. That case returns `NaN` rather than a
    * number nobody should read.
    */
  def uniformityChiSquared(bins: Int): Map[String, Double] =
    val values = thinnedDraws + 1
    if bins < 2 || values % bins != 0 then ranks.view.mapValues(_ => Double.NaN).toMap
    else
      val width = values / bins
      val expected = replications.toDouble / bins
      ranks.view.mapValues { observed =>
        val counts = Array.ofDim[Int](bins)
        (0 until observed.length).foreach { i =>
          val bin = math.min(observed(i) / width, bins - 1)
          counts(bin) = counts(bin) + 1
        }
        (0 until bins).foldLeft(0.0) { (total, b) =>
          val gap = counts(b) - expected
          total + gap * gap / expected
        }
      }.toMap

  /** The parameters whose ranks fail a chi-squared test at the stated critical value. */
  def miscalibrated(bins: Int, critical: Double): List[String] =
    uniformityChiSquared(bins).toList.collect {
      case (name, statistic) if statistic.isNaN || statistic > critical => name
    }.sorted

/** Simulation-based calibration (Talts, Betancourt, Simpson, Vehtari, Gelman 2018).
  *
  * Draw a parameter from the prior, simulate data from it, fit the model to that data, and see
  * where the parameter falls among the posterior samples. Repeat. The ranks must be uniform, and
  * that is a statement about the **whole** of a generative model — the prior as a density and the
  * prior as a sampler, the likelihood as a density and the likelihood as a simulator, the
  * transforms' Jacobians, and the sampler itself. Nothing else tests all of them at once.
  *
  * This is why `GenerativeModel` carries what it carries: `samplePrior` for the first step,
  * `simulateData` for the second, `conditionedOn` for the third, and `named` to find the parameter
  * among the samples of the fourth.
  *
  * The samples are **thinned** before ranking. Talts et al. are explicit about it: consecutive
  * samples of a Markov chain are correlated, and ranking against correlated samples gives a rank
  * histogram with a shape of its own even when everything is right.
  */
object Sbc:

  /** `replications` rounds of draw, simulate, fit, rank.
    *
    * Each round gets its own derived seed, so the result is a function of the seed it was given
    * and of nothing else — including the order the rounds are computed in.
    */
  def run[T[_], Y](
      model: GenerativeModel[T, Y],
      settings: RunSettings,
      replications: Int,
      thinnedDraws: Int = 63
  ): State[Seed, Either[BayesError, SbcRanks]] =
    State.get[Seed].map { seed =>
      (0 until replications).toList
        .traverse(round => once(model, settings, thinnedDraws, seed.fold(round.toLong + 1L)))
        .map { rounds =>
          val names = rounds.headOption.map(_.keySet).getOrElse(Set.empty)
          SbcRanks(
            names.toList.map(name => name -> IArray.from(rounds.map(_(name)))).toMap,
            thinnedDraws,
            replications
          )
        }
    }

  /** One round: the rank of each value of the drawn parameter among the posterior samples. */
  private def once[T[_], Y](
      model: GenerativeModel[T, Y],
      settings: RunSettings,
      thinnedDraws: Int,
      seed: Seed
  ): Either[BayesError, Map[String, Int]] =
    val program = for
      truth <- model.generative.samplePrior[Rand]
      simulated <- model.generative.simulateData[Rand](truth)
      conditioned = model.conditionedOn(simulated)
      target = Model.target(conditioned)
      start <- Monad[Rand]
        .replicateA(conditioned.params.unconstrainedSize, Rng[Rand].uniform)
        .map(us => IArray.from(us.map(u => 2.0 * (2.0 * u - 1.0))))
      fitted <- Mcmc.run[Rand](
        Nuts.kernel[Rand](target, settings.nuts),
        target,
        start,
        settings.warmup,
        settings.samples,
        settings.adaptation
      )
    yield (truth, conditioned, fitted)
    val (truth, conditioned, fitted) = program.runA(seed).value
    fitted.map { chain =>
      val wanted = model.generative.named(truth)
      val thinned = thin(chain.draws.toList, thinnedDraws)
      val samples = thinned.map(q => Model.values(conditioned, q))
      wanted.toList.flatMap { (block, values) =>
        (0 until values.length).map { index =>
          val name = if values.length == 1 then block else s"$block.${index + 1}"
          val below = samples.count { sampled =>
            sampled.get(block).exists(column => column(index) < values(index))
          }
          name -> below
        }
      }.toMap
    }

  /** `count` samples spread evenly over what the chain produced. */
  private def thin(draws: List[IArray[Double]], count: Int): List[IArray[Double]] =
    if draws.isEmpty || count <= 0 then Nil
    else if draws.length <= count then draws
    else
      val step = draws.length.toDouble / count
      (0 until count).toList.map(i => draws(math.min((i * step).toInt, draws.length - 1)))
