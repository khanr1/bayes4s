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
import weaver.SimpleIOSuite

/** The count model itself: a rate, a delay, and the cells that have been reported.
  *
  * The claim that matters most is the dullest to state: **only the visible cells are read**. A
  * nowcast exists because the recent months are incomplete, so a likelihood that quietly treated
  * an unreported cell as a zero would be confidently wrong about exactly the months anyone cares
  * about. Two tests pin it — a hand-written sum over the visible cells, and the fact that changing
  * an invisible count changes nothing at all.
  *
  * The gradient is checked against the dual-number oracle, as the ported posteriors' are: the
  * model is written once against `Real[A]` and the tape has to differentiate the whole assembly —
  * the AR(1) recursion, the cumulative cut points, the ordered logistic, the Poisson.
  */
object DelayCountSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val months = 6
  private val maxLag = 3
  private val t = IArray.tabulate(months)(m => (m - 2.5) / 6.0)

  /** Right-censored: a cell is visible once its reporting month is in the past. */
  private val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)

  private val counts = IArray(
    IArray(
      IArray(5, 3, 2, 1),
      IArray(7, 4, 2, 1),
      IArray(6, 3, 1, 2),
      IArray(8, 5, 2, 0),
      IArray(9, 4, 0, 0),
      IArray(7, 0, 0, 0)
    )
  )

  private val data = DelayCountData.of(t, counts, visible).toOption.get
  private val model = DelayCountModel(Ar1Trend(), OrderedLogitDrift(), data)

  private val at =
    IArray.tabulate(model.params.unconstrainedSize)(i => 0.25 * math.cos(i * 0.7))
  private val point = Model.constrain(model, at)

  pureTest("the data knows its own shape, and refuses one it cannot hold") {
    val wrongT = DelayCountData.of(IArray(0.0), counts, visible)
    val raggedDelays = DelayCountData.of(
      t,
      IArray(IArray.tabulate(months)(m => IArray.fill(if m == 0 then 2 else maxLag + 1)(0))),
      visible
    )
    val wrongMask = DelayCountData.of(t, counts, ReportingTriangle.rightCensored(months, 2, months))
    check(data.groups == 1 && data.months == months && data.maxLag == maxLag, "the shape is wrong")
      .and(check(wrongT.isLeft, "a time axis of the wrong length was accepted"))
      .and(check(raggedDelays.isLeft, "a ragged delay axis was accepted"))
      .and(check(wrongMask.isLeft, "a mask of the wrong width was accepted"))
      .and(check(DelayCountData.of(t, IArray.empty, visible).isLeft, "no groups was accepted"))
  }

  pureTest("the visible cells are exactly the lower triangle, and nothing else is read") {
    // Month m is visible out to delay `months - 1 - m`: six months of history, so the first month
    // has all four delays and the last has only delay zero.
    val perMonth = (0 until months).map(m => (0 to maxLag).count(k => visible.isVisible(m, k)))
    check(perMonth.toList == List(4, 4, 4, 3, 2, 1), s"the visible counts per month are $perMonth")
      .and(check(data.cells.length == 18, s"${data.cells.length} visible cells"))
      // Summed by hand over the visible part: 11 + 14 + 12 + 15 + 13 + 7.
      .and(check(data.visibleTotal == 72, s"the visible counts total ${data.visibleTotal}, not 72"))
      // This fixture carries zeros in the cells the mask excludes, which is how such data usually
      // arrives — a table of counts has a number in every cell whether or not it is final. That is
      // exactly why the mask has to be load-bearing rather than coincidental, and why the next
      // test puts a thousand in an unreported cell instead of trusting the zeros.
      .and(
        check(
          counts(0).toList.map(_.sum).sum == data.visibleTotal,
          "the fixture no longer has zeros outside the mask, so the next test is the only guard"
        )
      )
  }

  pureTest("the log likelihood is the Poisson sum over the visible cells, by hand") {
    val logRate = Ar1Trend().logRate[Double](point.rate, 0, t)
    val byHand = (0 until months).foldLeft(0.0) { (sum, m) =>
      val logPmf = OrderedLogitDrift().logPmf[Double](point.delay, 0, t(m))
      (0 to maxLag).foldLeft(sum) { (inner, k) =>
        if !visible.isVisible(m, k) then inner
        else inner + LogMass.poissonLog(counts(0)(m)(k), logRate(m) + logPmf(k))
      }
    }
    closeRel(
      model.logLikelihood[Double](point),
      byHand,
      1e-13,
      "the likelihood against the hand sum"
    )
  }

  pureTest("an unreported cell cannot change the answer, and a reported one must") {
    // The cell (5, 3) is outside the mask: it has not been reported yet. Putting a thousand in it
    // must leave the likelihood untouched — this is what "the recent months are incomplete" means
    // in code, and treating it as a zero instead would be a silent, confident error.
    val changedInvisible = counts.map(_.toList.zipWithIndex.map { (row, m) =>
      if m == 5 then IArray(row(0), row(1), row(2), 1000) else row
    })
    val invisible = DelayCountData
      .of(t, IArray(IArray.from(changedInvisible.head)), visible)
      .toOption
      .get
    val changedVisible = counts.map(_.toList.zipWithIndex.map { (row, m) =>
      if m == 0 then IArray(row(0) + 50, row(1), row(2), row(3)) else row
    })
    val altered = DelayCountData
      .of(t, IArray(IArray.from(changedVisible.head)), visible)
      .toOption
      .get
    val base = model.logLikelihood[Double](point)
    val withInvisible = DelayCountModel(Ar1Trend(), OrderedLogitDrift(), invisible)
      .logLikelihood[Double](point)
    val withVisible = DelayCountModel(Ar1Trend(), OrderedLogitDrift(), altered)
      .logLikelihood[Double](point)
    check(
      withInvisible == base,
      s"an invisible cell moved the likelihood from $base to $withInvisible"
    )
      .and(check(withVisible != base, "a visible cell left the likelihood alone"))
  }

  pureTest("the gradient agrees with the dual-number oracle, and with central differences") {
    val target = Model.target(model)
    val taped = Reverse.valueAndGradient(target, at)
    val oracle = Dual.gradient(target, at)
    val differences = GradientCheck(target, at, 1e-5)
    closeRel(taped.value, Dual.value(target, at), 1e-13, "the value")
      .and(
        (0 until at.length)
          .map(i => closeRel(taped.gradient(i), oracle(i), 1e-11, s"d/du($i)"))
          .reduce(_.and(_))
      )
      .and(
        check(
          differences.maxError < 1e-5,
          s"central differences disagree by ${differences.maxError}"
        )
      )
      .and(check(taped.value.isFinite, s"the target is ${taped.value}"))
  }

  pureTest("the derived rate is the rate the component computed") {
    val lambda = model.derived(point)("lambda")
    val logRate = Ar1Trend().logRate[Double](point.rate, 0, t)
    check(lambda.length == months, s"${lambda.length} rates")
      .and(
        (0 until months)
          .map(m => closeRel(lambda(m), math.exp(logRate(m)), 1e-14, s"the rate in month $m"))
          .reduce(_.and(_))
      )
  }

  test("the generative part produces counts with the means the parameters imply") {
    // One fixed parameter point, many simulated datasets: each visible cell's mean count has to be
    // `lambda[m] * pmf[m](k)`, which is what thinning a Poisson rate by the delay distribution
    // means. The standard error is sqrt(mean/replications), since the cell is Poisson.
    val replications = 3000
    val blank = DelayCountData.blank(months, maxLag, t, visible).toOption.get
    val generative = DelayCountModel.generative(Ar1Trend(), OrderedLogitDrift(), blank)
    val truth = DelayCountParams[Ar1TrendParams, OrderedLogitDriftParams, Double](
      Ar1TrendParams(IArray(3.0), IArray(0.5), 0.5, 0.1, IArray.fill(months)(0.0)),
      OrderedLogitDriftParams(0.5, IArray(0.7, 0.6), 0.8)
    )
    val simulated = IArray.tabulate(replications) { i =>
      generative.generative
        .simulateData[Rand](truth)
        .runA(Seed(20261106L).fold(i.toLong))
        .value
    }
    val logRate = Ar1Trend().logRate[Double](truth.rate, 0, t)
    cats.effect.IO {
      val checks = (0 until months).toList.flatMap { m =>
        val logPmf = OrderedLogitDrift().logPmf[Double](truth.delay, 0, t(m))
        (0 to maxLag).toList.map { k =>
          val mean =
            (0 until replications).foldLeft(0.0)((s, i) => s + simulated(i).counts(0)(m)(k)) /
              replications
          val expected = math.exp(logRate(m)) * math.exp(logPmf(k))
          withinStandardErrors(
            mean,
            expected,
            math.sqrt(expected / replications),
            4.0,
            s"the mean count in month $m at delay $k"
          )
        }
      }
      check(checks.length == months * (maxLag + 1), "not every cell was checked")
        .and(checks.reduce(_.and(_)))
    }
  }

  pureTest("the generative part names the parameters calibration will rank") {
    val blank = DelayCountData.blank(months, maxLag, t, visible).toOption.get
    val generative = DelayCountModel.generative(Ar1Trend(), OrderedLogitDrift(), blank)
    val drawn = generative.generative.samplePrior[Rand].runA(Seed(3L)).value
    val named = generative.generative.named(drawn)
    check(
      named.keySet == Set("a", "b", "phi", "tau", "cut0", "beta"),
      s"the named parameters are ${named.keySet}"
    )
      .and(
        check(named("phi").length == 1 && math.abs(named("phi")(0)) < 1.0, "phi is outside (-1, 1)")
      )
      .and(check(named("tau")(0) >= 0.0, "a negative wobble scale"))
      // And the model it conditions on reads the data it was given.
      .and {
        val simulated = generative.generative.simulateData[Rand](drawn).runA(Seed(4L)).value
        val conditioned = generative.conditionedOn(simulated)
        check(
          Reverse.value(Model.target(conditioned), at).isFinite,
          "the conditioned model's target is not a number"
        )
      }
  }
