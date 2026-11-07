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

import bayes4s.*
import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** The invented world, arranged as the count model's data.
  *
  * Nothing statistical here: the claims are that the arrangement loses nothing and invents
  * nothing. Every application that arrived inside the window and was processed within the maximum
  * delay is in exactly one cell; the mask hides exactly those cells whose reports had not arrived
  * by the day the observer is standing on; and the delay shares are the world's own, which is what
  * makes the exposure and the corrected counts comparable with it.
  *
  * The manual reads these values, so a change that broke them would be read as the library being
  * wrong rather than the example.
  */
object LibraryHistorySuite extends SimpleIOSuite with Approx:

  private val prepared = LibraryHistory.of()

  pureTest("the arrangement keeps every application that belongs in the window") {
    prepared match
      case Left(error) => failure(s"the history could not be arranged: $error")
      case Right(history) =>
        val inside = history.world.applications.count(a =>
          a.month < history.months && a.delay <= history.maxLag
        )
        val cells = (0 until history.cities).map { c =>
          (0 until history.months).map { m =>
            (0 to history.maxLag).map(k => history.data.counts(c)(m)(k)).sum
          }.sum
        }.sum
        check(cells == inside, s"$cells applications in the cells and $inside in the window")
          .and(check(history.data.groups == history.cities, "a city has no group"))
          .and(check(history.data.months == history.months, "the data has the wrong length"))
          .and(check(history.t.length == history.months, "the time axis has the wrong length"))
  }

  pureTest("the mask hides exactly what had not been processed by the day it stands on") {
    prepared match
      case Left(error) => failure(s"the history could not be arranged: $error")
      case Right(history) =>
        val visible = history.data.cells.map((_, _, _, y) => y).sum
        val processed = history.world.applications.count(a =>
          a.month < history.months && a.delay <= history.maxLag && a.processed < history.asOf
        )
        check(visible == processed, s"$visible cells are visible and $processed were processed")
          .and(check(visible < history.data.counts.map(_.map(_.sum).sum).sum, "nothing is hidden"))
  }

  pureTest("the delay shares are a truncated Poisson, which is what the world draws them from") {
    prepared match
      case Left(error) => failure(s"the history could not be arranged: $error")
      case Right(history) =>
        val shares = history.delayShares
        val mean = FictionalWorld.Settings().delayMean
        // Poisson(delayMean) renormalised over the delays the window can see: the analytic form
        // the shares are an estimate of. Four standard errors of a proportion at this count.
        val mass = IArray.tabulate(history.maxLag + 1) { k =>
          (0 until k).foldLeft(math.exp(-mean))((p, i) => p * mean / (i + 1.0))
        }
        val inside = history.world.applications.count(_.delay <= history.maxLag).toDouble
        val expected = IArray.tabulate(history.maxLag + 1)(k => mass(k) / mass.sum)
        val tooFar = (0 to history.maxLag).filter { k =>
          val standardError = math.sqrt(expected(k) * (1.0 - expected(k)) / inside)
          math.abs(shares(k) - expected(k)) > 4.0 * standardError
        }
        closeRel(shares.sum, 1.0, 1e-12, s"the shares sum to ${shares.sum}")
          .and(check(tooFar.isEmpty, s"delays $tooFar are not Poisson(${mean}): ${shares.toList}"))
          // The mode is at one, not at zero: an application is usually processed the month after
          // it arrives. Asserting a decreasing delay would be asserting a geometric one.
          .and(check(shares(1) > shares(0), "the delay does not peak after the first month"))
  }

  pureTest("the exposure falls towards the present, and the newest month knows least") {
    prepared match
      case Left(error) => failure(s"the history could not be arranged: $error")
      case Right(history) =>
        val exposure = history.exposure
        val recent = exposure.takeRight(history.maxLag + 1).toList
        check(exposure.length == history.months, "there is no exposure per month")
          .and(
            check(
              exposure.take(history.months - history.maxLag - 1).forall(_ > 0.99),
              "an old month is incomplete"
            )
          )
          .and(check(recent == recent.sorted.reverse, s"the exposure does not fall: $recent"))
          .and(check(exposure(history.months - 1) < 0.5, "the newest month is nearly complete"))
  }

  pureTest("the corrected counts refuse the months that have seen almost nothing") {
    prepared match
      case Left(error) => failure(s"the history could not be arranged: $error")
      case Right(history) =>
        val corrected = history.correctedCounts(City.values.head, minimumVisibility = 0.3)
        val raw = history.visibleTotals(0)
        val refused = corrected.toList.count(_.isNaN)
        check(refused > 0, "no month was refused, so the threshold does nothing here")
          .and(
            check(
              (0 until history.months).forall(m =>
                corrected(m).isNaN || corrected(m) >= raw(m) - 1e-9
              ),
              "a correction made a count smaller than what was already seen"
            )
          )
  }

  pureTest("a loan's return is placed at the month it was returned in") {
    prepared match
      case Left(error) => failure(s"the history could not be arranged: $error")
      case Right(history) =>
        val returns = history.returnsByMonth
        val expected = history.world.applications
          .filter(a => a.month < history.months)
          .flatMap(a => a.loans.toList.map(loan => a.processed + loan.returned))
          .count(m => m >= 0 && m < history.months)
        check(returns.sum == expected, s"${returns.sum} returns placed and $expected expected")
          .and(check(returns.length == history.months, "the returns are not one per month"))
          // A return cannot land before the application that caused it was processed.
          .and(check(returns(0) == 0, "a book was returned before anyone joined"))
  }
