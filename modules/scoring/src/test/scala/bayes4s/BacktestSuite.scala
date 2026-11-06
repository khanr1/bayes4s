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

import bayes4s.testkit.Approx
import cats.Monad
import weaver.SimpleIOSuite

/** Scoring a run of forecasts, and saying how uncertain the resulting average is.
  *
  * The second half is the one with a trap in it. Scores on neighbouring dates are correlated — a
  * model wrong in March is usually wrong in April — so the ordinary standard error of their mean,
  * `sd/sqrt(n)`, is too small, sometimes by a factor of several. The moving-block bootstrap keeps
  * the correlation inside each block, and the suite checks it on both kinds of data: independent
  * scores, where it must agree with the ordinary error, and strongly correlated ones, where it
  * must be clearly larger.
  */
object BacktestSuite extends SimpleIOSuite with Approx:

  private val alpha = 0.1

  /** Four dates, by hand, with forecasts whose quantiles are easy to see. */
  private val cases: List[(Int, IArray[Double], Double)] =
    val tenToTwenty = IArray.tabulate(11)(i => 10.0 + i.toDouble)
    List(
      (1, tenToTwenty, 15.0), // inside
      (2, tenToTwenty, 9.0), // below
      (3, tenToTwenty, 21.0), // above
      // Exactly on the lower bound, which for eleven samples from 10 to 20 at alpha = 0.1 is the
      // type-7 quantile 10.5 and not the smallest sample: boundary inclusion is the claim here.
      (4, tenToTwenty, 10.5)
    )

  pureTest("each date is scored with the same arithmetic as the pieces it is made of") {
    val result = Backtest.run(cases, alpha)
    val first = result.scored.head
    val forecast = cases.head._2
    check(result.dates == 4, s"${result.dates} dates")
      .and(check(result.scored.map(_.date) == List(1, 2, 3, 4), "the dates came back reordered"))
      .and(
        closeRel(first.crps, Crps.ensemble(forecast, 15.0), 1e-15, "the score of the first date")
      )
      .and(closeRel(first.pit, Calibration.pit(forecast, 15.0), 1e-15, "its transform"))
      .and(closeRel(first.bias, Calibration.bias(forecast, 15.0), 1e-15, "its bias"))
      // The interval is the forecast's own type-7 quantiles, not a normal approximation of it.
      .and(closeRel(first.lower, Quantile.quantile(forecast, 0.05), 1e-15, "the lower bound"))
      .and(closeRel(first.lower, 10.5, 1e-15, "the type-7 5 % quantile of eleven samples 10 to 20"))
      .and(closeRel(first.upper, Quantile.quantile(forecast, 0.95), 1e-15, "the upper bound"))
      .and(
        closeRel(
          first.intervalScore,
          Calibration.intervalScore(first.lower, first.upper, 15.0, alpha),
          1e-15,
          "its interval score"
        )
      )
  }

  pureTest("the aggregates count what they say, and an empty backtest measures nothing") {
    val result = Backtest.run(cases, alpha)
    val empty = Backtest.run(List.empty[(Int, IArray[Double], Double)], alpha)
    // Dates 1 and 4 are inside their interval — the fourth exactly on its lower bound of 10.5 —
    // and 2 and 3 are outside, so the coverage is a half against the 90 % it aimed at.
    closeRel(result.coverage, 0.5, 1e-15, "two of four covered")
      .and(closeRel(result.coverageGap, 0.5 - 0.9, 1e-15, "the gap from the nominal coverage"))
      .and(
        closeRel(
          result.meanCrps,
          result.scored.map(_.crps).sum / 4.0,
          1e-15,
          "the mean score against the mean of the scores"
        )
      )
      .and(check(empty.coverage.isNaN, "an empty backtest reported a coverage"))
      .and(check(empty.meanCrps.isNaN, "an empty backtest reported a score"))
      .and(check(empty.meanBias.isNaN, "an empty backtest reported a bias"))
  }

  /** Independent scores: the block bootstrap must agree with the ordinary standard error. */
  pureTest("on independent dates the block bootstrap agrees with the ordinary standard error") {
    val n = 400
    val program = Monad[Rand].replicateA(n, Rng[Rand].normal.map(z => 2.0 + z))
    val values = program.runA(Seed(20261106L)).value
    val scored = values.zipWithIndex.map((v, i) => (i, IArray(v), v))
    // A forecast of one sample equal to the observation scores zero, so the scores are planted
    // directly instead: this tests the bootstrap, not the score.
    val result = BacktestResult(
      scored.map((i, _, v) => Scored(i, v, 0.5, 0.0, v, 0.0, 1.0, true)).toList,
      alpha
    )
    val replicates = result
      .blockBootstrap[Rand](_.crps, blockSize = 20, replications = 2000)
      .runA(Seed(11L))
      .value
    val mean =
      (0 until replicates.length).foldLeft(0.0)((s, i) => s + replicates(i)) / replicates.length
    val bootstrapSd = math.sqrt(
      (0 until replicates.length).foldLeft(0.0)((s, i) => s + math.pow(replicates(i) - mean, 2.0)) /
        (replicates.length - 1.0)
    )
    val sampleMean = values.sum / n
    val sampleSd = math.sqrt(values.map(v => math.pow(v - sampleMean, 2.0)).sum / (n - 1.0))
    val ordinary = sampleSd / math.sqrt(n.toDouble)
    // The two estimate the same quantity here, and the block bootstrap at 2000 replications is
    // itself noisy, so the claim is a quarter either way rather than a tight tolerance.
    check(
      bootstrapSd > 0.75 * ordinary && bootstrapSd < 1.25 * ordinary,
      f"the block bootstrap gives $bootstrapSd%.5f against the ordinary $ordinary%.5f"
    )
      .and(check(replicates.length == 2000, s"${replicates.length} replications"))
      .and(
        closeRel(mean, sampleMean, 2e-2, "the bootstrap's own mean against the sample mean")
      )
  }

  /** Correlated scores: the ordinary error is wrong, and the block bootstrap must say so. */
  pureTest("on correlated dates the block bootstrap is larger, which is the reason for blocks") {
    val n = 400
    val phi = 0.9
    // An AR(1) run of scores: the true standard error of the mean is inflated by about
    // sqrt((1 + phi)/(1 - phi)) = 4.36 over the independent one, and nothing that resamples dates
    // one at a time can see it.
    val program = Monad[Rand]
      .replicateA(n, Rng[Rand].normal)
      .map(noise =>
        noise
          .foldLeft((Vector.empty[Double], 0.0)) { case ((collected, state), e) =>
            val next = phi * state + e
            (collected :+ (2.0 + next), next)
          }
          ._1
      )
    val values = program.runA(Seed(7L)).value
    val result = BacktestResult(
      values.zipWithIndex.map((v, i) => Scored(i, v, 0.5, 0.0, v, 0.0, 1.0, true)).toList,
      alpha
    )
    def sdOf(blockSize: Int): Double =
      val replicates = result
        .blockBootstrap[Rand](_.crps, blockSize, replications = 2000)
        .runA(Seed(13L))
        .value
      val mean =
        (0 until replicates.length).foldLeft(0.0)((s, i) => s + replicates(i)) / replicates.length
      math.sqrt(
        (0 until replicates.length).foldLeft(0.0)((s, i) =>
          s + math.pow(replicates(i) - mean, 2.0)
        ) /
          (replicates.length - 1.0)
      )
    val sampleMean = values.sum / n
    val sampleSd = math.sqrt(values.map(v => math.pow(v - sampleMean, 2.0)).sum / (n - 1.0))
    val ordinary = sampleSd / math.sqrt(n.toDouble)
    val inflation = math.sqrt((1.0 + phi) / (1.0 - phi))
    val blocked = sdOf(40)
    val singles = sdOf(1)
    check(
      blocked > 1.5 * ordinary,
      f"with blocks of 40 the bootstrap gives $blocked%.5f against the ordinary $ordinary%.5f, " +
        f"where the true inflation is about $inflation%.2f"
    )
      // Blocks of one are the ordinary bootstrap, and must reproduce its blindness: that is what
      // shows the difference comes from the blocks and not from the resampling.
      .and(
        check(
          singles < 1.25 * ordinary,
          f"blocks of one gave $singles%.5f, which already sees the correlation"
        )
      )
      .and(check(blocked > 1.5 * singles, f"blocks of 40 gave $blocked%.5f against $singles%.5f"))
  }

  pureTest("the bootstrap is a function of its seed, and refuses what it cannot resample") {
    val result = Backtest.run(cases, alpha)
    val once = result.blockBootstrap[Rand](_.crps, 2, 50).runA(Seed(3L)).value
    val again = result.blockBootstrap[Rand](_.crps, 2, 50).runA(Seed(3L)).value
    val other = result.blockBootstrap[Rand](_.crps, 2, 50).runA(Seed(4L)).value
    val empty = Backtest.run(List.empty[(Int, IArray[Double], Double)], alpha)
    check(
      (0 until 50).forall(i => once(i) == again(i)),
      "the same seed gave different replications"
    )
      .and(check((0 until 50).exists(i => once(i) != other(i)), "a different seed gave the same"))
      .and(
        check(
          empty.blockBootstrap[Rand](_.crps, 2, 10).runA(Seed(1L)).value.isEmpty,
          "nothing resampled into something"
        )
      )
      .and(
        check(
          result.blockBootstrap[Rand](_.crps, 0, 10).runA(Seed(1L)).value.isEmpty,
          "a block of nothing"
        )
      )
      .and(
        check(
          result.blockBootstrap[Rand](_.crps, 2, 0).runA(Seed(1L)).value.isEmpty,
          "no replications gave some"
        )
      )
      // A block longer than the run is the whole run, once: every replicate is then the same.
      .and(
        check(
          result
            .blockBootstrap[Rand](_.crps, 99, 20)
            .runA(Seed(1L))
            .value
            .toList
            .distinct
            .length == 1,
          "a block longer than the run gave more than one answer"
        )
      )
  }
