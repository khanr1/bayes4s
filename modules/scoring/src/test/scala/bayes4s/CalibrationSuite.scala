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

/** Whether a forecast's **shape** is right, which the score alone does not say.
  *
  * A forecast can score well on average and still be systematically too wide, too narrow or
  * shifted. The probability integral transform catches all three: where the observation falls
  * inside the forecast, period after period, has to be uniform. Too narrow and the values pile at
  * the ends; too wide and they pile in the middle; shifted and they pile at one end.
  *
  * The interval score is checked against a second formula rather than against itself: the Winkler
  * score of a central interval is `2/alpha` times the sum of the two pinball losses at its ends,
  * which is an identity worth asserting because it is the bridge between interval reporting and
  * quantile scoring.
  */
object CalibrationSuite extends SimpleIOSuite with Approx:

  pureTest("the transform says where the observation falls among the samples") {
    val forecast = IArray(1.0, 2.0, 3.0, 4.0)
    closeRel(Calibration.pit(forecast, 5.0), 1.0, 1e-15, "an observation above every sample")
      .and(closeRel(Calibration.pit(forecast, 0.0), 0.0, 1e-15, "below every sample"))
      .and(closeRel(Calibration.pit(forecast, 2.5), 0.5, 1e-15, "in the middle"))
      // A tie is counted as half, which is the expectation of the randomised transform and what
      // keeps a discrete forecast from piling every observation at a bin edge.
      .and(closeRel(Calibration.pit(forecast, 2.0), 0.375, 1e-15, "exactly on a sample"))
      .and(
        closeRel(
          Calibration.pit(IArray(1.0, 1.0, 1.0, 1.0), 1.0),
          0.5,
          1e-15,
          "on every sample at once"
        )
      )
      .and(
        check(Calibration.pit(IArray.empty[Double], 1.0).isNaN, "an empty forecast has a transform")
      )
  }

  pureTest("the bias runs from under-prediction to over-prediction") {
    // The convention is scoringutils': `1 - 2 pit`, so +1 is a forecast entirely above what
    // happened and -1 entirely below. Getting the sign backwards is the easy mistake and it is
    // silent, so both ends are pinned.
    val forecast = IArray(1.0, 2.0, 3.0, 4.0)
    closeRel(Calibration.bias(forecast, 0.0), 1.0, 1e-15, "a forecast entirely above the outcome")
      .and(closeRel(Calibration.bias(forecast, 5.0), -1.0, 1e-15, "entirely below the outcome"))
      .and(closeRel(Calibration.bias(forecast, 2.5), 0.0, 1e-15, "centred on the outcome"))
      .and(check(Calibration.bias(IArray.empty[Double], 1.0).isNaN, "an empty forecast has a bias"))
  }

  pureTest("the interval score pays for width, and much more for missing") {
    val alpha = 0.1
    val lower = 10.0
    val upper = 20.0
    val inside = Calibration.intervalScore(lower, upper, 15.0, alpha)
    val below = Calibration.intervalScore(lower, upper, 8.0, alpha)
    val above = Calibration.intervalScore(lower, upper, 23.0, alpha)
    // Inside: the width alone. Below by 2 at alpha = 0.1: the width plus 2/0.1 times 2, so 50.
    closeRel(inside, 10.0, 1e-15, "an observation inside the interval")
      .and(closeRel(below, 10.0 + 20.0 * 2.0, 1e-15, "an observation two below"))
      .and(closeRel(above, 10.0 + 20.0 * 3.0, 1e-15, "an observation three above"))
      .and(check(inside < below && below < above, "the ordering is wrong"))
      // A narrower interval that still contains the outcome scores better: the score rewards
      // sharpness subject to being right.
      .and(
        check(
          Calibration.intervalScore(14.0, 16.0, 15.0, alpha) < inside,
          "a sharper interval that was right did not score better"
        )
      )
      .and(
        check(
          Calibration.intervalScore(20.0, 10.0, 15.0, alpha).isNaN,
          "an inverted interval scored"
        )
      )
      .and(check(Calibration.intervalScore(10.0, 20.0, 15.0, 0.0).isNaN, "an alpha of zero scored"))
      .and(
        check(Calibration.intervalScore(10.0, 20.0, 15.0, 1.5).isNaN, "an alpha above one scored")
      )
  }

  pureTest(
    "the interval score is the two pinball losses, which is the bridge to quantile scoring"
  ) {
    // Winkler's interval score at level alpha equals `2/alpha` times the sum of the pinball losses
    // at the quantile levels alpha/2 and 1 - alpha/2. The pinball loss is written here, by hand,
    // from its own definition, so the two sides share no code.
    def pinball(tau: Double, quantile: Double, observation: Double): Double =
      if observation >= quantile then (observation - quantile) * tau
      else (quantile - observation) * (1.0 - tau)
    val cases = for
      alpha <- List(0.05, 0.1, 0.5)
      observation <- List(-5.0, 9.9, 10.0, 15.0, 20.0, 20.1, 40.0)
    yield (alpha, observation)
    val checks = cases.map { (alpha, observation) =>
      val lower = 10.0
      val upper = 20.0
      val fromPinball =
        (2.0 / alpha) *
          (pinball(alpha / 2.0, lower, observation) + pinball(
            1.0 - alpha / 2.0,
            upper,
            observation
          ))
      closeRel(
        Calibration.intervalScore(lower, upper, observation, alpha),
        fromPinball,
        1e-13,
        f"alpha $alpha%.2f, observation $observation%.1f"
      )
    }
    checks.reduce(_.and(_))
  }

  pureTest("coverage counts the observations inside their own intervals, boundaries included") {
    val lower = IArray(0.0, 10.0, 20.0, 30.0)
    val upper = IArray(5.0, 15.0, 25.0, 35.0)
    val observations = IArray(2.0, 20.0, 25.0, 1.0)
    // Two of four: the second is above its interval, the fourth below. The third sits exactly on
    // its upper bound and counts, which is a choice and therefore stated.
    closeRel(Calibration.coverage(lower, upper, observations), 0.5, 1e-15, "two of four covered")
      .and(
        closeRel(
          Calibration.coverage(IArray(0.0), IArray(1.0), IArray(1.0)),
          1.0,
          1e-15,
          "an observation exactly on the boundary"
        )
      )
      .and(
        check(Calibration.coverage(IArray(0.0), IArray(1.0), IArray(2.0)) == 0.0, "outside counted")
      )
      .and(
        check(
          Calibration.coverage(IArray(0.0, 1.0), IArray(1.0), IArray(2.0)).isNaN,
          "mismatched lengths gave a coverage"
        )
      )
      .and(
        check(
          Calibration.coverage(IArray.empty, IArray.empty, IArray.empty).isNaN,
          "nothing was covered"
        )
      )
  }

  pureTest(
    "a calibrated forecast gives a flat histogram of transforms, and a shifted one does not"
  ) {
    // Two hundred periods, each with a forecast of a hundred samples. When the observation comes
    // from the same distribution as the forecast, the transforms are uniform; when it is shifted
    // by a standard deviation, they are not. The threshold is the 0.1 % point of chi-squared with
    // 9 degrees of freedom, 27.877, for 10 bins — announced here, as 6.5 requires.
    val periods = 200
    val samples = 100
    val bins = 10
    val critical = 27.877
    def transforms(shift: Double): IArray[Double] =
      IArray.tabulate(periods) { t =>
        val program = for
          forecast <- Monad[Rand].replicateA(samples, Rng[Rand].normal)
          outcome <- Rng[Rand].normal
        yield (IArray.from(forecast), outcome + shift)
        val (forecast, outcome) = program.runA(Seed(20261106L).fold(t.toLong)).value
        Calibration.pit(forecast, outcome)
      }
    val calibrated = Calibration.pitUniformity(transforms(0.0), bins)
    val shifted = Calibration.pitUniformity(transforms(1.0), bins)
    val histogram = Calibration.pitHistogram(transforms(0.0), bins)
    check(
      calibrated <= critical,
      f"a calibrated forecast gives chi-squared $calibrated%.2f over $bins bins, past $critical"
    )
      .and(
        check(
          shifted > critical,
          f"a forecast shifted by a standard deviation gives only $shifted%.2f, under $critical"
        )
      )
      .and(check(histogram.sum == periods, s"the histogram holds ${histogram.sum} of $periods"))
      .and(check(histogram.length == bins, s"${histogram.length} bins"))
      // A transform of exactly 1 belongs in the last bin, not in a bin of its own past the end.
      .and(
        check(
          Calibration.pitHistogram(IArray(0.0, 1.0), 4).toList == List(1, 0, 0, 1),
          s"the ends land in ${Calibration.pitHistogram(IArray(0.0, 1.0), 4).toList}"
        )
      )
      .and(check(Calibration.pitUniformity(IArray(0.5), 0).isNaN, "no bins gave a statistic"))
  }
