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

import java.time.YearMonth

/** A quarter of a calendar year, 1 to 4.
  *
  * A label rather than an index, so it is numbered the way a calendar numbers it (2.9).
  */
final case class Quarter(year: Int, quarter: Int):
  override def toString: String = s"${year}Q$quarter"

object Quarter:
  def of(month: YearMonth): Quarter =
    Quarter(month.getYear, (month.getMonthValue - 1) / 3 + 1)

/** A contiguous run of whole months: the period axis every simulated future is indexed by.
  *
  * A period is addressed by its **offset** from the start of the run, which is what an ensemble's
  * arrays are indexed by, and names a `YearMonth`, which is what a report is labelled with. The
  * two directions are inverse on the run and empty outside it: a run is a closed interval, not a
  * calendar.
  *
  * Quarters are the calendar's own. A run that starts in February starts in the middle of a
  * quarter and its first quarter is therefore short — which is the point of asking `java.time`
  * rather than counting in threes from the start.
  * **Example.**
  * ```scala
  * //{
  * import java.time.YearMonth
  * //}
  * // A run of months. `monthly` refuses a negative count, so it returns an `Either`.
  * val horizon: Either[BayesError, Periods] = Periods.monthly(YearMonth.of(2027, 1), 11)
  *
  * // A period is addressed by its offset, which is what an ensemble's arrays are indexed by, and
  * // names a `YearMonth`, which is what a report is labelled with.
  * val labels: Either[BayesError, IArray[YearMonth]] = horizon.map(_.months)
  *
  * // Reporting by quarter: which quarters the run touches, and which one an offset falls in.
  * val quarters: Either[BayesError, IArray[Quarter]] = horizon.map(_.quarters)
  * val firstQuarter: Either[BayesError, Option[Quarter]] = horizon.map(_.quarterOf(0))
  * ```
  */
final case class Periods private (start: YearMonth, count: Int):

  def isEmpty: Boolean = count == 0

  def months: IArray[YearMonth] =
    IArray.tabulate(count)(o => start.plusMonths(o.toLong))

  /** The month at an offset, or nothing outside the run. */
  def at(offset: Int): Option[YearMonth] =
    if offset < 0 || offset >= count then None else Some(start.plusMonths(offset.toLong))

  /** The offset of a month, or nothing outside the run. */
  def offsetOf(month: YearMonth): Option[Int] =
    if count == 0 then None
    else
      val offset = java.time.temporal.ChronoUnit.MONTHS.between(start, month)
      if offset < 0L || offset >= count.toLong then None else Some(offset.toInt)

  def quarterOf(offset: Int): Option[Quarter] = at(offset).map(Quarter.of)

  /** The distinct quarters the run touches, in order. */
  def quarters: IArray[Quarter] =
    val seen = (0 until count).foldLeft(Vector.empty[Quarter]) { (collected, o) =>
      val quarter = Quarter.of(start.plusMonths(o.toLong))
      if collected.lastOption.contains(quarter) then collected else collected :+ quarter
    }
    IArray.from(seen)

  /** One past the last offset, as a month: the run is `[start, end)`. */
  private[bayes4s] def end: YearMonth = start.plusMonths(count.toLong)

object Periods:

  /** The run of no months. It has no start, so it is the only run whose `start` means nothing. */
  val empty: Periods = Periods(YearMonth.of(1970, 1), 0)

  def monthly(start: YearMonth, count: Int): Either[BayesError, Periods] =
    if count < 0 then
      Left(BayesError.InvalidArgument("Periods.monthly", "count", count.toDouble, "at least 0"))
    else if count == 0 then Right(empty)
    else Right(Periods(start, count))

  /** The shortest run of whole months holding both.
    *
    * Two runs that do not overlap are covered by the gap between them as well, because a run with
    * a hole in it is not expressible — and not wanted either, since what lives in the hole is
    * zero. This is what lets ensembles over different horizons be summed (`Ensemble`).
    */
  def cover(a: Periods, b: Periods): Periods =
    if a.isEmpty then b
    else if b.isEmpty then a
    else
      val from = if a.start.isBefore(b.start) then a.start else b.start
      val to = if a.end.isAfter(b.end) then a.end else b.end
      Periods(from, java.time.temporal.ChronoUnit.MONTHS.between(from, to).toInt)
