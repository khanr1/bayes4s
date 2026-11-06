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

import cats.kernel.CommutativeMonoid

/** What one simulated event contributes: an amount, under a key, at an offset from the period the
  * event closes in.
  *
  * The offset is what makes a delay expressible — an event that closes in March and is reported
  * over April and May is two contributions at offsets 1 and 2 — and it is relative to the close
  * rather than to the start of the run, because that is how a delay is known.
  */
final case class Contribution[K](key: K, periodOffset: Int, amount: Double)

/** What an application says its events contribute.
  *
  * `payload` is the application's own type — a drawn template, a sampled customer, whatever the
  * model produces — and `scaled` is its size after whatever scaling the simulation applies, so a
  * rule never has to know about trends or bootstrap weights. The library knows only
  * [[Contribution]].
  */
trait ContributionRule[P, K]:
  def contributions(payload: P, scaled: Double): List[Contribution[K]]

/** One simulated future under construction: a total per key per period, nothing more.
  *
  * Events are added one at a time, each with the offset of the period it closes in. A contribution
  * that falls outside the run is **dropped**: not clamped to the last period, not wrapped, not
  * accumulated into an overflow bucket. Clamping would pile every late delay onto the final period
  * and make it look busy; dropping is the honest statement that the run does not cover it.
  */
final case class SimulatedFuture[K] private (
    periods: Periods,
    totals: Map[K, IArray[Double]]
):

  /** An event closing at `closeOffset`, with the contributions a rule gave for it. */
  def add(closeOffset: Int, contributions: List[Contribution[K]]): SimulatedFuture[K] =
    val placed = contributions.foldLeft(totals) { (collected, contribution) =>
      val at = closeOffset + contribution.periodOffset
      val row = collected.getOrElse(contribution.key, IArray.fill(periods.count)(0.0))
      val updated =
        if at < 0 || at >= periods.count then row
        else
          IArray.tabulate(periods.count)(p =>
            if p == at then row(p) + contribution.amount else row(p)
          )
      collected + (contribution.key -> updated)
    }
    SimulatedFuture(periods, placed)

  /** This future as an ensemble of one sample, which is what the monoid combines. */
  def ensemble: Ensemble[K] =
    Ensemble
      .of(1, periods, totals.view.mapValues(row => IArray(row)).toMap)
      .getOrElse(Ensemble.zero[K](1, periods))

object SimulatedFuture:
  def empty[K](periods: Periods): SimulatedFuture[K] =
    SimulatedFuture(periods, Map.empty)

/** A simulated future from one sample from the posterior. Pure: the seed is an argument.
  *
  * `draw` carries everything the future needs from the fit — the rates, the templates, the
  * bootstrap weights — so that an amount and the parts it decomposes into come from the same
  * sample and keep their correlation.
  */
trait Simulator[P, K]:
  def simulate(draw: P, seed: Seed): Ensemble[K]

object Simulator:

  /** One future per sample, each from its own derived seed.
    *
    * Future `i` is simulated at `seed.fold(i)`, so the result is a function of the seed alone and
    * not of the order the futures are computed in: the loop is parallelisable without changing a
    * single number.
    *
    * Each future contributes its own row of the ensemble, which is the same thing as summing the
    * futures by the monoid after placing each in its own row — and that equality is asserted in
    * the suite rather than assumed. It is written as a stacking because the monoid version would
    * allocate a full matrix per future to hold one row of content.
    */
  def run[P, K](sim: Simulator[P, K], draws: IArray[P], seed: Seed): Ensemble[K] =
    if draws.length == 0 then Ensemble.empty[K]
    else
      val futures = IArray.tabulate(draws.length)(i => sim.simulate(draws(i), seed.fold(i.toLong)))
      val periods = futures.foldLeft(Periods.empty)((cover, f) => Periods.cover(cover, f.periods))
      val keys = futures.foldLeft(Set.empty[K])((collected, f) => collected ++ f.values.keySet)
      val values = keys.view.map { key =>
        key -> IArray.tabulate(draws.length) { i =>
          val future = futures(i)
          val shift = shiftOf(periods, future.periods)
          future.values.get(key) match
            case None => IArray.fill(periods.count)(0.0)
            case Some(matrix) =>
              IArray.tabulate(periods.count) { p =>
                val from = p - shift
                if matrix.length > 0 && from >= 0 && from < matrix(0).length then matrix(0)(from)
                else 0.0
              }
        }
      }.toMap
      Ensemble.of(draws.length, periods, values).getOrElse(Ensemble.zero[K](draws.length, periods))

  /** Where a future's own run starts inside the run covering all of them. */
  private def shiftOf(cover: Periods, part: Periods): Int =
    if cover.isEmpty || part.isEmpty then 0
    else java.time.temporal.ChronoUnit.MONTHS.between(cover.start, part.start).toInt

  /** The monoid, named so that a simulator can combine the pieces of one future explicitly. */
  def combining[K]: CommutativeMonoid[Ensemble[K]] = Ensemble.commutativeMonoid[K]
