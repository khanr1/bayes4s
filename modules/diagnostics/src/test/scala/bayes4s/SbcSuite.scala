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

/** The machinery of simulation-based calibration, and a first look at its answer.
  *
  * The calibration itself needs hundreds of fits for its chi-squared to mean anything, and that
  * lives in `src/slow/scala`. What is here is cheap and catches the mistakes that make the slow
  * suite meaningless: ranks outside their range, a parameter the generative part names and the
  * model does not, a statistic computed over bins that do not divide the ranks evenly.
  */
object SbcSuite extends SimpleIOSuite with Approx:

  private val quick = RunSettings(chains = 2, warmup = 300, samples = 300)

  pureTest("the ranks are in range, one per replication, for every parameter") {
    val model = CalibrationModels.locationScale(12)
    Sbc.run(model, quick, replications = 16, thinnedDraws = 31).runA(Seed(1L)).value match
      case Left(error) => failure(error.message)
      case Right(result) =>
        val names = result.ranks.keySet
        check(names == Set("mu", "sigma"), s"the parameters are $names")
          .and(
            forEach(result.ranks.toList) { (name, ranks) =>
              check(ranks.length == 16, s"$name has ${ranks.length} ranks, expected 16")
                .and(
                  check(
                    (0 until ranks.length).forall(i => ranks(i) >= 0 && ranks(i) <= 31),
                    s"$name has a rank outside 0..31: ${ranks.toList}"
                  )
                )
            }
          )
          .and(check(result.thinnedDraws == 31, "the thinning was not what was asked for"))
  }

  pureTest("a vector parameter gets one rank per value, named as CmdStan names it") {
    val model = CalibrationModels.varyingLevels(4)
    Sbc.run(model, quick, replications = 8, thinnedDraws = 15).runA(Seed(2L)).value match
      case Left(error) => failure(error.message)
      case Right(result) =>
        check(
          result.ranks.keySet == Set("mu", "tau", "z.1", "z.2", "z.3", "z.4"),
          s"the parameters are ${result.ranks.keySet.toList.sorted}"
        ).and(
          forEach(result.ranks.toList) { (name, ranks) =>
            check(ranks.length == 8, s"$name has ${ranks.length} ranks")
          }
        )
  }

  /** The statistic itself, on ranks whose answer is known.
    *
    * Uniform ranks must give about `bins - 1`, ranks all in one bin must give a large number, and
    * bins that do not divide the possible ranks evenly must give `NaN` rather than a statistic
    * that would reject a correct sampler.
    */
  pureTest("the chi-squared statistic says what it should on ranks that are known") {
    val uniform = SbcRanks(
      Map("flat" -> IArray.tabulate(256)(i => i % 64)),
      thinnedDraws = 63,
      replications = 256
    )
    val piled = SbcRanks(
      Map("piled" -> IArray.fill(256)(0)),
      thinnedDraws = 63,
      replications = 256
    )
    val awkward = SbcRanks(
      Map("awkward" -> IArray.tabulate(100)(i => i % 50)),
      thinnedDraws = 49,
      replications = 100
    )
    val flat = uniform.uniformityChiSquared(16)("flat")
    val heap = piled.uniformityChiSquared(16)("piled")
    // 16 bins over 64 possible ranks is 4 ranks a bin; over 50 it is not a whole number.
    val bad = awkward.uniformityChiSquared(16)("awkward")
    check(flat == 0.0, s"perfectly uniform ranks gave $flat, expected exactly 0")
      .and(check(heap > 1000.0, s"ranks all in one bin gave $heap"))
      .and(check(bad.isNaN, s"bins that do not divide the ranks gave $bad, expected NaN"))
      .and(
        check(
          uniform.miscalibrated(16, 37.697).isEmpty,
          "uniform ranks were called miscalibrated"
        )
      )
      .and(
        check(
          piled.miscalibrated(16, 37.697) == List("piled"),
          "piled ranks were not called miscalibrated"
        )
      )
  }

  /** A model whose two readings disagree must fail, or the whole technique says nothing.
    *
    * The generative part draws `mu` from a Gaussian of scale 2 and the density believes it has
    * scale 2; here the density is told 0.5 instead, which is the mistake simulation-based
    * calibration exists to find — a prior written twice and differently. Sixteen replications are
    * enough for a disagreement this large.
    */
  pureTest("a prior that disagrees with its own simulation is caught") {
    val honest = CalibrationModels.gammaPoisson(20)
    val wrong = GenerativeModel(
      honest.observed,
      data =>
        new Model[cats.Id]:
          val params: ParamSpec[cats.Id] = ParamSpec.positive("rate")
          def logPrior[A: Real](rate: A): A =
            // The simulation draws from Gamma(3, 1); this says Gamma(3, 4).
            LogDensity.gamma(rate, Real[A].const(3.0), Real[A].const(4.0))
          def logLikelihood[A: Real](rate: A): A =
            honest.conditionedOn(data).logLikelihood(rate)
      ,
      honest.generative
    )
    val settings = RunSettings(chains = 2, warmup = 300, samples = 400)
    val truthful = Sbc.run(honest, settings, replications = 48, thinnedDraws = 31)
    val mistaken = Sbc.run(wrong, settings, replications = 48, thinnedDraws = 31)
    (truthful.runA(Seed(11L)).value, mistaken.runA(Seed(11L)).value) match
      case (Right(good), Right(bad)) =>
        val honestStatistic = good.uniformityChiSquared(8)("rate")
        val wrongStatistic = bad.uniformityChiSquared(8)("rate")
        // The 0.1 % point of chi-squared with 7 degrees of freedom is 24.32.
        check(
          wrongStatistic > 24.32,
          s"the mismatched prior gave a statistic of $wrongStatistic, which does not reject"
        ).and(
          check(
            honestStatistic < 24.32,
            s"the honest model gave $wrongStatistic for the wrong one and $honestStatistic for " +
              "itself, and the second rejects too, so the test separates nothing"
          )
        )
      case (Left(error), _) => failure(s"the honest model: ${error.message}")
      case (_, Left(error)) => failure(s"the mistaken model: ${error.message}")
  }
