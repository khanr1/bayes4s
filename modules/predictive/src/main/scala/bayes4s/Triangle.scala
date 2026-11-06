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

import cats.Monad
import cats.syntax.all.*

/** A Poisson stream split over reporting delays.
  *
  * An event created in period `m` is reported with delay `k` with probability `shares(k)`. Thinning
  * a Poisson count that way gives **independent** Poisson counts per cell with mean
  * `rates(m) * shares(k)` — the standard property of a Poisson process split by an independent
  * mark — so a grid of counts can be simulated cell by cell rather than event by event.
  *
  * `firstPeriodFactor` scales the first delay only. A period is an interval, not an instant: an
  * event created halfway through one has only half of it left in which to be reported with no
  * delay, and the factor is where that goes. It is a parameter rather than a fixed 0.5 because how
  * creations spread within a period is a property of the data, not of arithmetic.
  */
object PoissonThinning:

  /** The mean of every cell, `rates.length` by `shares.length`. */
  def means(
      rates: IArray[Double],
      shares: IArray[Double],
      firstPeriodFactor: Double
  ): IArray[IArray[Double]] =
    IArray.tabulate(rates.length) { m =>
      IArray.tabulate(shares.length) { k =>
        val share = if k == 0 then shares(k) * firstPeriodFactor else shares(k)
        rates(m) * share
      }
    }

  /** One simulated grid of counts. */
  def simulate[F[_]: Monad: Rng](
      rates: IArray[Double],
      shares: IArray[Double],
      firstPeriodFactor: Double
  ): F[IArray[IArray[Int]]] =
    val grid = means(rates, shares, firstPeriodFactor)
    (0 until rates.length).toList
      .traverse { m =>
        (0 until shares.length).toList
          .traverse(k => Sampling.poisson[F](grid(m)(k)))
          .map(row => IArray.from(row))
      }
      .map(rows => IArray.from(rows))

/** Which cells of a (creation period, delay) grid the data has actually seen.
  *
  * The mask is **arbitrary**. The two usual shapes are constructors — right censoring, where a
  * cell is visible once its reporting period is in the past, and left truncation, where the
  * available history begins somewhere after the first creation period — and their intersection is
  * the ordinary case. Nothing here assumes the visible set is a triangle, because a lost month or
  * a system that changed mid-history is a hole, and a library that assumed a shape would quietly
  * mis-state the exposure of every period after it.
  */
final case class ReportingTriangle private (
    creations: Int,
    delays: Int,
    private val mask: IArray[IArray[Boolean]]
):

  def isVisible(creation: Int, delay: Int): Boolean =
    creation >= 0 && creation < creations && delay >= 0 && delay < delays &&
      mask(creation)(delay)

  def visibleCells: Int =
    (0 until creations).foldLeft(0)((count, m) => count + (0 until delays).count(mask(m)(_)))

  /** Both masks, cell by cell. Refused when the grids are not the same shape. */
  def intersect(other: ReportingTriangle): Either[BayesError, ReportingTriangle] =
    if other.creations != creations then
      Left(BayesError.DimensionMismatch("ReportingTriangle.intersect", creations, other.creations))
    else if other.delays != delays then
      Left(BayesError.DimensionMismatch("ReportingTriangle.intersect", delays, other.delays))
    else
      Right(
        ReportingTriangle(
          creations,
          delays,
          IArray.tabulate(creations)(m =>
            IArray.tabulate(delays)(k => mask(m)(k) && other.mask(m)(k))
          )
        )
      )

  /** The share of a creation period's events that could have been seen: the visible delays' mass.
    *
    * For the right-censored mask this is `F(c - 1 - m)`, the delay distribution's cumulative
    * function at the age of the period — which is the closed form the suite checks it against. For
    * an arbitrary mask there is no closed form, and summing the visible shares is the definition.
    */
  def exposure(shares: IArray[Double]): IArray[Double] =
    IArray.tabulate(creations) { m =>
      (0 until math.min(delays, shares.length))
        .foldLeft(0.0)((sum, k) => if mask(m)(k) then sum + shares(k) else sum)
    }

  def totalExposure(shares: IArray[Double]): Double =
    val per = exposure(shares)
    (0 until per.length).foldLeft(0.0)((sum, m) => sum + per(m))

  /** Observed counts scaled up by what the exposure says is still to come.
    *
    * `observed(m) / exposure(m)`, and `NaN` wherever the exposure is below `minimumVisibility` or
    * zero. The threshold is the whole point: the newest period has seen almost none of its events,
    * so dividing by its exposure turns one early report into an enormous estimate. Reporting `NaN`
    * says "not yet knowable", which is true, where a number would be a confident mistake.
    */
  def correctedCounts(
      observed: IArray[Double],
      shares: IArray[Double],
      minimumVisibility: Double
  ): IArray[Double] =
    val per = exposure(shares)
    IArray.tabulate(math.min(observed.length, per.length)) { m =>
      if !(per(m) > 0.0) || per(m) < minimumVisibility then Double.NaN
      else observed(m) / per(m)
    }

object ReportingTriangle:

  /** Any mask at all, given as a predicate on the cell. */
  def of(creations: Int, delays: Int, visible: (Int, Int) => Boolean): ReportingTriangle =
    ReportingTriangle(
      math.max(creations, 0),
      math.max(delays, 0),
      IArray.tabulate(math.max(creations, 0))(m =>
        IArray.tabulate(math.max(delays, 0))(k => visible(m, k))
      )
    )

  /** Visible once the reporting period is in the past: `m + k <= current - 1`. */
  def rightCensored(creations: Int, delays: Int, current: Int): ReportingTriangle =
    of(creations, delays, (m, k) => m + k <= current - 1)

  /** Visible once the reporting period is within the available history: `m + k >= firstVisible`. */
  def leftTruncated(creations: Int, delays: Int, firstVisible: Int): ReportingTriangle =
    of(creations, delays, (m, k) => m + k >= firstVisible)
