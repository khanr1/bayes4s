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
import java.time.YearMonth
import weaver.SimpleIOSuite

/** The predictive simulation, and the log-normal sizes it can attach to events.
  *
  * The quantitative claim is the one worth testing: over many simulated futures, the expected
  * total is the projected rate times the mean size, with the first period scaled by its own
  * factor. Everything else in the assembly — the pool, the delay, the rule — is tested in the
  * phases that own it; what this adds is that they are wired together in the right order and that
  * one future uses one sample throughout.
  */
object ForecastSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val horizon = 4
  private val futureT = IArray(0.5, 0.6, 0.7, 0.8)
  private val periods = Periods.monthly(YearMonth.of(2027, 1), horizon + 3).toOption.get

  /** A rate with no wobble and no trend: `exp(a)` events a month, exactly. */
  private val flatRate =
    Ar1TrendParams[Double](IArray(math.log(20.0)), IArray(0.0), 0.0, 0.0, IArray.fill(4)(0.0))

  /** All mass at delay zero: an event is reported in the month it closes. */
  private val immediate = OrderedLogitDriftParams[Double](20.0, IArray(1.0, 1.0), 0.0)

  private val parameters =
    DelayCountParams[Ar1TrendParams, OrderedLogitDriftParams, Double](flatRate, immediate)
  private val pool = TemplatePool.of(IArray("only"), IArray(1.0)).toOption.get
  private val rule: ContributionRule[String, String] =
    (_, scaled) => List(Contribution("count", 0, scaled))

  pureTest("a run produces one future per sample, times perDraw") {
    val samples = List.fill(5)(parameters)
    val once = Forecast.run(
      Ar1Trend(),
      OrderedLogitDrift(),
      samples,
      futureT,
      periods,
      pool,
      SizeModel.unit,
      rule,
      ForecastSettings(),
      Seed(1L)
    )
    val thrice = Forecast.run(
      Ar1Trend(),
      OrderedLogitDrift(),
      samples,
      futureT,
      periods,
      pool,
      SizeModel.unit,
      rule,
      ForecastSettings(perDraw = 3),
      Seed(1L)
    )
    check(once.draws == 5, s"${once.draws} futures from five samples")
      .and(check(thrice.draws == 15, s"${thrice.draws} futures at three per sample"))
      .and(check(once.periods == periods, "the ensemble is over the wrong run"))
      .and(check(once.values.keySet == Set("count"), s"the keys are ${once.values.keySet}"))
      .and {
        val empty = Forecast.run(
          Ar1Trend(),
          OrderedLogitDrift(),
          Nil,
          futureT,
          periods,
          pool,
          SizeModel.unit,
          rule,
          ForecastSettings(),
          Seed(1L)
        )
        check(empty.draws == 0, "no samples gave futures")
      }
  }

  test("the expected total is the rate times the mean size, the first period scaled") {
    // A flat rate of twenty a month over four months, every event reported at once and counting
    // one: the expected total is eighty, and with the first month half-counted it is seventy.
    val samples = List.fill(2000)(parameters)
    def totalOf(factor: Double): Double =
      val ensemble = Forecast.run(
        Ar1Trend(),
        OrderedLogitDrift(),
        samples,
        futureT,
        periods,
        pool,
        SizeModel.unit,
        rule,
        ForecastSettings(firstPeriodFactor = factor),
        Seed(20261106L)
      )
      val matrix = ensemble.values("count")
      val totals = (0 until ensemble.draws).map(s =>
        (0 until ensemble.periods.count).foldLeft(0.0)((sum, p) => sum + matrix(s)(p))
      )
      totals.sum / totals.length
    val whole = totalOf(1.0)
    val halved = totalOf(0.5)
    // Each future's total is Poisson with mean 80, so the standard error of the average of 2000 of
    // them is sqrt(80/2000).
    cats.effect.IO {
      withinStandardErrors(whole, 80.0, math.sqrt(80.0 / 2000.0), 4.0, "the expected total")
        .and(
          withinStandardErrors(
            halved,
            70.0,
            math.sqrt(70.0 / 2000.0),
            4.0,
            "with a half first period"
          )
        )
    }
  }

  test("a delay moves contributions later, and the horizon's own periods stay empty") {
    // All mass at delay two: nothing can land in the first two periods of the forecast.
    val delayed = OrderedLogitDriftParams[Double](-20.0, IArray(0.1, 40.0), 0.0)
    val samples = List.fill(300)(
      DelayCountParams[Ar1TrendParams, OrderedLogitDriftParams, Double](flatRate, delayed)
    )
    val ensemble = Forecast.run(
      Ar1Trend(),
      OrderedLogitDrift(),
      samples,
      futureT,
      periods,
      pool,
      SizeModel.unit,
      rule,
      ForecastSettings(),
      Seed(5L)
    )
    val matrix = ensemble.values("count")
    val perPeriod = (0 until ensemble.periods.count).map(p =>
      (0 until ensemble.draws).foldLeft(0.0)((sum, s) => sum + matrix(s)(p)) / ensemble.draws
    )
    cats.effect.IO {
      check(
        perPeriod(0) == 0.0 && perPeriod(1) == 0.0,
        s"a delay of two still filled the first periods: ${perPeriod.take(3)}"
      )
        .and(check(perPeriod(2) > 10.0, s"period two holds only ${perPeriod(2)}"))
        // Four closes, each reported two later, over a run of seven: periods 2 to 5 fill and 6 is
        // empty, because no close happens after period 3.
        .and(check(perPeriod(6) == 0.0, s"period six holds ${perPeriod(6)}"))
    }
  }

  test("log-normal sizes have the mean the parameters imply, and the cap truncates") {
    val p = LogNormalTrendRegressionParams[Double](mu = 2.0, kappa = 0.0, omega = 0.0, s = 0.5)
    val uncapped = SizeModel.logNormal[String](p)
    val capped = SizeModel.logNormal[String](p, cap = Some(10.0))
    val replications = 40_000
    def drawn(model: SizeModel[String]): IArray[Double] =
      IArray.tabulate(replications)(i =>
        model.draw[Rand]("x", 0.0, 0).runA(Seed(9L).fold(i.toLong)).value
      )
    val free = drawn(uncapped)
    val limited = drawn(capped)
    val mean = (0 until replications).foldLeft(0.0)((s, i) => s + free(i)) / replications
    // A log-normal's mean is exp(mu + s²/2), and its variance exp(2mu + s²)(exp(s²) - 1).
    val expected = math.exp(2.0 + 0.125)
    val variance = math.exp(4.0 + 0.25) * (math.exp(0.25) - 1.0)
    cats.effect.IO {
      withinStandardErrors(
        mean,
        expected,
        math.sqrt(variance / replications),
        4.0,
        "the mean log-normal size"
      )
        .and(check((0 until replications).forall(i => limited(i) <= 10.0), "the cap was exceeded"))
        .and(
          check(
            (0 until replications).exists(i => free(i) > 10.0),
            "nothing exceeded ten, so the cap test proves nothing"
          )
        )
        // The median is exp(mu) whatever the spread, and the cap is above it, so the median is
        // untouched: a cap changes the tail, not the centre.
        .and(
          closeRel(
            Quantile.quantile(limited, 0.5),
            math.exp(2.0),
            0.02,
            "the capped median against exp(mu)"
          )
        )
        .and(
          check(
            SizeModel.unit[String].draw[Rand]("x", 0.0, 0).runA(Seed(1L)).value == 1.0,
            "unit size"
          )
        )
    }
  }

  pureTest("the same seed forecasts the same futures, and a different one does not") {
    val samples = List.fill(20)(parameters)
    def run(seed: Long) = Forecast.run(
      Ar1Trend(),
      OrderedLogitDrift(),
      samples,
      futureT,
      periods,
      pool,
      SizeModel.unit,
      rule,
      ForecastSettings(),
      Seed(seed)
    )
    val once = run(3L)
    val again = run(3L)
    val other = run(4L)
    check(Ensemble.eq[String].eqv(once, again), "the same seed gave different futures")
      .and(check(!Ensemble.eq[String].eqv(once, other), "a different seed gave the same futures"))
  }
