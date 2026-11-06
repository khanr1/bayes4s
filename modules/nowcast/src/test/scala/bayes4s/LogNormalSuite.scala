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
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** Sizes that trend and depend on how late they were reported.
  *
  * `log A ~ Gaussian(mu + kappa t + omega log1p(lag), s)`: three statements in one line, on the
  * scale where a multiplicative story belongs. The regression is ordinary; what is worth asserting
  * is that it recovers what it was given, and that the gap between the mean and the median of the
  * amount is not forgotten — exponentiating the fitted mean gives the **median**, and a forecast
  * that adds amounts up wants the mean, which is larger by `exp(s²/2)`.
  */
object LogNormalSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val rows = 400
  private val truth =
    LogNormalTrendRegressionParams[Double](mu = 2.0, kappa = 0.6, omega = -0.3, s = 0.4)

  private val t = IArray.tabulate(rows)(i => (i - rows / 2.0) / rows)
  private val lag = IArray.tabulate(rows)(i => (i % 5).toDouble)

  private val logAmount =
    val program = (0 until rows).toList.traverse { i =>
      Rng[Rand].normal.map { z =>
        truth.mu + truth.kappa * t(i) + truth.omega * math.log1p(lag(i)) + truth.s * z
      }
    }
    IArray.from(program.runA(Seed(20261106L)).value)

  private val model = LogNormalTrendRegression(logAmount, t, lag)

  test("the regression recovers the parameters that generated the sizes") {
    val settings = RunSettings(chains = 2, warmup = 600, samples = 1500)
    cats.effect.IO {
      Mcmc.runNuts(model, settings).runA(Seed(99L)).value match
        case Left(error) => failure(error.message)
        case Right(chains) =>
          val samples = chains.flatMap(_.draws.map(q => Model.constrain(model, q)))
          def summary(f: LogNormalTrendRegressionParams[Double] => Double): (Double, Double) =
            val values = IArray.from(samples.map(f))
            val n = values.length
            val mean = (0 until n).foldLeft(0.0)((s, i) => s + values(i)) / n
            val sd = math.sqrt(
              (0 until n).foldLeft(0.0)((s, i) => s + math.pow(values(i) - mean, 2.0)) / (n - 1.0)
            )
            (mean, sd)
          // Four hundred rows: each parameter's posterior is tight enough that the truth has to be
          // within four posterior standard deviations of the mean, which is the honest statement
          // of "recovered" for a fit of this size.
          val checks = List(
            ("mu", summary(_.mu), truth.mu),
            ("kappa", summary(_.kappa), truth.kappa),
            ("omega", summary(_.omega), truth.omega),
            ("s", summary(_.s), truth.s)
          ).map { case (name, (mean, sd), value) =>
            check(
              math.abs(mean - value) <= 4.0 * sd,
              f"$name: the posterior is $mean%.4f +/- $sd%.4f against a true $value%.4f"
            )
          }
          checks.reduce(_.and(_))
    }
  }

  pureTest("the mean amount exceeds the median by exactly the lognormal factor") {
    val median = LogNormalTrendRegression.medianAmount(truth, 0.2, 3.0)
    val mean = LogNormalTrendRegression.meanAmount(truth, 0.2, 3.0)
    closeRel(mean / median, math.exp(truth.s * truth.s / 2.0), 1e-14, "the mean over the median")
      // At s = 0.4 the gap is 8.3 %: small enough to miss and large enough to matter once a few
      // thousand amounts are added up.
      .and(closeRel(mean / median, 1.0832871, 1e-6, "the factor at s = 0.4"))
      .and(
        closeRel(
          median,
          math.exp(truth.mu + truth.kappa * 0.2 + truth.omega * math.log1p(3.0)),
          1e-14,
          "the median itself"
        )
      )
      // A later report means a smaller amount here, since omega is negative.
      .and(
        check(
          LogNormalTrendRegression.medianAmount(truth, 0.2, 10.0) <
            LogNormalTrendRegression.medianAmount(truth, 0.2, 0.0),
          "a negative omega did not make late reports smaller"
        )
      )
  }

  test("the simulated sizes have the mean the parameters imply") {
    // The model's own claim, checked against the sizes it generates: the mean of exp(log A) at a
    // given t and lag is `exp(mu + kappa t + omega log1p(lag) + s²/2)`.
    val replications = 40_000
    val at = (0.2, 3.0)
    val drawn = IArray.tabulate(replications) { i =>
      Rng[Rand].normal
        .map(z =>
          math.exp(truth.mu + truth.kappa * at._1 + truth.omega * math.log1p(at._2) + truth.s * z)
        )
        .runA(Seed(11L).fold(i.toLong))
        .value
    }
    val mean = (0 until replications).foldLeft(0.0)((s, i) => s + drawn(i)) / replications
    val expected = LogNormalTrendRegression.meanAmount(truth, at._1, at._2)
    val median = LogNormalTrendRegression.medianAmount(truth, at._1, at._2)
    val variance = expected * expected * (math.exp(truth.s * truth.s) - 1.0)
    cats.effect.IO {
      withinStandardErrors(
        mean,
        expected,
        math.sqrt(variance / replications),
        4.0,
        "the mean simulated amount"
      ).and(
        closeRel(Quantile.quantile(drawn, 0.5), median, 0.02, "the median simulated amount")
      )
    }
  }
