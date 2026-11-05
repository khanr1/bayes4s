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

import bayes4s.syntax.{*, given}
import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import bayes4s.testkit.MonteCarlo
import cats.Id
import cats.Monad
import cats.syntax.all.*
import io.circe.Json
import weaver.SimpleIOSuite

/** The summary a user reads, and the intervals it reports.
  *
  * Two things are checked that no convergence statistic covers. The **names** have to be the
  * model's own, so that a row of a table can be matched to a line of the model; and the
  * **intervals** have to be the intervals they claim, which is testable because
  * `worked_examples.json` states all four of them in closed form for a Gamma(33.5, 4.2).
  */
object SummarySuite extends SimpleIOSuite with MonteCarlo with Approx:

  private val draws = 200_000

  private val gammaDraws: Either[String, IArray[Double]] =
    Gamma
      .of(33.5, 4.2)
      .left
      .map(_.message)
      .map { law =>
        IArray.from(Monad[Rand].replicateA(draws, law.sample[Rand]).runA(Seed(20261105L)).value)
      }

  private def conjugate(field: String): Either[String, Json] =
    Fixtures.workedExample("gamma_poisson_conjugate").flatMap { json =>
      json.hcursor.downField(field).focus.toRight(s"no field '$field'")
    }

  /** All four interval statistics of `worked_examples.json`, against draws from the same law.
    *
    * The tolerance is the Monte Carlo error of each, which for a quantile is
    * `sqrt(p(1-p)/n) / density` and for a probability `sqrt(p(1-p)/n)`. Stating it that way is
    * what makes the comparison a test rather than a hope.
    */
  pureTest("the intervals and exceedance probabilities of worked_examples.json") {
    (gammaDraws, conjugate("equal_tailed_90"), conjugate("hpd_90"), conjugate("p_gt_10")) match
      case (Right(sample), Right(equalTailed), Right(hpd), Right(above)) =>
        val expectedEqual = equalTailed.as[List[Double]].getOrElse(Nil)
        val expectedHpd = hpd.as[List[Double]].getOrElse(Nil)
        val expectedAbove = above.as[Double].getOrElse(Double.NaN)
        val (low, high) = Intervals.equalTailed(sample, 0.9)
        val (hpdLow, hpdHigh) = Intervals.hpd(sample, 0.9)
        val probability = Intervals.probabilityAbove(sample, 10.0)
        // The density of Gamma(33.5, 4.2) at its 5th and 95th percentiles, for the quantile's
        // standard error.
        def density(x: Double): Double =
          math.exp(Gamma.of(33.5, 4.2).fold(_ => Double.NaN, _.logPdf(x)))
        def quantileError(p: Double, at: Double): Double =
          math.sqrt(p * (1.0 - p) / draws) / density(at)
        val probabilityError =
          math.sqrt(expectedAbove * (1.0 - expectedAbove) / draws)
        check(expectedEqual.length == 2 && expectedHpd.length == 2, "the fixture's shape changed")
          .and(
            withinStandardErrors(
              low,
              expectedEqual(0),
              quantileError(0.05, expectedEqual(0)),
              4.0,
              "the 5th percentile"
            )
          )
          .and(
            withinStandardErrors(
              high,
              expectedEqual(1),
              quantileError(0.95, expectedEqual(1)),
              4.0,
              "the 95th percentile"
            )
          )
          // The highest-density interval of a skewed law is narrower than the equal-tailed one and
          // sits lower; both facts are asserted, because an implementation that returned the
          // equal-tailed interval would otherwise pass.
          .and(
            withinStandardErrors(
              hpdLow,
              expectedHpd(0),
              quantileError(0.05, expectedHpd(0)),
              4.0,
              "the lower end of the highest-density interval"
            )
          )
          .and(
            withinStandardErrors(
              hpdHigh,
              expectedHpd(1),
              quantileError(0.95, expectedHpd(1)),
              4.0,
              "its upper end"
            )
          )
          .and(
            check(
              hpdHigh - hpdLow < high - low,
              s"the highest-density interval (${hpdHigh - hpdLow}) is not narrower than the " +
                s"equal-tailed one (${high - low}), which it must be for a skewed law"
            )
          )
          .and(
            withinStandardErrors(
              probability,
              expectedAbove,
              probabilityError,
              4.0,
              "the probability of exceeding 10"
            )
          )
      case _ => failure("the fixture or the draws could not be read")
  }

  pureTest("the second exceedance probability of the fixture, and the two ends of the scale") {
    (gammaDraws, conjugate("p_gt_6")) match
      case (Right(sample), Right(json)) =>
        val expected = json.as[Double].getOrElse(Double.NaN)
        val got = Intervals.probabilityAbove(sample, 6.0)
        withinStandardErrors(
          got,
          expected,
          math.sqrt(expected * (1.0 - expected) / draws),
          4.0,
          "the probability of exceeding 6"
        )
          .and(
            check(
              Intervals.probabilityAbove(sample, -1.0) == 1.0,
              "every draw of a Gamma is above -1"
            )
          )
          .and(
            check(
              Intervals.probabilityAbove(sample, 1e9) == 0.0,
              "no draw of a Gamma is above a billion"
            )
          )
      case _ => failure("the fixture or the draws could not be read")
  }

  pureTest("the intervals of a sample they cannot summarise") {
    val empty = IArray.empty[Double]
    val one = IArray(3.0)
    check(Intervals.equalTailed(empty, 0.9)._1.isNaN, "an empty sample has no interval")
      .and(check(Intervals.hpd(empty, 0.9)._1.isNaN, "nor a highest-density one"))
      .and(check(Intervals.probabilityAbove(empty, 0.0).isNaN, "nor a probability"))
      .and(
        check(Intervals.equalTailed(one, 0.9) == (3.0, 3.0), "a single draw is its own interval")
      )
      .and(check(Intervals.hpd(one, 0.9) == (3.0, 3.0), "and its own shortest interval"))
      .and(check(Intervals.equalTailed(one, 1.5)._1.isNaN, "a mass above one is not a mass"))
      .and(check(Intervals.hpd(one, 0.0)._1.isNaN, "nor is a mass of zero"))
  }

  /** The shortest interval of a two-humped sample covers the gap, which is the thing a reader has
    * to know about it: reporting one interval for two modes is the mistake, and the width is what
    * reveals it.
    */
  pureTest("the shortest interval of a two-humped sample is still an interval, and says so") {
    val humps = IArray.from(
      (0 until 1000).map(i => if i % 2 == 0 then -3.0 + 0.001 * i else 3.0 + 0.001 * i)
    )
    val (low, high) = Intervals.hpd(humps, 0.9)
    check(low < 0.0 && high > 0.0, s"the interval ($low, $high) does not cover the gap")
      .and(check(high - low > 5.0, s"its width is ${high - low}, which hides the two modes"))
  }

  /** A model's own names, in its own order, and `lp__` first as `stansummary` has it. */
  pureTest("a summary names the rows the way the model names its parameters") {
    val model: Model[Id] = new Model[Id]:
      val params: ParamSpec[Id] = ParamSpec.positive("rate")
      def logPrior[A: Real](rate: A): A = LogDensity.gamma(rate, 2.0.lift[A], 0.5.lift[A])
      def logLikelihood[A: Real](rate: A): A = Real[A].const(0.0)
    val target = Model.target(model)
    val chains = (0 until 2).toList.map { c =>
      Mcmc
        .run[Rand](
          Nuts.kernel[Rand](target, NutsSettings()),
          target,
          IArray(0.5),
          500,
          1000,
          AdaptationSettings()
        )
        .runA(Seed(99L).fold(c.toLong))
        .value
    }
    chains.partitionMap(identity) match
      case (Nil, fitted) =>
        val rows = Summary.of(model, fitted)
        val rate = rows.find(_.name == "rate")
        val table = Summary.table(model, fitted)
        check(rows.map(_.name) == List("lp__", "rate"), s"the rows are ${rows.map(_.name)}")
          .and(
            rate.fold(failure("no row for the rate")) { r =>
              // Gamma(2, 0.5) has mean 4 and standard deviation sqrt(2)/0.5 = 2.83.
              withinStandardErrors(r.mean, 4.0, r.mcseMean, 4.0, "the posterior mean of the rate")
                .and(check(r.sd > 2.0 && r.sd < 3.6, s"its standard deviation is ${r.sd}"))
                .and(check(r.q05 < r.q50 && r.q50 < r.q95, "the quantiles are out of order"))
                .and(check(r.rhat < 1.01, s"R-hat is ${r.rhat}"))
                .and(check(r.essBulk > 200.0, s"the bulk effective size is ${r.essBulk}"))
                .and(check(r.essTail > 200.0, s"the tail effective size is ${r.essTail}"))
            }
          )
          .and(check(table.linesIterator.length == 3, "the table has the wrong number of lines"))
          .and(check(table.contains("ESS_bulk"), "the table has no header"))
          .and(check(table.contains("rate"), "the table does not name the rate"))
          // A table formatted in the default locale writes a comma for the decimal point in much
          // of the world, and the same fit then prints differently on two machines.
          .and(check(!table.contains(","), s"the table is locale-dependent:\n$table"))
          .and(check(table.contains("."), "the table has no decimal point at all"))
      case (error :: _, _) => failure(error.message)
  }

  /** A summary of a model with a vector gets one row per value, named as CmdStan names them. */
  pureTest("a vector parameter gets one row per value") {
    EightSchools.Data.of(
      IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0),
      IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
    ) match
      case Left(error) => failure(error.message)
      case Right(data) =>
        val model = EightSchools.nonCentred(data)
        Mcmc
          .runNuts(model, RunSettings(chains = 2, warmup = 600, samples = 600))
          .runA(Seed(20261105L))
          .value match
          case Left(error) => failure(error.message)
          case Right(chains) =>
            val rows = Summary.of(model, chains)
            val names = rows.map(_.name)
            check(
              names == List("lp__", "mu", "tau") ++ (1 to 8).map(i => s"z.$i"),
              s"the rows are $names"
            )
              .and(check(rows.forall(r => r.rhat < 1.05), s"an R-hat is ${rows.map(_.rhat).max}"))
              .and(
                check(
                  rows.forall(r => r.essBulk > 100.0),
                  s"the smallest bulk effective size is ${rows.map(_.essBulk).min}"
                )
              )
              // `tau` is positive, so every quantile of it must be.
              .and(
                rows
                  .find(_.name == "tau")
                  .fold(failure("no row for tau"))(r =>
                    check(r.q05 > 0.0, s"the 5th percentile of tau is ${r.q05}")
                  )
              )
  }

  /** E-BFMI has no reference value, so it is checked against its own closed form. */
  pureTest("the energy diagnostic is 2 for independent energies, and low for a stuck chain") {
    val independent = IArray.from(
      Monad[Rand].replicateA(20_000, Rng[Rand].normal).runA(Seed(7L)).value.map(_ * 3.0 + 10.0)
    )
    val stuck = IArray.tabulate(20_000)(i => 10.0 + 0.001 * i)
    val value = SamplerReport.eBfmi(independent)
    // For independent energies E[(E_n - E_{n-1})^2] = 2 var(E), so the ratio is 2.
    check(math.abs(value - 2.0) < 0.1, s"independent energies gave $value, expected 2")
      .and(
        check(
          SamplerReport.eBfmi(stuck) < 0.01,
          s"a drifting energy gave ${SamplerReport.eBfmi(stuck)}, which should be near zero"
        )
      )
      .and(check(SamplerReport.eBfmi(IArray(1.0)).isNaN, "one energy is not a diagnosis"))
      .and(
        check(
          SamplerReport.eBfmi(IArray.fill(10)(1.0)).isNaN,
          "a constant energy has no variance to divide by"
        )
      )
  }

  pureTest("the sampler report counts what the sampler reported") {
    val target = Targets.standardGaussian
    val tiny = Tuning(1e-4, Metric.unit(2))
    val kernel = Nuts.kernel[Rand](target, NutsSettings(maxTreeDepth = 5))
    val point = Point.at(target, IArray(0.2, -0.1))
    val transitions =
      Monad[Rand].replicateA(30, kernel.transition(point, tiny)).runA(Seed(3L)).value
    val chain = Chain(transitions.map(_.point.q).toVector, transitions.toVector, tiny)
    val report = SamplerReport.of(List(chain), maxTreeDepth = 5)
    check(report.saturatedDepth == 30, s"${report.saturatedDepth} of 30 saturated the depth")
      .and(check(report.divergent == 0, s"${report.divergent} divergent transitions"))
      .and(check(report.eBfmi.length == 1, "one chain, one energy diagnostic"))
      // A step size too small to move does **not** give a low energy diagnostic, and that is worth
      // asserting: the momentum is drawn afresh every transition, so the energy marginal is
      // explored whatever the trajectory does. What E-BFMI sees is the next test.
      .and(
        check(
          report.lowEnergyChains().isEmpty,
          s"a tiny step size was flagged by the energy diagnostic (${report.eBfmi}), which is not " +
            "what that diagnostic is about"
        )
      )
  }

  /** The energy diagnostic on the posterior it exists for.
    *
    * Betancourt (2018): a centred funnel has an energy distribution the momentum refresh cannot
    * cross, and E-BFMI falls far below the rule of thumb of 0.3. Measured here: 0.11 and 0.09 on
    * the funnel against 1.05 and 1.12 on a Gaussian of the same dimension, with 7 divergent
    * transitions against none. Both halves matter — a diagnostic that flagged everything would be
    * as useless as one that flagged nothing.
    */
  pureTest("the energy diagnostic flags the funnel and leaves a Gaussian alone") {
    def fit(target: DiffFn, dimension: Int): Either[BayesError, List[Chain]] =
      (0 until 2).toList
        .traverse { c =>
          Mcmc
            .run[Rand](
              Nuts.kernel[Rand](target, NutsSettings()),
              target,
              IArray.fill(dimension)(0.0),
              600,
              1000,
              AdaptationSettings()
            )
            .runA(Seed(20261105L).fold(c.toLong))
            .value
        }
        .map(_.toList)
    (fit(Targets.funnel(10), 10), fit(Targets.standardGaussian, 10)) match
      case (Right(funnel), Right(gaussian)) =>
        val hard = SamplerReport.of(funnel, 10)
        val easy = SamplerReport.of(gaussian, 10)
        check(
          hard.lowEnergyChains().length == 2,
          s"the funnel's energy diagnostics are ${hard.eBfmi}, and ${hard.lowEnergyChains().length} " +
            "of two chains were flagged"
        )
          .and(
            check(
              easy.lowEnergyChains().isEmpty,
              s"a Gaussian was flagged; its energy diagnostics are ${easy.eBfmi}"
            )
          )
          .and(
            check(
              easy.eBfmi.forall(v => v > 0.8 && v < 1.5),
              s"a Gaussian's energy diagnostic should sit near 1, not ${easy.eBfmi}"
            )
          )
          .and(check(hard.divergent > 0, "the funnel produced no divergent transition"))
          .and(check(easy.divergent == 0, s"a Gaussian produced ${easy.divergent} divergences"))
      case (Left(error), _) => failure(s"the funnel: ${error.message}")
      case (_, Left(error)) => failure(s"the Gaussian: ${error.message}")
  }
