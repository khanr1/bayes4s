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

/** Counts that arrive late, and what can be said about them before they have all arrived.
  *
  * An event created in one period is reported in that period or a later one, with a delay drawn
  * from a distribution. Thinning a Poisson stream that way gives one independent Poisson count per
  * (creation period, delay) cell, with mean `rate × share`, and that is the first thing asserted.
  *
  * What is then **observed** is only the part of that grid the data has reached: a cell is visible
  * if its reporting period is in the past and at or after the start of the available history. The
  * triangle carries that as an arbitrary mask, with those two cases as constructors, and the
  * exposure it implies has a closed form — `F(c - 1 - m)` per creation period — which the mask has
  * to reproduce. Everything about correcting counts for what has not arrived rests on that.
  */
object TriangleSuite extends SimpleIOSuite with Approx with MonteCarlo:

  /** A delay distribution: most within two periods, a tail out to five. */
  private val shares = IArray(0.4, 0.3, 0.15, 0.1, 0.05)
  private val rates = IArray(100.0, 120.0, 80.0, 150.0)

  pureTest("thinning gives each cell the mean the rate and the share imply") {
    val replications = 4000
    val means = PoissonThinning.means(rates, shares, firstPeriodFactor = 1.0)
    val simulated = IArray.tabulate(replications) { i =>
      PoissonThinning
        .simulate[Rand](rates, shares, firstPeriodFactor = 1.0)
        .runA(Seed(20261106L).fold(i.toLong))
        .value
    }
    val checks = for
      m <- (0 until rates.length).toList
      k <- (0 until shares.length).toList
    yield
      val cells = IArray.tabulate(replications)(i => simulated(i)(m)(k).toDouble)
      val mean = (0 until replications).foldLeft(0.0)((acc, i) => acc + cells(i)) / replications
      val expected = means(m)(k)
      // A Poisson count has variance equal to its mean, so the standard error of the average of
      // `replications` of them is sqrt(mean / replications) — no need to estimate it.
      val error = math.sqrt(expected / replications)
      withinStandardErrors(mean, expected, error, 4.0, s"cell ($m, $k)")
    checks.reduce(_.and(_))
  }

  pureTest("the means are the rate spread over the delays, and the first period can be scaled") {
    val plain = PoissonThinning.means(rates, shares, firstPeriodFactor = 1.0)
    val halved = PoissonThinning.means(rates, shares, firstPeriodFactor = 0.5)
    def rowSum(m: IArray[IArray[Double]], row: Int): Double =
      (0 until m(row).length).foldLeft(0.0)((acc, k) => acc + m(row)(k))
    check(plain.length == 4 && plain(0).length == 5, "the grid is the wrong shape")
      .and(closeRel(plain(0)(0), 100.0 * 0.4, 1e-15, "the first cell"))
      .and(closeRel(plain(3)(4), 150.0 * 0.05, 1e-15, "the last cell"))
      // The shares sum to one, so a row sums to its own rate.
      .and(closeRel(rowSum(plain, 1), 120.0, 1e-13, "a row of the grid"))
      // The factor applies to the first delay only: an event created halfway through a period has
      // half a period in which to be reported with no delay at all. Nothing else moves.
      .and(closeRel(halved(0)(0), 100.0 * 0.4 * 0.5, 1e-15, "the first cell, halved"))
      .and(closeRel(halved(0)(1), plain(0)(1), 1e-15, "the second cell is untouched"))
      .and(closeRel(rowSum(halved, 0), 100.0 * (1.0 - 0.2), 1e-13, "the row loses what it halved"))
      .and(
        check(
          PoissonThinning.means(rates, IArray.empty[Double], 1.0).forall(_.isEmpty),
          "no delays gave cells"
        )
      )
  }

  pureTest("thinned counts are Poisson: the variance of a cell equals its mean") {
    val replications = 20_000
    val one = IArray(60.0)
    val simulated = IArray.tabulate(replications) { i =>
      PoissonThinning.simulate[Rand](one, shares, 1.0).runA(Seed(7L).fold(i.toLong)).value(0)(0)
    }
    val cells = IArray.tabulate(replications)(i => simulated(i).toDouble)
    val mean = (0 until replications).foldLeft(0.0)((acc, i) => acc + cells(i)) / replications
    val variance =
      (0 until replications).foldLeft(0.0)((acc, i) => acc + math.pow(cells(i) - mean, 2.0)) /
        (replications - 1.0)
    val expected = 60.0 * 0.4
    // The variance of a Poisson variance estimate: mu(1 + 2mu)/n, from its fourth central moment.
    val varianceError = math.sqrt(expected * (1.0 + 2.0 * expected) / replications)
    withinStandardErrors(mean, expected, math.sqrt(expected / replications), 4.0, "the cell's mean")
      .and(withinStandardErrors(variance, expected, varianceError, 4.0, "the cell's variance"))
  }

  // --- the triangle -----------------------------------------------------------------------------

  pureTest("right censoring keeps exactly the cells whose reporting period is in the past") {
    // Four creation periods, five delays, and the data runs to the end of period 2 — so a cell is
    // visible when `m + k <= 2`, which is `c - 1` with `c = 3`.
    val triangle = ReportingTriangle.rightCensored(4, 5, current = 3)
    val expected = for
      m <- 0 until 4
      k <- 0 until 5
    yield (m, k, m + k <= 2)
    check(
      expected.forall((m, k, visible) => triangle.isVisible(m, k) == visible),
      "the censored mask is not m + k <= c - 1"
    )
      .and(check(triangle.visibleCells == 6, s"${triangle.visibleCells} visible cells"))
      // Nothing outside the grid is visible, whatever the formula would say.
      .and(
        check(!triangle.isVisible(-1, 0) && !triangle.isVisible(0, 5), "a cell outside is visible")
      )
  }

  pureTest("left truncation and censoring intersect to the triangle the data really covers") {
    // The history available starts at period 1: a cell reported in period 0 was never recorded,
    // even though it is in the past. Intersected with censoring at c = 3, the visible band is
    // `1 <= m + k <= 2` — built by hand here rather than taken from the library.
    val censored = ReportingTriangle.rightCensored(4, 5, current = 3)
    val truncated = ReportingTriangle.leftTruncated(4, 5, firstVisible = 1)
    val both = censored.intersect(truncated)
    val byHand = for
      m <- 0 until 4
      k <- 0 until 5
    yield (m, k, m + k >= 1 && m + k <= 2)
    both match
      case Left(error) => failure(error.message)
      case Right(band) =>
        check(
          byHand.forall((m, k, visible) => band.isVisible(m, k) == visible),
          "the intersection is not the band the two masks share"
        )
          .and(check(band.visibleCells == 5, s"${band.visibleCells} cells in the band"))
          // The cell (0, 0) is in the past and before the history: censoring keeps it, truncation
          // drops it, and the intersection has to drop it.
          .and(
            check(!band.isVisible(0, 0), "the cell before the history survived the intersection")
          )
          .and(check(censored.isVisible(0, 0), "censoring alone should keep it"))
          .and(
            check(
              censored.intersect(ReportingTriangle.rightCensored(3, 5, 3)).isLeft,
              "two triangles of different shapes intersected"
            )
          )
  }

  pureTest("an arbitrary mask is allowed, and the exposure follows it rather than a formula") {
    // A hole in the middle: one creation period whose reporting was lost. Nothing in the library
    // assumes the visible set is a triangle, and this is what checks it.
    val holed = ReportingTriangle.of(4, 5, (m, k) => m + k <= 2 && m != 1)
    val exposure = holed.exposure(shares)
    check(exposure.length == 4, s"${exposure.length} exposures")
      .and(closeRel(exposure(0), 0.4 + 0.3 + 0.15, 1e-15, "the first period sees three delays"))
      .and(check(exposure(1) == 0.0, s"the holed period has exposure ${exposure(1)}"))
      .and(closeRel(exposure(2), 0.4, 1e-15, "the third period sees one delay"))
      .and(check(exposure(3) == 0.0, s"the last period sees nothing, not ${exposure(3)}"))
  }

  pureTest("the censored triangle's exposure is the closed form, period by period and in total") {
    val current = 3
    val triangle = ReportingTriangle.rightCensored(4, 5, current)
    val exposure = triangle.exposure(shares)
    // F is the delay distribution's cumulative function, and the exposure of creation period m is
    // F(c - 1 - m): the share of its events that could have been reported by now.
    def cdf(upTo: Int): Double =
      if upTo < 0 then 0.0
      else (0 to math.min(upTo, shares.length - 1)).foldLeft(0.0)((acc, k) => acc + shares(k))
    val closedForm = IArray.tabulate(4)(m => cdf(current - 1 - m))
    val total = (0 until 4).foldLeft(0.0)((acc, m) => acc + closedForm(m))
    (0 until 4)
      .map(m => closeRel(exposure(m), closedForm(m), 1e-14, s"the exposure of period $m"))
      .reduce(_.and(_))
      .and(closeRel(triangle.totalExposure(shares), total, 1e-14, "the total exposure"))
      // The oldest period is fully reported only once the whole delay distribution has run; here
      // it has not, so no period has an exposure of one.
      .and(
        check(exposure.forall(_ < 1.0), s"a period is fully exposed: ${exposure.mkString(", ")}")
      )
      .and(closeRel(exposure(0), 0.85, 1e-14, "the first period's exposure"))
      .and(check(exposure(3) == 0.0, "the newest period has exposure"))
  }

  pureTest("corrected counts divide by the exposure, and refuse to when it is too small") {
    val triangle = ReportingTriangle.rightCensored(4, 5, current = 3)
    val observed = IArray(85.0, 42.0, 20.0, 0.0)
    val exposure = triangle.exposure(shares)
    val corrected = triangle.correctedCounts(observed, shares, minimumVisibility = 0.3)
    check(corrected.length == 4, s"${corrected.length} corrected counts")
      .and(closeRel(corrected(0), 85.0 / 0.85, 1e-14, "the first period, 85 % visible"))
      .and(closeRel(corrected(1), 42.0 / 0.7, 1e-14, "the second period, 70 % visible"))
      .and(closeRel(corrected(2), 20.0 / 0.4, 1e-14, "the third period, 40 % visible"))
      // The newest period has seen nothing of its events. Dividing by an exposure of zero — or of
      // 0.05 — turns one late report into an enormous estimate, which is the mistake the threshold
      // exists to prevent: below it the answer is NaN, not a number nobody should use.
      .and(check(corrected(3).isNaN, s"the unexposed period was corrected to ${corrected(3)}"))
      .and(
        check(
          triangle.correctedCounts(observed, shares, 0.75)(1).isNaN,
          "a period below the threshold was corrected anyway"
        )
      )
      .and(
        check(
          exposure(2) > 0.0 && triangle.correctedCounts(observed, shares, 0.0)(3).isNaN,
          "a threshold of zero still cannot divide by an exposure of zero"
        )
      )
      // A correction never shrinks a count: it is an estimate of what the count will become.
      .and(
        check(
          (0 until 3).forall(m => corrected(m) >= observed(m)),
          "a corrected count came out below what was observed"
        )
      )
  }
