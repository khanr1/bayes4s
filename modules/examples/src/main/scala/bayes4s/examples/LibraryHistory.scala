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

package bayes4s.examples

import bayes4s.BayesError
import bayes4s.DelayCountData
import bayes4s.ReportingTriangle

/** The invented world, arranged the way a count model reads it.
  *
  * The world is a list of applications; a count model wants a grid — group, month of arrival,
  * processing delay — with the cells nobody has heard about yet masked off. This is that
  * rearrangement, and nothing more: no statistics, no fit, no assumption. The manual's chapters 7,
  * 8 and 11 all start from it, so they are all set in the same place and a reader who follows them
  * in order is never asked to learn a new world.
  *
  * The city is the group. `asOf` is the month the observer is standing in: a cell is visible once
  * its application was processed, which is `month + delay < asOf`.
  */
final case class LibraryHistory(
    months: Int,
    maxLag: Int,
    asOf: Int,
    t: IArray[Double],
    data: DelayCountData,
    world: World
):

  def cities: Int = City.values.length

  /** Applications per city and month that had been processed by `asOf`, indexed `[city][month]`. */
  val visibleTotals: IArray[IArray[Double]] =
    IArray.tabulate(cities)(c =>
      IArray.tabulate(months)(m =>
        (0 to maxLag)
          .foldLeft(0.0)((sum, k) =>
            if data.visible.isVisible(m, k) then sum + data.counts(c)(m)(k) else sum
          )
      )
    )

  /** The world's own delay distribution over `0 .. maxLag`, as shares that sum to one.
    *
    * Taken from the applications themselves rather than from the settings that generated them: it
    * is the empirical delay, which is what an exposure calculation is supposed to use.
    */
  val delayShares: IArray[Double] =
    val inside = world.applications.filter(_.delay <= maxLag)
    val total = inside.length.toDouble
    if total == 0.0 then IArray.fill(maxLag + 1)(0.0)
    else IArray.tabulate(maxLag + 1)(k => inside.count(_.delay == k).toDouble / total)

  /** The share of each month's applications that could have been processed by `asOf`. */
  def exposure: IArray[Double] = data.visible.exposure(delayShares)

  /** The visible counts of one city, scaled up by what the exposure says is still to come. */
  def correctedCounts(city: City, minimumVisibility: Double): IArray[Double] =
    data.visible.correctedCounts(visibleTotals(city.ordinal), delayShares, minimumVisibility)

  /** Books returned per month: one event per loan, placed at the month it came back in.
    *
    * A loan starts when the application is processed and ends `returned` months later, so a
    * return is a derived quantity of an application that lands at an offset from it — the shape
    * `Contribution` exists for, seen here in the data rather than in a forecast.
    */
  val returnsByMonth: IArray[Int] =
    val placed = world.applications
      .filter(_.month < months)
      .flatMap(a => a.loans.toList.map(loan => a.processed + loan.returned))
      .filter(m => m >= 0 && m < months)
    val byMonth = placed.groupMapReduce(identity)(_ => 1)(_ + _)
    IArray.tabulate(months)(m => byMonth.getOrElse(m, 0))

object LibraryHistory:

  /** The window the manual's chapters use: three years of history, seen from its last month.
    *
    * Thirty-six months is long enough for a trend to be visible and short enough to fit in
    * seconds; a maximum delay of five months leaves the last few months visibly incomplete, which
    * is the thing a nowcast is for.
    */
  def of(
      months: Int = 36,
      maxLag: Int = 5,
      asOf: Int = 36,
      world: World = FictionalWorld.standard
  ): Either[BayesError, LibraryHistory] =
    val window = math.min(math.max(months, 1), world.months)
    val lags = math.max(maxLag, 0)
    // Centred and scaled, which is what keeps a drift's coefficient on a readable scale.
    val t = IArray.tabulate(window)(m => (m - (window - 1) / 2.0) / window)
    val triangle = world.triangle(lags)
    val counts = IArray.tabulate(City.values.length)(c =>
      IArray.tabulate(window)(m => IArray.tabulate(lags + 1)(k => triangle(c)(m)(k)))
    )
    val visible = ReportingTriangle.rightCensored(window, lags + 1, math.min(asOf, window))
    DelayCountData
      .of(t, counts, visible)
      .map(data => LibraryHistory(window, lags, math.min(asOf, window), t, data, world))
