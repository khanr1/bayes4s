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

import cats.Eq
import cats.kernel.CommutativeMonoid

/** Simulated futures, aligned by sample from the posterior.
  *
  * `values(key)(sample)(offset)` is what key `key` contributed in period `offset` of simulated
  * future `sample`. The alignment is the whole point: future `sample` is one coherent story — the
  * same parameters, the same template, the same bootstrap weights throughout — so adding two keys
  * means adding their sample `s` to each other, and never adding two summaries.
  *
  * **Quantiles are computed at the end, from an ensemble, and never added.** The 90th percentile of
  * a total is not the sum of its parts' 90th percentiles; it is smaller whenever the parts are not
  * perfectly correlated, which they never are. Nothing in this type sums a quantile, and
  * `quantiles` is the only way to get one — deliberately the last step.
  * **Example.**
  * An ensemble is aligned by sample: row `s` of one key and row `s` of another came from the same
  * simulated future, which is what makes them addable and their summaries not:
  *
  * ```scala
  * //{
  * import java.time.YearMonth
  * //}
  * val months = Periods.monthly(YearMonth.of(2027, 1), 2).getOrElse(Periods.empty)
  *
  * val byCity: Either[BayesError, Ensemble[String]] = Ensemble.of(
  *   draws = 4,
  *   periods = months,
  *   values = Map(
  *     "Northgate" -> IArray(IArray(10.0, 12.0), IArray(30.0, 33.0), IArray(20.0, 21.0), IArray(5.0, 6.0)),
  *     "Rivermouth" -> IArray(IArray(30.0, 28.0), IArray(10.0, 7.0), IArray(20.0, 19.0), IArray(35.0, 34.0))
  *   )
  * )
  *
  * // Summed sample by sample, never percentile by percentile.
  * val total: Either[BayesError, Ensemble[Unit]] = byCity.map(_.total)
  *
  * // Coarser keys, summing the ones that land together. The total is the same whichever regrouping.
  * val byRegion: Either[BayesError, Ensemble[Boolean]] =
  *   byCity.map(_.regroup(_.startsWith("N")))
  *
  * // Quantiles come last, and reading an ensemble is the only way to get one.
  * val reported: Either[BayesError, Map[Unit, IArray[IArray[Double]]]] =
  *   total.map(_.quantiles(List(0.05, 0.5, 0.95)))
  * ```
  */
final case class Ensemble[K] private (
    draws: Int,
    periods: Periods,
    values: Map[K, IArray[IArray[Double]]]
):

  /** Summed over the keys, sample by sample: one story per sample, as a single series. */
  def total: Ensemble[Unit] = regroup(_ => ())

  /** The same futures under coarser keys. Keys mapped together are **summed**, sample by sample. */
  def regroup[K2](f: K => K2): Ensemble[K2] =
    val grouped = values.foldLeft(Map.empty[K2, IArray[IArray[Double]]]) {
      case (collected, (key, matrix)) =>
        val to = f(key)
        collected.updatedWith(to) {
          case None => Some(matrix)
          case Some(existing) => Some(Ensemble.add(existing, matrix))
        }
    }
    Ensemble(draws, periods, grouped)

  /** The quantiles of each key, one row per probability and one column per period.
    *
    * `quantiles(ps)(key)(i)(offset)` is the `ps(i)` quantile of the samples for that period. Type 7
    * throughout, as everywhere else in the library.
    */
  def quantiles(ps: List[Double]): Map[K, IArray[IArray[Double]]] =
    values.view.mapValues { matrix =>
      IArray.from(
        ps.map { p =>
          IArray.tabulate(periods.count) { offset =>
            Quantile.quantile(IArray.tabulate(draws)(s => matrix(s)(offset)), p)
          }
        }
      )
    }.toMap

object Ensemble:

  /** The ensemble with no keys: nothing simulated, over no periods. */
  def empty[K]: Ensemble[K] = Ensemble(0, Periods.empty, Map.empty)

  /** A shape with no contributions yet, which is what a simulation starts from. */
  def zero[K](draws: Int, periods: Periods): Ensemble[K] =
    Ensemble(math.max(draws, 0), periods, Map.empty)

  /** Checked once, here, so that nothing downstream has to look.
    *
    * Every key's matrix must be `draws` rows of `periods.count`. A ragged matrix is the mistake
    * this catches, and catching it at the boundary is why `values` can be read without checking.
    */
  def of[K](
      draws: Int,
      periods: Periods,
      values: Map[K, IArray[IArray[Double]]]
  ): Either[BayesError, Ensemble[K]] =
    if draws < 0 then
      Left(BayesError.InvalidArgument("Ensemble.of", "draws", draws.toDouble, "at least 0"))
    else
      values.toList
        .collectFirst {
          case (key, matrix) if matrix.length != draws =>
            BayesError.DimensionMismatch(s"Ensemble.of, key $key", draws, matrix.length)
          case (key, matrix) if matrix.exists(_.length != periods.count) =>
            BayesError.DimensionMismatch(
              s"Ensemble.of, key $key",
              periods.count,
              matrix
                .collectFirst { case row if row.length != periods.count => row.length }
                .getOrElse(periods.count)
            )
        }
        .toLeft(Ensemble(draws, periods, values))

  /** Sample by sample, key by key, over the run covering both.
    *
    * The instance is lawful for ensembles of **different shapes**, which it has to be to be a
    * monoid at all: the periods become the run covering both (`Periods.cover`), the samples the
    * larger count, and a cell neither side has is zero. In a simulation every ensemble shares a
    * shape and none of that padding happens; it exists so that the law holds for every value, not
    * only for the ones a simulation produces.
    *
    * Padding samples is positional, and that is a statement about how these values are made rather
    * than a statistical claim: sample `s` of two ensembles of the same run came from the same
    * posterior sample, which is exactly why they may be added. Two ensembles with different sample
    * counts did not, so the shorter one contributes zero to the samples it does not have.
    */
  given commutativeMonoid[K]: CommutativeMonoid[Ensemble[K]] with
    def empty: Ensemble[K] = Ensemble.empty[K]
    def combine(x: Ensemble[K], y: Ensemble[K]): Ensemble[K] =
      if x.values.isEmpty && x.draws == 0 && x.periods.isEmpty then y
      else if y.values.isEmpty && y.draws == 0 && y.periods.isEmpty then x
      else
        val periods = Periods.cover(x.periods, y.periods)
        val draws = math.max(x.draws, y.draws)
        val shiftX = offsetWithin(periods, x.periods)
        val shiftY = offsetWithin(periods, y.periods)
        val keys = x.values.keySet ++ y.values.keySet
        val values = keys.view.map { key =>
          val left = x.values.get(key).map(placed(_, draws, periods.count, shiftX))
          val right = y.values.get(key).map(placed(_, draws, periods.count, shiftY))
          key -> ((left, right) match
            case (Some(l), Some(r)) => add(l, r)
            case (Some(l), None) => l
            case (None, Some(r)) => r
            case (None, None) => zeros(draws, periods.count))
        }.toMap
        Ensemble(draws, periods, values)

  /** Equality cell by cell, which the laws need and a comparison by `IArray` cannot give. */
  given eq[K]: Eq[Ensemble[K]] with
    def eqv(x: Ensemble[K], y: Ensemble[K]): Boolean =
      x.draws == y.draws && x.periods == y.periods &&
        x.values.keySet == y.values.keySet &&
        x.values.keySet.forall { key =>
          val l = x.values(key)
          val r = y.values(key)
          l.length == r.length &&
          (0 until l.length).forall(s =>
            l(s).length == r(s).length && (0 until l(s).length).forall(p => l(s)(p) == r(s)(p))
          )
        }

  private[bayes4s] def add(
      l: IArray[IArray[Double]],
      r: IArray[IArray[Double]]
  ): IArray[IArray[Double]] =
    IArray.tabulate(l.length)(s => IArray.tabulate(l(s).length)(p => l(s)(p) + r(s)(p)))

  private def zeros(draws: Int, periods: Int): IArray[IArray[Double]] =
    IArray.tabulate(draws)(_ => IArray.fill(periods)(0.0))

  /** Where a run starts inside the run covering it. */
  private def offsetWithin(cover: Periods, part: Periods): Int =
    if part.isEmpty || cover.isEmpty then 0
    else java.time.temporal.ChronoUnit.MONTHS.between(cover.start, part.start).toInt

  /** A matrix placed into a larger shape, the rest zero. */
  private def placed(
      matrix: IArray[IArray[Double]],
      draws: Int,
      periods: Int,
      shift: Int
  ): IArray[IArray[Double]] =
    IArray.tabulate(draws) { s =>
      IArray.tabulate(periods) { p =>
        val from = p - shift
        if s < matrix.length && from >= 0 && from < matrix(s).length then matrix(s)(from) else 0.0
      }
    }
