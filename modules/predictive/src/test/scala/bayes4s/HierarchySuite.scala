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
import bayes4s.testkit.MonteCarlo
import cats.Monad
import weaver.SimpleIOSuite

/** A group nobody has seen, against the closed form.
  *
  * The normal-normal case is the one where the answer is a formula: if the level is
  * `Gaussian(m, s)` and a group is `Gaussian(level, t)`, then marginally the group is
  * `Gaussian(m, sqrt(s² + t²))`. That is what makes this testable rather than plausible, and the
  * variance is the half that a prediction from the posterior mean would get wrong.
  */
object HierarchySuite extends SimpleIOSuite with MonteCarlo with Approx:

  // Forty thousand rather than more: the thresholds below are standard errors, so they tighten as
  // the square root of this, and `replicateA` on `State` builds a chain of exactly this length.
  private val samples = 40_000

  pureTest("a new group's effect has the variance the closed form gives") {
    val level = 1.5
    val levelSd = 0.8
    val groupSd = 0.6
    // Samples of the hyperparameters: the level varies between samples, the scale does not, which
    // is the case the closed form covers.
    val levels = IArray.from(
      Monad[Rand]
        .replicateA(samples, Rng[Rand].normal)
        .runA(Seed(20261105L))
        .value
        .map(z => level + levelSd * z)
    )
    val scales = IArray.fill(samples)(groupSd)
    val drawn = Hierarchy.newGroup[Rand](levels, scales).runA(Seed(7L)).value
    val values = (0 until drawn.length).map(drawn.apply).toList
    val mean = values.sum / values.length
    val variance = values.map(x => (x - mean) * (x - mean)).sum / (values.length - 1)
    val truth = levelSd * levelSd + groupSd * groupSd
    withinStandardErrors(
      mean,
      level,
      math.sqrt(truth / samples),
      4.0,
      "the mean of a new group's effect"
    )
      .and(
        withinStandardErrors(
          variance,
          truth,
          truth * math.sqrt(2.0 / samples),
          4.0,
          "its variance, which is the level's plus the group's"
        )
      )
      // The mistake this exists to avoid: predicting from the level's posterior mean would give a
      // variance of the group's alone.
      .and(
        check(
          variance > 1.5 * groupSd * groupSd,
          s"the variance is $variance, barely more than the group's own ${groupSd * groupSd}"
        )
      )
  }

  pureTest("the level and the scale stay paired, sample by sample") {
    // Half the samples say the scale is tiny and half say it is large. Pairing them correctly gives
    // a mixture of two variances; crossing them would give the average scale everywhere.
    val levels = IArray.fill(samples)(0.0)
    val scales = IArray.tabulate(samples)(s => if s % 2 == 0 then 0.1 else 3.0)
    val drawn = Hierarchy.newGroup[Rand](levels, scales).runA(Seed(11L)).value
    val even = (0 until samples by 2).map(drawn.apply).toList
    val odd = (1 until samples by 2).map(drawn.apply).toList
    def spread(xs: List[Double]): Double =
      val m = xs.sum / xs.length
      math.sqrt(xs.map(x => (x - m) * (x - m)).sum / (xs.length - 1))
    val small = spread(even)
    val large = spread(odd)
    withinStandardErrors(small, 0.1, 0.1 / math.sqrt(2.0 * even.length), 4.0, "the tight half")
      .and(
        withinStandardErrors(large, 3.0, 3.0 / math.sqrt(2.0 * odd.length), 4.0, "the wide half")
      )
      .and(check(large / small > 20.0, s"the two halves have spreads $small and $large"))
  }

  /** The correlated case: the covariance of a new group's two effects is `diag(tau) L L' diag(tau)`. */
  pureTest("a correlated new group has the covariance its level implies") {
    val rho = 0.6
    val taus = IArray(0.8, 0.4)
    val rows = IArray(IArray(1.0, 0.0), IArray(rho, math.sqrt(1.0 - rho * rho)))
    Matrix.ofRows(rows).flatMap(Cholesky.ofLower) match
      case Left(error) => failure(error.message)
      case Right(factor) =>
        val levels = IArray.fill(samples)(IArray(1.0, -0.5))
        val scales = IArray.fill(samples)(taus)
        val factors = IArray.fill(samples)(factor)
        val drawn =
          Hierarchy.newGroupCorrelated[Rand](levels, scales, factors).runA(Seed(13L)).value
        val first = (0 until samples).map(s => drawn(s)(0)).toList
        val second = (0 until samples).map(s => drawn(s)(1)).toList
        val m0 = first.sum / samples
        val m1 = second.sum / samples
        val v0 = first.map(x => (x - m0) * (x - m0)).sum / (samples - 1)
        val v1 = second.map(x => (x - m1) * (x - m1)).sum / (samples - 1)
        val covariance = first.zip(second).map((a, b) => (a - m0) * (b - m1)).sum / samples
        val expected0 = taus(0) * taus(0)
        val expected1 = taus(1) * taus(1)
        val expectedCovariance = rho * taus(0) * taus(1)
        withinStandardErrors(m0, 1.0, math.sqrt(expected0 / samples), 4.0, "the first mean")
          .and(withinStandardErrors(m1, -0.5, math.sqrt(expected1 / samples), 4.0, "the second"))
          .and(
            withinStandardErrors(
              v0,
              expected0,
              expected0 * math.sqrt(2.0 / samples),
              4.0,
              "the first variance"
            )
          )
          .and(
            withinStandardErrors(
              v1,
              expected1,
              expected1 * math.sqrt(2.0 / samples),
              4.0,
              "the second variance"
            )
          )
          .and(
            withinStandardErrors(
              covariance,
              expectedCovariance,
              math
                .sqrt((expected0 * expected1 + expectedCovariance * expectedCovariance) / samples),
              4.0,
              "the covariance, which is where the correlation shows"
            )
          )
  }

  pureTest("mismatched lengths give nothing rather than a wrong answer") {
    val short = Hierarchy.newGroup[Rand](IArray(1.0, 2.0), IArray(1.0)).runA(Seed(1L)).value
    val none = Hierarchy
      .newGroupCorrelated[Rand](IArray.empty, IArray.empty, IArray.empty)
      .runA(Seed(1L))
      .value
    check(short.isEmpty, s"mismatched lengths gave ${short.length} effects")
      .and(check(none.isEmpty, "no samples gave effects"))
  }
