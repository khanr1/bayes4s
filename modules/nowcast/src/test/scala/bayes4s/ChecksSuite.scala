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
import weaver.SimpleIOSuite

/** Predictive checks: what the model says the data should look like, against what it does.
  *
  * The statistic is the number of reports that arrived in each calendar period — the diagonal of
  * the grid, `month + delay = period` — because that is the series anyone has actually seen. How
  * many events were *created* in the recent months is the thing nobody knows, and checking a model
  * against it would be checking it against its own guess.
  *
  * Two claims. The band is the replications' own quantiles, period by period, and `outsideBand`
  * counts the periods the observation missed — where **zero is as much a warning as half**, since
  * a band nothing escapes is a band that is too wide to say anything. And the posterior check has
  * to carry both kinds of uncertainty: the parameters' and the Poisson's.
  */
object ChecksSuite extends SimpleIOSuite with Approx:

  private val months = 8
  private val maxLag = 3
  private val t = IArray.tabulate(months)(m => (m - 3.5) / 8.0)
  private val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)

  private val truth = DelayCountParams[Ar1TrendParams, OrderedLogitDriftParams, Double](
    Ar1TrendParams(
      IArray(3.2),
      IArray(0.4),
      0.5,
      0.15,
      IArray.tabulate(months)(m => math.sin(m.toDouble))
    ),
    OrderedLogitDriftParams(0.6, IArray(0.7, 0.5), 0.3)
  )

  private val blank = DelayCountData.blank(months, maxLag, t, visible).toOption.get
  private val generative = DelayCountModel.generative(Ar1Trend(), OrderedLogitDrift(), blank)
  private val observed =
    generative.generative.simulateData[Rand](truth).runA(Seed(20261106L)).value
  private val model = DelayCountModel(Ar1Trend(), OrderedLogitDrift(), observed)

  pureTest("the statistic is the diagonal of the grid: reports that arrived in each period") {
    val arrivals = Checks.arrivals(observed)
    // Period 0 can only hold month 0 at delay 0; period 1 holds (0,1) and (1,0); and so on until
    // the mask runs out. Summed by hand from the simulated grid.
    val byHand = IArray.tabulate(months + maxLag) { p =>
      (0 until months).foldLeft(0.0) { (sum, m) =>
        val k = p - m
        if k < 0 || k > maxLag || !visible.isVisible(m, k) then sum
        else sum + observed.counts(0)(m)(k)
      }
    }
    check(arrivals.length == months + maxLag, s"${arrivals.length} periods")
      .and(
        (0 until arrivals.length)
          .map(p => closeRel(arrivals(p), byHand(p), 1e-15, s"the arrivals in period $p"))
          .reduce(_.and(_))
      )
      // The visible grid's total is the arrivals' total: every reported cell arrives exactly once.
      .and(
        closeRel(
          (0 until arrivals.length).foldLeft(0.0)((s, p) => s + arrivals(p)),
          observed.visibleTotal.toDouble,
          1e-15,
          "the arrivals against the visible total"
        )
      )
  }

  pureTest("the band is the replications' own quantiles, and nothing is counted twice") {
    val sample = PredictiveSample(
      "made up",
      IArray(5.0, 50.0),
      IArray.tabulate(100)(r => IArray(r.toDouble, 100.0 - r.toDouble))
    )
    val band = sample.band(0.9)
    check(band.length == 2, s"${band.length} periods in the band")
      // Type-7 quantiles of 0..99 at 5 % and 95 %: 4.95 and 94.05.
      .and(closeRel(band(0)._1, 4.95, 1e-13, "the lower end of the first period"))
      .and(closeRel(band(0)._2, 94.05, 1e-13, "the upper end of the first period"))
      // The first observation, 5, is inside; the second, 50, is inside too.
      .and(check(sample.outsideBand(0.9) == 0, s"${sample.outsideBand(0.9)} periods outside"))
      .and {
        val extreme = sample.copy(observed = IArray(-1.0, 50.0))
        check(extreme.outsideBand(0.9) == 1, s"${extreme.outsideBand(0.9)} periods outside")
      }
      .and(check(sample.band(1.5).forall((l, _) => l.isNaN), "a mass above one gave a band"))
      .and(
        check(
          PredictiveSample("none", IArray(1.0), IArray.empty).outsideBand(0.9) == 1,
          "no replications still covered the observation"
        )
      )
  }

  pureTest("the prior predictive simulates from the priors alone, and spans orders of magnitude") {
    val sample = Checks.priorPredictive(model, draws = 200, Seed(7L))
    val totals = IArray.tabulate(sample.replications) { r =>
      (0 until sample.periods).foldLeft(0.0)((s, p) => s + sample.replicated(r)(p))
    }
    val sorted = IArray.from(totals.toList.sorted)
    check(sample.replications == 200, s"${sample.replications} replications")
      .and(check(sample.periods == months + maxLag, s"${sample.periods} periods"))
      .and(check(sample.observed.toList == Checks.arrivals(observed).toList, "the observed series"))
      // The priors here are deliberately vague — a level of Normal(0, 2) on the log scale — so the
      // replications have to span orders of magnitude. If they did not, the prior would be far
      // more informative than it looks, which is exactly what this check exists to reveal.
      .and(
        check(
          sorted(sorted.length - 1) > 100.0 * math.max(sorted(sorted.length / 2), 1.0),
          f"the prior's totals run from ${sorted.head}%.1f to ${sorted(sorted.length - 1)}%.1f, " +
            f"with a median of ${sorted(sorted.length / 2)}%.1f, which is not a vague prior"
        )
      )
      .and(
        check(
          Checks.priorPredictive(model, 0, Seed(1L)).replications == 0,
          "no draws, no replications"
        )
      )
  }

  test("the posterior predictive covers about the right share of periods") {
    // The criterion of 8.2: on data simulated from the model, about 90 % of the periods fall inside
    // the 90 % band. With eleven periods that is ten or eleven of them; the test allows nine, which
    // is the honest reading of "about" at this length — one period in eleven is already 9 %.
    val settings = RunSettings(chains = 2, warmup = 600, samples = 1000)
    val fitted = Mcmc.runNuts(model, settings).runA(Seed(4711L)).value
    cats.effect.IO {
      fitted match
        case Left(error) => failure(error.message)
        case Right(chains) =>
          val draws = Mcmc.draws(model, chains)
          draws.flatMap(d => Checks.posteriorPredictive(model, d, Seed(99L))) match
            case Left(error) => failure(error.message)
            case Right(sample) =>
              val outside = sample.outsideBand(0.9)
              val inside = sample.periods - outside
              check(
                inside >= sample.periods - 2,
                s"$inside of ${sample.periods} periods are inside the 90 % band"
              )
                // And the band is not vacuous: a band that contained everything whatever happened
                // would pass the check above and mean nothing, so the observed series has to be
                // somewhere a shifted one would not be.
                .and {
                  val shifted = sample.copy(observed = sample.observed.map(_ * 3.0 + 50.0))
                  check(
                    shifted.outsideBand(0.9) >= sample.periods - 2,
                    s"a series shifted far away is still inside: ${shifted.outsideBand(0.9)} outside"
                  )
                }
          end match
    }
  }
