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

import bayes4s.Seed
import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** The invented world the manual draws on.
  *
  * A simulator is tested against the laws of the distributions it claims to draw from, which is
  * the only independent reference there is for it: the mean of a Poisson is its rate, the
  * stationary mean of `exp` of an AR(1) path is `exp(sigma^2 / 2)`, and a lag-one correlation is
  * `phi`. The structural claims — a city out of four, a loan per book, nothing visible before it
  * was processed — are exact and checked exactly.
  *
  * Monte Carlo thresholds are four standard errors, and the series' errors carry the AR(1)
  * inflation `sqrt((1 + phi) / (1 - phi))`, without which the bound would be too tight by a factor
  * of three and the test would fail on a seed that is perfectly good.
  */
object FictionalWorldSuite extends SimpleIOSuite with Approx:

  private val settings = FictionalWorld.Settings()
  private val world = FictionalWorld.standard

  /** A long run, for the claims that are about a distribution rather than about a shape. */
  private val long =
    FictionalWorld.simulate(settings.copy(months = 1200)).runA(Seed(7L)).value

  pureTest("the same seed gives the same world, a different seed a different one") {
    val again = FictionalWorld.simulate(settings).runA(FictionalWorld.seed).value
    val other = FictionalWorld.simulate(settings).runA(Seed(99L)).value
    check(again.applications == world.applications, "the generator is not reproducible")
      .and(check(other.applications != world.applications, "two seeds give the same world"))
      .and(check(world.applications.nonEmpty, "the world is empty"))
  }

  pureTest("every application is well formed") {
    val malformed = world.applications.filter { a =>
      a.month < 0 || a.month >= world.months ||
      a.delay < 0 ||
      a.loans.isEmpty ||
      a.loans.exists(_.returned < 1) ||
      a.size != a.loans.length
    }
    check(malformed.isEmpty, s"${malformed.length} applications are malformed")
      .and(check(world.applications.map(_.city).toSet == City.values.toSet, "a city never appears"))
      .and(
        check(
          world.applications.flatMap(_.loans.toList).map(_.genre).toSet == Genre.values.toSet,
          "a genre never appears"
        )
      )
  }

  pureTest("the views agree with the applications they are made of") {
    val byCity = world.counts
    val perMonth = world.arrivals
    val cityTotals = City.values.toList.map(c => byCity(c.ordinal).sum)
    check(
      perMonth.sum == world.applications.length,
      s"the monthly counts sum to ${perMonth.sum} and not to ${world.applications.length}"
    ).and(
      check(
        cityTotals.sum == world.applications.length,
        "the counts by city do not sum to the applications"
      )
    ).and {
      val recounted = City.values.toList.map(c => world.applications.count(_.city == c))
      check(cityTotals == recounted, s"the counts by city are $cityTotals and not $recounted")
    }.and {
      // A triangle holds a cell for every delay it has room for, and only those.
      val triangle = world.triangle(maxLag = 6)
      val inside = world.applications.count(_.delay <= 6)
      val total = triangle.map(_.map(_.sum).sum).sum
      check(total == inside, s"the triangle holds $total applications and not $inside")
    }
  }

  pureTest("what is visible as of a month is what was processed by then") {
    val asOf = 60
    val triangle = world.triangle(maxLag = 6)
    val mask = world.visible(maxLag = 6, asOf = asOf)
    val visible =
      (0 until world.months).map { m =>
        (0 to 6).filter(k => mask.isVisible(m, k)).map(k => triangle(0)(m)(k)).sum
      }.sum
    val processed = world.applications.count(a =>
      a.city == City.values.head && a.processed < asOf && a.delay <= 6
    )
    check(visible == processed, s"$visible cells are visible and $processed were processed")
      .and(check(!mask.isVisible(asOf - 1, 1), "a cell in the future is visible"))
      .and(check(mask.isVisible(0, 0), "the oldest cell is hidden"))
  }

  pureTest("the arrival rate has the mean and the memory it was given") {
    val months = long.months
    val trend = long.logRate
    val stationary = settings.tau * settings.tau / (1.0 - settings.phi * settings.phi)
    val inflation = math.sqrt((1.0 + settings.phi) / (1.0 - settings.phi))
    // Lag-one correlation of the path: phi, with a standard error of 1/sqrt(months).
    val mean = trend.sum / months
    val centred = trend.map(_ - mean)
    val variance = centred.map(x => x * x).sum / months
    val lagOne = (1 until months).map(i => centred(i) * centred(i - 1)).sum / months / variance
    // Mean count in the first city: exp(level + offset) * exp(sigma^2 / 2), a Poisson mixture.
    val counts = long.counts(0)
    val expected =
      math.exp(settings.level + settings.cities(0)) * math.exp(stationary / 2.0)
    val observed = counts.sum.toDouble / months
    val spread = math.sqrt(counts.map(y => (y - observed) * (y - observed)).sum / months)
    val standardError = spread / math.sqrt(months.toDouble) * inflation
    close(
      lagOne,
      settings.phi,
      4.0 / math.sqrt(months.toDouble),
      s"the memory of the path is $lagOne"
    )
      .and {
        // Four standard errors of the sample variance of an AR(1) path:
        // sd(s^2) / sigma^2 = sqrt(2 / n * (1 + phi^2) / (1 - phi^2)).
        val relative =
          math.sqrt(
            2.0 / months * (1.0 + settings.phi * settings.phi) / (1.0 - settings.phi * settings.phi)
          )
        close(
          variance,
          stationary,
          4.0 * relative * stationary,
          s"the spread of the path is $variance"
        )
      }
      .and(close(observed, expected, 4.0 * standardError, s"the mean count is $observed"))
  }

  pureTest("sizes, processing delays and return delays have the means they were given") {
    val applications = long.applications
    val n = applications.length.toDouble
    val sizes = applications.map(_.size.toDouble)
    val delays = applications.map(_.delay.toDouble)
    val returns = applications.flatMap(_.loans.toList).map(_.returned.toDouble)
    def mean(xs: Vector[Double]): Double = xs.sum / xs.length
    def standardError(xs: Vector[Double]): Double =
      val m = mean(xs)
      math.sqrt(xs.map(x => (x - m) * (x - m)).sum / xs.length / xs.length)
    check(n > 10000.0, s"only $n applications to average over")
      .and(
        close(
          mean(sizes),
          1.0 + settings.extraBooks,
          4.0 * standardError(sizes),
          s"the mean size is ${mean(sizes)}"
        )
      )
      .and(
        close(
          mean(delays),
          settings.delayMean,
          4.0 * standardError(delays),
          s"the mean processing delay is ${mean(delays)}"
        )
      )
      .and(
        close(
          mean(returns),
          settings.returnMean,
          4.0 * standardError(returns),
          s"the mean return delay is ${mean(returns)}"
        )
      )
  }

  pureTest("the cities keep the order of their offsets, and a genre its share") {
    val perCity = City.values.toList.map(c => long.counts(c.ordinal).sum)
    val expected = settings.cities.toList.zipWithIndex.sortBy(-_._1).map(_._2)
    val observed = perCity.zipWithIndex.sortBy(-_._1).map(_._2)
    val loans = long.applications.flatMap(_.loans.toList)
    val share = loans.count(_.genre == Genre.values.head).toDouble / loans.length
    val weights = settings.genres.sum
    val target = settings.genres(0) / weights
    val standardError = math.sqrt(target * (1.0 - target) / loans.length)
    check(observed == expected, s"the cities rank $observed and their offsets rank $expected")
      .and(close(share, target, 4.0 * standardError, s"the first genre takes $share of the loans"))
  }
