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
import java.time.YearMonth
import weaver.SimpleIOSuite

/** The two ways a simulated future moves away from the present: an AR(1) state that is projected
  * forward, and a trend that scales an amount by how far into the future it falls.
  *
  * Both have closed forms, so both are tested against them rather than against a fixture. The
  * AR(1) projection has a mean and a variance at every horizon, and the trend is one line of
  * arithmetic whose only real question is which draw gets paired with which amount.
  */
object TrendSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val futures = 20_000

  /** `futures` independent projections, each from its own derived seed. */
  private def projections(phi: Double, tau: Double, zLast: Double, horizon: Int) =
    IArray.tabulate(futures)(i =>
      Ar1.project(phi, tau, zLast, horizon, Seed(20261106L).fold(i.toLong))
    )

  private def momentsAt(paths: IArray[IArray[Double]], step: Int): (Double, Double) =
    val n = paths.length
    val mean = (0 until n).foldLeft(0.0)((acc, i) => acc + paths(i)(step)) / n
    val variance =
      (0 until n).foldLeft(0.0)((acc, i) => acc + math.pow(paths(i)(step) - mean, 2.0)) / (n - 1.0)
    (mean, variance)

  pureTest("the projected state has the mean and the variance the recursion implies") {
    val phi = 0.8
    val tau = 0.5
    val zLast = 2.0
    val horizon = 6
    val paths = projections(phi, tau, zLast, horizon)
    val checks = (0 until horizon).map { step =>
      val h = step + 1
      val (mean, variance) = momentsAt(paths, step)
      // E[z_h] = phi^h z_last, Var[z_h] = tau² (1 - phi^(2h)) / (1 - phi²).
      val expectedMean = math.pow(phi, h.toDouble) * zLast
      val expectedVariance =
        tau * tau * (1.0 - math.pow(phi, 2.0 * h)) / (1.0 - phi * phi)
      val meanError = math.sqrt(expectedVariance / futures)
      // The standard error of a variance of a Gaussian is sd² sqrt(2/(n-1)).
      val varianceError = expectedVariance * math.sqrt(2.0 / (futures - 1.0))
      withinStandardErrors(mean, expectedMean, meanError, 4.0, s"the mean at horizon $h")
        .and(
          withinStandardErrors(
            variance,
            expectedVariance,
            varianceError,
            4.0,
            s"the variance at horizon $h"
          )
        )
    }
    checks.reduce(_.and(_))
  }

  pureTest("the variance approaches the stationary one, and never passes it") {
    val phi = 0.7
    val tau = 0.4
    val stationary = tau * tau / (1.0 - phi * phi)
    val paths = projections(phi, tau, 0.0, 40)
    val (_, far) = momentsAt(paths, 39)
    val (_, near) = momentsAt(paths, 0)
    val error = stationary * math.sqrt(2.0 / (futures - 1.0))
    withinStandardErrors(far, stationary, error, 4.0, "the variance at horizon 40")
      .and(closeRel(near, tau * tau, 2e-2, "the variance of the first step is tau squared"))
      .and(check(near < far, s"the variance did not grow: $near then $far"))
  }

  pureTest("a projection with no noise is the deterministic decay, exactly") {
    val path = Ar1.project(0.9, 0.0, 3.0, 4, Seed(1L))
    val expected = IArray.tabulate(4)(h => 3.0 * math.pow(0.9, (h + 1).toDouble))
    val zeroPhi = Ar1.project(0.0, 1.0, 100.0, 3, Seed(2L))
    check(path.length == 4, s"${path.length} steps")
      .and(
        (0 until 4)
          .map(h => closeRel(path(h), expected(h), 1e-15, s"step $h with no noise"))
          .reduce(_.and(_))
      )
      // With phi = 0 the state forgets where it was: the first step is already pure noise, and
      // nothing of the 100 it started from survives.
      .and(
        check(
          math.abs(zeroPhi(0)) < 10.0,
          s"with phi = 0 the first step is ${zeroPhi(0)}, which remembers the 100"
        )
      )
      .and(check(Ar1.project(0.5, 1.0, 0.0, 0, Seed(3L)).isEmpty, "a horizon of 0 gave steps"))
      .and(check(Ar1.project(0.5, 1.0, 0.0, -2, Seed(3L)).isEmpty, "a negative horizon gave steps"))
  }

  pureTest("the same seed projects the same path, a different one does not") {
    val once = Ar1.project(0.6, 0.3, 1.0, 5, Seed(77L))
    val again = Ar1.project(0.6, 0.3, 1.0, 5, Seed(77L))
    val other = Ar1.project(0.6, 0.3, 1.0, 5, Seed(78L))
    check((0 until 5).forall(h => once(h) == again(h)), "the same seed gave a different path")
      .and(
        check((0 until 5).exists(h => once(h) != other(h)), "a different seed gave the same path")
      )
  }

  pureTest("a projection is total: nonsense in, NaN out, and no exception") {
    val explosive = Ar1.project(1.5, 0.1, 1.0, 3, Seed(4L))
    val nan = Ar1.project(Double.NaN, 0.1, 1.0, 2, Seed(4L))
    val negativeScale = Ar1.project(0.5, -1.0, 1.0, 2, Seed(4L))
    check(explosive.forall(_.isFinite), s"|phi| > 1 gave ${explosive.mkString(", ")}")
      .and(check(nan.forall(_.isNaN), s"a NaN phi gave ${nan.mkString(", ")}"))
      // A negative scale is not an error: it mirrors the noise, and the state is still finite.
      .and(check(negativeScale.forall(_.isFinite), "a negative scale gave something unfinite"))
      .and(
        check(
          math.abs(explosive(2)) > math.abs(explosive(0)) * 0.5,
          "an explosive projection is documented to explode, and did not"
        )
      )
  }

  // --- the trend --------------------------------------------------------------------------------

  pureTest("a trend scales an amount by the time to the period, and by nothing else") {
    val kappa = 0.2
    closeRel(TrendScaling.scaled(100.0, kappa, 0.0), 100.0, 1e-15, "no time, no scaling")
      .and(closeRel(TrendScaling.scaled(100.0, 0.0, 3.0), 100.0, 1e-15, "no trend, no scaling"))
      .and(
        closeRel(
          TrendScaling.scaled(100.0, kappa, 2.5),
          100.0 * math.exp(0.2 * 2.5),
          1e-15,
          "the trend over two and a half years"
        )
      )
      // A trend of kappa per year compounds: two years is the square of one.
      .and(
        closeRel(
          TrendScaling.scaled(1.0, kappa, 2.0),
          math.pow(TrendScaling.scaled(1.0, kappa, 1.0), 2.0),
          1e-14,
          "two years against one year squared"
        )
      )
      .and(check(TrendScaling.scaled(1.0, Double.NaN, 1.0).isNaN, "a NaN trend gave a number"))
  }

  pureTest("the time to a period is counted in whole months, as years") {
    val from = YearMonth.of(2026, 1)
    closeRel(TrendScaling.yearsBetween(from, YearMonth.of(2027, 1)), 1.0, 1e-15, "one year")
      .and(
        closeRel(TrendScaling.yearsBetween(from, YearMonth.of(2026, 7)), 0.5, 1e-15, "six months")
      )
      .and(closeRel(TrendScaling.yearsBetween(from, from), 0.0, 1e-15, "no time at all"))
      // Backwards is negative, which is what a trend applied to the past has to mean.
      .and(
        closeRel(
          TrendScaling.yearsBetween(from, YearMonth.of(2025, 1)),
          -1.0,
          1e-15,
          "a year into the past"
        )
      )
  }

  pureTest("draws are paired with futures cyclically, and the pairing is documented") {
    // Fewer trend draws than futures is the ordinary case: the draws come from the fit and the
    // futures from the simulation, and the second is usually the larger. Pairing is cyclic, which
    // means future i uses draw i modulo the number of draws — stated here because any other
    // pairing (clamping to the last, random re-draw) would change the spread of the answer.
    val kappas = IArray(0.1, 0.2, 0.3)
    val chosen = (0 until 7).map(i => TrendScaling.at(kappas, i))
    check(
      chosen.toList == List(0.1, 0.2, 0.3, 0.1, 0.2, 0.3, 0.1),
      s"the pairing gave $chosen"
    )
      .and(
        check(TrendScaling.at(IArray.empty[Double], 0).isNaN, "an empty set of draws gave a number")
      )
      .and(check(TrendScaling.at(kappas, -1).isNaN, "a negative future index gave a number"))
  }
