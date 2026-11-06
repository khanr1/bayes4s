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
import java.time.YearMonth
import weaver.SimpleIOSuite

/** The period axis a simulated future is indexed by.
  *
  * Whole months, and quarters as a view of them. There is no arithmetic here that `java.time` does
  * not already do correctly; what the suite pins is the part this library adds — that an offset
  * and a month name the same period in both directions, and that a quarter is four of these
  * aligned to the calendar rather than to the start of the run.
  */
object PeriodsSuite extends SimpleIOSuite with Approx:

  private val start = YearMonth.of(2026, 2)

  pureTest("a run of months names each of its periods, in both directions") {
    Periods.monthly(start, 5) match
      case Left(error) => failure(error.message)
      case Right(periods) =>
        val months = periods.months
        check(periods.count == 5, s"${periods.count} periods")
          .and(check(months.length == 5, s"${months.length} months"))
          .and(check(months(0) == start, s"the first month is ${months(0)}"))
          .and(check(months(4) == YearMonth.of(2026, 6), s"the last month is ${months(4)}"))
          .and(
            check(periods.at(2).contains(YearMonth.of(2026, 4)), s"offset 2 is ${periods.at(2)}")
          )
          // A month outside the run has no offset, and an offset outside it has no month: the two
          // partial functions are inverse on exactly the run itself.
          .and(check(periods.at(5).isEmpty, "offset 5 is inside a run of 5"))
          .and(check(periods.at(-1).isEmpty, "offset -1 is inside the run"))
          .and(check(periods.offsetOf(YearMonth.of(2026, 4)).contains(2), "April is not at 2"))
          .and(check(periods.offsetOf(YearMonth.of(2026, 1)).isEmpty, "January is inside the run"))
          .and(check(periods.offsetOf(YearMonth.of(2026, 7)).isEmpty, "July is inside the run"))
          .and(
            check(
              (0 until 5).forall(o => periods.at(o).flatMap(periods.offsetOf).contains(o)),
              "an offset does not survive a round trip through its month"
            )
          )
  }

  pureTest("a run refuses a length it cannot hold") {
    check(Periods.monthly(start, 0).isRight, "a run of no months is refusable but not an error")
      .and(check(Periods.monthly(start, -1).isLeft, "a run of -1 months was accepted"))
      .and(check(Periods.empty.count == 0, s"the empty run holds ${Periods.empty.count}"))
      .and(check(Periods.empty.months.isEmpty, "the empty run has months"))
      .and(check(Periods.empty.at(0).isEmpty, "the empty run has a period at 0"))
  }

  pureTest("quarters are the calendar's, not the run's") {
    // The run starts in February, which is the second month of the first quarter. A quarter is
    // therefore not four months counted from the start: the first one here is short.
    Periods.monthly(start, 8) match
      case Left(error) => failure(error.message)
      case Right(periods) =>
        val quarters = (0 until 8).map(o => periods.quarterOf(o))
        val expected = List(
          Quarter(2026, 1), // February
          Quarter(2026, 1), // March
          Quarter(2026, 2), // April
          Quarter(2026, 2),
          Quarter(2026, 2), // June
          Quarter(2026, 3), // July
          Quarter(2026, 3),
          Quarter(2026, 3) // September
        )
        check(
          quarters.toList == expected.map(Some.apply),
          s"the quarters are $quarters"
        )
          .and(check(periods.quarterOf(8).isEmpty, "offset 8 is inside a run of 8"))
          .and(
            check(
              periods.quarters.toList == expected.distinct,
              s"the distinct quarters are ${periods.quarters.toList}"
            )
          )
          .and(
            check(
              Quarter.of(YearMonth.of(2026, 12)) == Quarter(2026, 4),
              "December is not in the fourth quarter"
            )
          )
          .and(
            check(
              Quarter.of(YearMonth.of(2027, 1)) == Quarter(2027, 1),
              "January is not in the first quarter of its own year"
            )
          )
  }

  pureTest("two runs have a covering run, which is what summing over different horizons needs") {
    val a = Periods.monthly(YearMonth.of(2026, 2), 3).toOption.get
    val b = Periods.monthly(YearMonth.of(2026, 4), 4).toOption.get
    val disjoint = Periods.monthly(YearMonth.of(2027, 1), 2).toOption.get
    val cover = Periods.cover(a, b)
    check(cover.months.length == 6, s"the cover of three and four overlapping months is $cover")
      .and(check(cover.at(0).contains(YearMonth.of(2026, 2)), s"it starts at ${cover.at(0)}"))
      .and(check(cover.at(5).contains(YearMonth.of(2026, 7)), s"it ends at ${cover.at(5)}"))
      // Disjoint runs are covered by the gap as well, because a cover is a run of whole months and
      // a run with a hole in it is not expressible — nor wanted, since the hole is zero.
      // February 2026 through February 2027 inclusive: thirteen months, the gap included.
      .and(
        check(
          Periods.cover(a, disjoint).count == 13,
          s"${Periods.cover(a, disjoint).count} months"
        )
      )
      .and(check(Periods.cover(a, Periods.empty) == a, "the empty run is not neutral in a cover"))
      .and(check(Periods.cover(Periods.empty, b) == b, "the empty run is not neutral on the left"))
      .and(check(Periods.cover(a, b) == Periods.cover(b, a), "a cover is not symmetric"))
  }
