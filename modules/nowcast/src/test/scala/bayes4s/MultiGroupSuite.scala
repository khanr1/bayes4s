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

/** Several groups at once: fitted together, forecast together, and totalled sample by sample.
  *
  * Three of the phase's criteria live here. The fit has to recover what generated the data and
  * mix while doing it; the per-group forecast summed sample by sample has to equal the forecast of
  * the total; and a local level fitted to data that is not trending must not let a trend be found
  * in it.
  *
  * The last one is a comparison of two rate components on the same data, which is what makes it
  * worth running: `LocalLevel` has no trend to report, so the question is whether `Ar1Trend`,
  * given the same series, puts zero inside its trend's interval. If it does not, the two
  * components disagree about the data and at least one of them is wrong.
  */
object MultiGroupSuite extends SimpleIOSuite with Approx:

  private val months = 14
  private val maxLag = 4
  private val groups = 3
  private val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
  private val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)

  /** Three groups around a common level and trend, with a shared wobble. */
  private val truth = HierarchicalAr1Params[Double](
    muA = IArray(3.0),
    muB = IArray(0.5),
    sigmaA = 0.4,
    sigmaB = 0.15,
    aRaw = IArray(1.0, -0.5, 0.2),
    bRaw = IArray(-0.5, 1.0, 0.0),
    phi = 0.5,
    tau = 0.15,
    eps = IArray.tabulate(months)(m => math.sin(m * 0.8))
  )
  private val delayTruth = GroupedDelayParams[Double](
    cut0 = 0.4,
    increments = IArray(0.7, 0.5, 0.4),
    beta = 0.3,
    delta = IArray(0.0, 0.6, -0.6),
    sigmaCut = 0.5
  )

  private val rate = Ar1TrendHierarchical(IArray.fill(groups)(0))
  private val delay = OrderedLogitDriftByGroup()

  private val blank =
    DelayCountData
      .of(
        t,
        IArray.tabulate(groups)(_ => IArray.tabulate(months)(_ => IArray.fill(maxLag + 1)(0))),
        visible
      )
      .toOption
      .get

  private val generative = DelayCountModel.generative(rate, delay, blank)
  private val parameters =
    DelayCountParams[HierarchicalAr1Params, GroupedDelayParams, Double](truth, delayTruth)
  private val observed =
    generative.generative.simulateData[Rand](parameters).runA(Seed(20261107L)).value

  pureTest("the data covers three groups, and every group has counts of its own") {
    val perGroup = (0 until groups).map(g =>
      (0 until months).foldLeft(0) { (sum, m) =>
        sum + (0 to maxLag).foldLeft(0)((inner, k) =>
          if visible.isVisible(m, k) then inner + observed.counts(g)(m)(k) else inner
        )
      }
    )
    check(observed.groups == groups, s"${observed.groups} groups")
      .and(check(perGroup.forall(_ > 50), s"the groups total $perGroup"))
      // The groups differ: a test on data where they happened to coincide would say nothing about
      // whether the model keeps them apart.
      .and(check(perGroup.max > 1.3 * perGroup.min, s"the groups are too alike: $perGroup"))
  }

  /** The criterion of 8.2: per group, summed per sample, equals the total. */
  test("the per-group forecast summed sample by sample is the forecast of the total") {
    val futureT = IArray.tabulate(5)(h => (months + h - (months - 1) / 2.0) / months)
    val periods = Periods.monthly(YearMonth.of(2027, 3), 5 + maxLag).toOption.get
    val pool = TemplatePool.of(IArray("one"), IArray(1.0)).toOption.get
    val samples = List.fill(120)(parameters)
    // The same simulation, keyed two ways: once per group, once all together. Keying is labelling
    // and must not touch a single draw, so the totals have to agree exactly — and this is also
    // what says the groups are simulated inside one future rather than in three separate ones.
    val byGroup = Forecast.byGroup(
      rate,
      delay,
      samples,
      futureT,
      periods,
      pool,
      SizeModel.unit,
      groups,
      g => (_, scaled) => List(Contribution(s"group-$g", 0, scaled)),
      ForecastSettings(),
      Seed(99L)
    )
    val together = Forecast.byGroup(
      rate,
      delay,
      samples,
      futureT,
      periods,
      pool,
      SizeModel.unit,
      groups,
      _ => (_, scaled) => List(Contribution("all", 0, scaled)),
      ForecastSettings(),
      Seed(99L)
    )
    cats.effect.IO {
      val summed = byGroup.total
      val whole = together.total
      check(
        byGroup.values.keySet.size == groups,
        s"the per-group keys are ${byGroup.values.keySet}"
      )
        .and(check(summed.draws == whole.draws, s"${summed.draws} against ${whole.draws} futures"))
        .and(
          check(
            Ensemble.eq[Unit].eqv(summed, whole),
            "the per-group forecast summed by sample is not the forecast of the total"
          )
        )
        // And the contrast that matters: the sum of the groups' own quantiles is not the total's.
        .and {
          val perGroupQuantiles = byGroup.quantiles(List(0.95))
          val sumOfQuantiles =
            (0 until periods.count).map(p =>
              byGroup.values.keySet.toList.foldLeft(0.0)((s, k) => s + perGroupQuantiles(k)(0)(p))
            )
          val totalQuantile = whole.quantiles(List(0.95))(())(0)
          val gaps = (0 until periods.count).map(p => sumOfQuantiles(p) - totalQuantile(p))
          check(
            gaps.exists(_ > 1.0),
            f"the sum of the groups' 95th percentiles never exceeds the total's by more than " +
              f"${gaps.max}%.3f, so the contrast proves nothing"
          )
        }
    }
  }
