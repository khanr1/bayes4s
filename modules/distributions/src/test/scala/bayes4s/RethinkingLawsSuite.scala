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
import bayes4s.testkit.Fixtures
import cats.Monad
import io.circe.Json
import weaver.SimpleIOSuite

/** The two laws the `rethinking` facade needs and the core did not have.
  *
  * `zeroInflatedPoisson` is a mixture: with probability `p` the count is zero because nothing
  * happened at all, and otherwise it is Poisson. It is written on the log scale throughout, so a
  * rate of 1e-300 and a mixing probability of 1e-12 are both exact where the probability-scale
  * form would have lost everything.
  *
  * `lkjCorr` is the LKJ density on a correlation **matrix** rather than on its Cholesky factor.
  * Stan's own `lkj_corr_test.cpp` is the reference, through `multilevel.json`: at the identity the
  * density is its normalising constant, and at the matrix with every correlation one half it is
  * that constant plus `(eta - 1) log det`.
  */
object RethinkingLawsSuite extends SimpleIOSuite with Approx:

  private def cases(json: Json, fn: String): List[(Map[String, Double], Double)] =
    json.hcursor
      .downField("densities")
      .values
      .toList
      .flatten
      .filter(_.hcursor.get[String]("fn").toOption.contains(fn))
      .map { entry =>
        val args = entry.hcursor.downField("args").as[Map[String, Double]].getOrElse(Map.empty)
        (args, entry.hcursor.get[Double]("log").getOrElse(Double.NaN))
      }

  pureTest("the zero-inflated Poisson matches the package's parameterisation") {
    Fixtures.rethinking match
      case Left(message) => failure(message)
      case Right(json) =>
        val entries = cases(json, "dzipois")
        val wrong = entries.collect {
          case (args, expected)
              if !closeEnough(
                LogMass.zeroInflatedPoisson(args("x").toInt, args("p"), args("lambda")),
                expected
              ) =>
            (
              args,
              LogMass.zeroInflatedPoisson(args("x").toInt, args("p"), args("lambda")),
              expected
            )
        }
        check(entries.length == 3, s"${entries.length} cases in the reference, not 3")
          .and(check(wrong.isEmpty, s"disagreeing: $wrong"))
          // `p = 0` has to collapse exactly onto the Poisson, not merely nearly.
          .and {
            val plain = LogMass.poisson(4, 2.5)
            val inflated = LogMass.zeroInflatedPoisson(4, 0.0, 2.5)
            check(plain == inflated, s"at p = 0: $inflated against $plain")
          }
  }

  pureTest("the zero-inflated Poisson is a probability distribution, and total") {
    val total = (0 to 400).foldLeft(0.0)((sum, k) =>
      sum + math.exp(LogMass.zeroInflatedPoisson(k, 0.35, 6.0))
    )
    closeRel(total, 1.0, 1e-12, s"the masses sum to $total")
      // Out of the support is -Inf; an invalid parameter is NaN. Never an exception.
      .and(check(LogMass.zeroInflatedPoisson(-1, 0.2, 3.0).isNegInfinity, "a negative count"))
      .and(check(LogMass.zeroInflatedPoisson(1, 1.2, 3.0).isNaN, "a probability above one"))
      .and(check(LogMass.zeroInflatedPoisson(1, 0.2, -1.0).isNaN, "a negative rate"))
      // At p = 1 every count but zero is impossible, and zero is certain.
      .and(check(LogMass.zeroInflatedPoisson(0, 1.0, 3.0) == 0.0, "at p = 1, zero is certain"))
      .and(check(LogMass.zeroInflatedPoisson(2, 1.0, 3.0).isNegInfinity, "at p = 1, two is not"))
  }

  pureTest("the zero-inflated Poisson's sampler has the mean and the zeros the law says") {
    val drawn = Monad[Rand]
      .replicateA(40000, Sampling.zeroInflatedPoisson[Rand](0.3, 5.0))
      .runA(Seed(20261112L))
      .value
    locally {
      val n = drawn.length.toDouble
      val mean = drawn.sum / n
      // Mean (1 - p) lambda = 3.5; variance of the mixture gives the standard error.
      val expected = 0.7 * 5.0
      val spread = math.sqrt(drawn.map(k => (k - mean) * (k - mean)).sum / n)
      val standardError = spread / math.sqrt(n)
      val zeros = drawn.count(_ == 0).toDouble / n
      val expectedZeros = 0.3 + 0.7 * math.exp(-5.0)
      close(mean, expected, 4.0 * standardError, s"the mean is $mean")
        .and(
          close(
            zeros,
            expectedZeros,
            4.0 * math.sqrt(expectedZeros * (1.0 - expectedZeros) / n),
            s"the share of zeros is $zeros"
          )
        )
    }
  }

  pureTest("the LKJ density on a correlation matrix is Stan's") {
    Fixtures.multilevel match
      case Left(message) => failure(message)
      case Right(json) =>
        val entries = json.hcursor.downField("lkj").downField("corr_cases").values.toList.flatten
        val checked = entries.map { entry =>
          val k = entry.hcursor.get[Int]("K").getOrElse(0)
          val eta = entry.hcursor.get[Double]("eta").getOrElse(Double.NaN)
          val half = entry.hcursor.get[Double]("lkj_corr_half").getOrElse(Double.NaN)
          val identity = entry.hcursor.get[Double]("lkj_corr_identity").getOrElse(Double.NaN)
          val halfMatrix =
            IArray.tabulate(k)(i => IArray.tabulate(k)(j => if i == j then 1.0 else 0.5))
          val identityMatrix =
            IArray.tabulate(k)(i => IArray.tabulate(k)(j => if i == j then 1.0 else 0.0))
          (
            closeEnough(LogDensity.lkjCorr(halfMatrix, eta), half),
            closeEnough(LogDensity.lkjCorr(identityMatrix, eta), identity)
          )
        }
        check(entries.length == 3, s"${entries.length} cases, not 3")
          .and(check(checked.forall(_._1), "a half-correlation case disagrees"))
          .and(check(checked.forall(_._2), "an identity case disagrees"))
  }

  pureTest("the LKJ density refuses what is not a correlation matrix") {
    val notUnitDiagonal = IArray(IArray(1.0, 0.5), IArray(0.5, 2.0))
    val notSymmetric = IArray(IArray(1.0, 0.5), IArray(0.2, 1.0))
    val notPositiveDefinite = IArray(IArray(1.0, 1.5), IArray(1.5, 1.0))
    check(LogDensity.lkjCorr(notUnitDiagonal, 1.5).isNaN, "a diagonal that is not one")
      .and(check(LogDensity.lkjCorr(notSymmetric, 1.5).isNaN, "an asymmetric matrix"))
      .and(check(LogDensity.lkjCorr(notPositiveDefinite, 1.5).isNaN, "an impossible correlation"))
      .and(check(LogDensity.lkjCorr(IArray(IArray(1.0)), 1.5).isNaN, "a one by one matrix"))
      .and {
        val fine = IArray(IArray(1.0, 0.5), IArray(0.5, 1.0))
        check(LogDensity.lkjCorr(fine, -1.0).isNaN, "a negative shape")
      }
  }

  private def closeEnough(obtained: Double, expected: Double): Boolean =
    math.abs(obtained - expected) <= 1e-12 * math.max(1.0, math.abs(expected))
