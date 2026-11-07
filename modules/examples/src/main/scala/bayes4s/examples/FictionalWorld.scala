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

import bayes4s.Rand
import bayes4s.ReportingTriangle
import bayes4s.Rng
import bayes4s.Sampling
import bayes4s.Seed
import cats.syntax.all.*

/** One of four invented cities a lending library serves. */
enum City:
  case Northgate, Rivermouth, Stonefield, Larkhollow

/** One of five invented shelves a borrowed book comes from. */
enum Genre:
  case Adventure, History, Poetry, Science, Folklore

/** A borrowed book: its shelf, and how many days it was kept. */
final case class Loan(genre: Genre, returned: Int)

/** A membership application: the day it arrived, where, how long it waited to be processed, how
  * many books the new member borrowed, and what became of each.
  */
final case class Application(month: Int, city: City, delay: Int, size: Int, loans: Vector[Loan]):
  /** The month the application finished being processed, which is when it becomes visible. */
  def processed: Int = month + delay

/** A simulated history, and the views of it the examples read. */
final case class World(months: Int, logRate: IArray[Double], applications: Vector[Application]):

  /** Applications per month, every city together. */
  val arrivals: IArray[Int] =
    val byMonth = applications.groupMapReduce(_.month)(_ => 1)(_ + _)
    IArray.tabulate(months)(m => byMonth.getOrElse(m, 0))

  /** Applications per city and per month, indexed `[city][month]`. */
  val counts: IArray[IArray[Int]] =
    val byCell = applications.groupMapReduce(a => (a.city.ordinal, a.month))(_ => 1)(_ + _)
    IArray.tabulate(City.values.length)(c =>
      IArray.tabulate(months)(m => byCell.getOrElse((c, m), 0))
    )

  /** Applications by city, month of arrival and processing delay, indexed `[city][month][delay]`.
    *
    * An application processed more than `maxLag` months after it arrived is in no cell: the lag is
    * the horizon of the model that reads the triangle, and a count model is told how far it sees.
    */
  def triangle(maxLag: Int): IArray[IArray[IArray[Int]]] =
    val lags = math.max(maxLag, 0)
    val byCell =
      applications.groupMapReduce(a => (a.city.ordinal, a.month, a.delay))(_ => 1)(_ + _)
    IArray.tabulate(City.values.length)(c =>
      IArray.tabulate(months)(m => IArray.tabulate(lags + 1)(k => byCell.getOrElse((c, m, k), 0)))
    )

  /** What an observer standing in month `asOf` sees: a cell once its application was processed. */
  def visible(maxLag: Int, asOf: Int): ReportingTriangle =
    ReportingTriangle.rightCensored(months, math.max(maxLag, 0) + 1, asOf)

  /** Months kept, over every loan of every application. */
  def returns: IArray[Int] = IArray.from(applications.flatMap(_.loans.toList).map(_.returned))

/** The invented world the manual's examples are set in.
  *
  * A lending library in four invented cities. Membership applications arrive at a rate that drifts
  * from month to month — an AR(1) path on the log scale, so the rate is always positive and last
  * month's level tells you most of this one's — are processed after a delay, and bring a new
  * member who borrows a few books, each returned after a while.
  *
  * The unit of time is the **month**, which is the unit `Periods`, `ReportingTriangle` and
  * `DelayCountData` are written in: a history of this world can be handed to the count model of
  * `nowcast` without rescaling anything.
  *
  * Nothing here is drawn from any real library, service or business, and nothing is meant to be:
  * the parameters were chosen so that figures are legible and a fit takes seconds. What the world
  * does have is every shape the library models — counts by group, a drifting rate, a reporting
  * delay that right-censors the recent past, a size per event, and a second delay nested inside
  * the first.
  *
  * It is a value: `simulate` is a `Rand`, so the same seed always gives the same history, and
  * [[standard]] is that history under [[seed]].
  */
object FictionalWorld:

  /** How the world behaves. The defaults are what the manual's figures are drawn from. */
  final case class Settings(
      months: Int = 120,
      level: Double = 1.6,
      cities: IArray[Double] = IArray(0.5, 0.15, -0.25, -0.6),
      phi: Double = 0.8,
      tau: Double = 0.18,
      delayMean: Double = 1.6,
      extraBooks: Double = 1.4,
      returnMean: Double = 2.4,
      genres: IArray[Double] = IArray(5.0, 4.0, 3.0, 2.0, 1.0)
  )

  /** The seed the manual's figures are drawn under. */
  val seed: Seed = Seed(20261107L)

  /** The world of the manual: [[Settings]] by default, under [[seed]]. */
  lazy val standard: World = simulate(Settings()).runA(seed).value

  /** One history, as a pure program over a seed.
    *
    * Every number the world needs is drawn through [[bayes4s.Sampling]], so the history is the
    * same whichever interpreter runs it and the shape of each quantity is one a model in this
    * library can be written against:
    *
    *   - `logRate[m] = phi * logRate[m - 1] + tau * z[m]`, started at its stationary spread;
    *   - `arrivals[c][m] ~ Poisson(exp(level + cities[c] + logRate[m]))`;
    *   - `delay ~ Poisson(delayMean)`, `size = 1 + Poisson(extraBooks)`;
    *   - a genre drawn from `genres`, kept `1 + Poisson(returnMean - 1)` months.
    *
    * Out-of-range settings do not fail: a negative count of months gives an empty world, a
    * negative weight counts as zero, and weights all zero make the shelves equally likely.
    */
  def simulate(settings: Settings): Rand[World] =
    val months = math.max(settings.months, 0)
    for
      path <- ar1(months, settings.phi, settings.tau)
      histories <- City.values.toList.traverse { city =>
        path.zipWithIndex.traverse((logRate, month) => arrivalsOn(settings, city, month, logRate))
      }
    yield World(
      months,
      IArray.from(path),
      histories.flatten.flatten.toVector.sortBy(a => (a.month, a.city.ordinal))
    )

  // --- the pieces ---------------------------------------------------------------------------

  /** A stationary AR(1) path: the innovations drawn first, then folded into the path. */
  private def ar1(months: Int, phi: Double, tau: Double): Rand[List[Double]] =
    val memory = math.min(math.abs(phi), 0.999)
    val stationarySd = tau / math.sqrt(1.0 - memory * memory)
    List.fill(months)(Rng[Rand].normal).sequence.map {
      case Nil => Nil
      case first :: rest =>
        rest.scanLeft(stationarySd * first)((previous, z) => memory * previous + tau * z)
    }

  private def arrivalsOn(
      settings: Settings,
      city: City,
      month: Int,
      logRate: Double
  ): Rand[List[Application]] =
    val mean = math.exp(settings.level + at(settings.cities, city.ordinal) + logRate)
    Sampling
      .poisson[Rand](mean)
      .flatMap(count => List.fill(math.max(count, 0))(application(settings, month, city)).sequence)

  private def application(settings: Settings, month: Int, city: City): Rand[Application] =
    for
      delay <- Sampling.poisson[Rand](math.max(settings.delayMean, 0.0))
      extra <- Sampling.poisson[Rand](math.max(settings.extraBooks, 0.0))
      loans <- List.fill(math.max(extra, 0) + 1)(loan(settings)).sequence
    yield Application(month, city, math.max(delay, 0), loans.length, loans.toVector)

  private def loan(settings: Settings): Rand[Loan] =
    for
      shelf <- Sampling.categorical[Rand](cumulative(settings.genres))
      kept <- Sampling.poisson[Rand](math.max(settings.returnMean - 1.0, 0.0))
    yield Loan(
      Genre.fromOrdinal(math.min(math.max(shelf, 0), Genre.values.length - 1)),
      math.max(kept, 0) + 1
    )

  /** Cumulative shelf probabilities, one per [[Genre]] whatever the settings hold. */
  private def cumulative(weights: IArray[Double]): IArray[Double] =
    val shelves = Genre.values.length
    val asked = IArray.tabulate(shelves)(i => math.max(at(weights, i), 0.0))
    val total = asked.sum
    val masses = if total > 0.0 then asked.map(_ / total) else IArray.fill(shelves)(1.0 / shelves)
    IArray.tabulate(shelves)(i => masses.take(i + 1).sum)

  /** An offset or a weight that the settings may be too short to hold. */
  private def at(values: IArray[Double], index: Int): Double =
    if index >= 0 && index < values.length then values(index) else 0.0
