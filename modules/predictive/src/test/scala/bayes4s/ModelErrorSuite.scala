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
import weaver.SimpleIOSuite

/** The model's own error: a Gaussian term on the log rate, for the part of the future no component
  * of the model claims to explain.
  *
  * One fact about it decides whether a forecast is centred where its author thinks: multiplying a
  * rate by `exp(eta)` leaves the **median** alone and raises the **mean** by `exp(sigma²/2)`. At a
  * sigma of 0.3 that is 4.6 % on every total, which is the kind of bias that is never noticed and
  * never forgiven, so it is asserted here rather than mentioned.
  */
object ModelErrorSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val replications = 40_000

  private def etas(sigma: Double): IArray[Double] =
    IArray.tabulate(replications)(i =>
      ModelError.draw[Rand](sigma).runA(Seed(20261106L).fold(i.toLong)).value
    )

  pureTest("the error term has the mean and the spread it was asked for") {
    val sigma = 0.3
    val drawn = etas(sigma)
    val mean = (0 until replications).foldLeft(0.0)((s, i) => s + drawn(i)) / replications
    val variance =
      (0 until replications).foldLeft(0.0)((s, i) => s + math.pow(drawn(i) - mean, 2.0)) /
        (replications - 1.0)
    withinStandardErrors(
      mean,
      0.0,
      sigma / math.sqrt(replications.toDouble),
      4.0,
      "the mean of eta"
    )
      .and(
        withinStandardErrors(
          variance,
          sigma * sigma,
          sigma * sigma * math.sqrt(2.0 / (replications - 1.0)),
          4.0,
          "the variance of eta"
        )
      )
      .and(
        check(ModelError.draw[Rand](0.0).runA(Seed(1L)).value == 0.0, "a sigma of zero gave noise")
      )
      .and(
        check(
          ModelError.draw[Rand](-1.0).runA(Seed(1L)).value.isNaN,
          "a negative sigma gave a number"
        )
      )
  }

  pureTest("the error leaves the median of a rate alone and raises its mean") {
    val sigma = 0.3
    val rate = 100.0
    val drawn = etas(sigma)
    val applied = IArray.tabulate(replications)(i => ModelError.applied(rate, drawn(i)))
    val mean = (0 until replications).foldLeft(0.0)((s, i) => s + applied(i)) / replications
    val median = Quantile.quantile(applied, 0.5)
    // A lognormal: mean rate·exp(sigma²/2), median rate.
    val expectedMean = rate * math.exp(sigma * sigma / 2.0)
    val lognormalVariance = rate * rate * math.exp(sigma * sigma) * (math.exp(sigma * sigma) - 1.0)
    withinStandardErrors(
      mean,
      expectedMean,
      math.sqrt(lognormalVariance / replications),
      4.0,
      "the mean of the perturbed rate"
    )
      // The median's standard error, from the density at the median: sqrt(pi/2)·sigma·rate/sqrt(n).
      .and(
        withinStandardErrors(
          median,
          rate,
          math.sqrt(math.Pi / 2.0) * sigma * rate / math.sqrt(replications.toDouble),
          4.0,
          "the median of the perturbed rate"
        )
      )
      // Which is to say: the inflation is real and about 4.6 % at this sigma.
      .and(closeRel(expectedMean / rate, 1.0460279, 1e-6, "the inflation factor at sigma = 0.3"))
      .and(closeRel(ModelError.applied(rate, 0.0), rate, 1e-15, "no error, no change"))
      .and(check(ModelError.applied(rate, Double.NaN).isNaN, "a NaN error gave a rate"))
  }

  pureTest("the same seed gives the same error, and the term is additive on the log scale") {
    val once = ModelError.draw[Rand](0.5).runA(Seed(9L)).value
    val again = ModelError.draw[Rand](0.5).runA(Seed(9L)).value
    val other = ModelError.draw[Rand](0.5).runA(Seed(10L)).value
    check(once == again, "the same seed gave a different error")
      .and(check(once != other, "a different seed gave the same error"))
      // Applying the term twice is applying their sum once: it lives on the log scale.
      .and(
        closeRel(
          ModelError.applied(ModelError.applied(50.0, 0.2), 0.3),
          ModelError.applied(50.0, 0.5),
          1e-14,
          "two errors against their sum"
        )
      )
  }
