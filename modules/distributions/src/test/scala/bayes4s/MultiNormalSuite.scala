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
import bayes4s.testkit.MonteCarlo
import cats.Monad
import weaver.SimpleIOSuite

/** The multivariate Gaussian on a Cholesky factor, against ports of
  * `multi_normal_cholesky_lpdf`, and its sampler against the covariance it claims.
  */
object MultiNormalSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private def factor(rows: IArray[IArray[Double]]): Either[String, Cholesky] =
    Matrix.ofRows(rows).flatMap(Cholesky.ofLower).left.map(_.message)

  pureTest("the 13 cases of multilevel.json") {
    Fixtures.multiNormalCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val got = for
            l <- factor(c.l)
            law <- MultiNormalCholesky.of(c.mu, l).left.map(_.message)
          yield law.logPdf(c.y)
          got match
            case Left(message) => failure(s"${c.label}: $message")
            case Right(value) => closeRel(value, c.value, 1e-12, c.label)
        }
        check(cases.length == 13, s"${cases.length} cases, expected 13").and(assertions)
  }

  pureTest("what the fixture says must be refused, is refused") {
    Fixtures.multiNormalInvalid match
      case Left(message) => failure(message)
      case Right(descriptions) =>
        // The fixture states these in prose, so the inputs are built here from the description.
        val notLower = IArray(IArray(1.0, 0.5), IArray(0.0, 1.0))
        val nonPositiveDiagonal = IArray(IArray(1.0, 0.0), IArray(0.5, 0.0))
        val good = IArray(IArray(1.0, 0.0), IArray(0.5, 1.0))
        val mismatch = factor(good).flatMap(l =>
          MultiNormalCholesky.of(IArray(0.0, 0.0, 0.0), l).left.map(_.message)
        )
        val withNaN = factor(good).flatMap(l =>
          MultiNormalCholesky
            .of(IArray(0.0, 0.0), l)
            .left
            .map(_.message)
            .map(_.logPdf(IArray(Double.NaN, 0.0)))
        )
        check(descriptions.length == 4, s"${descriptions.length} descriptions, expected 4")
          .and(check(factor(notLower).isLeft, "an upper entry was accepted"))
          .and(check(factor(nonPositiveDiagonal).isLeft, "a zero diagonal entry was accepted"))
          .and(check(mismatch.isLeft, "a mean of the wrong length was accepted"))
          .and(check(withNaN.exists(_.isNaN), s"a NaN observation gave $withNaN, expected NaN"))
  }

  pureTest("the sampler reproduces the mean and the covariance it was given") {
    val n = 40_000
    val rows = IArray(IArray(2.0, 0.0), IArray(-0.6, 1.5))
    val mu = IArray(1.0, -3.0)
    val built = for
      l <- factor(rows)
      law <- MultiNormalCholesky.of(mu, l).left.map(_.message)
    yield law
    built match
      case Left(message) => failure(message)
      case Right(law) =>
        val draws = Monad[Rand].replicateA(n, law.sample[Rand]).runA(Seed(20261105L)).value
        val first = draws.map(_(0))
        val second = draws.map(_(1))
        def average(xs: List[Double]) = xs.sum / xs.length
        val m0 = average(first)
        val m1 = average(second)
        // The covariance the factor implies: L L^T.
        val s00 = 4.0
        val s11 = 0.36 + 2.25
        val s01 = -1.2
        val c00 = average(first.map(x => (x - m0) * (x - m0)))
        val c11 = average(second.map(x => (x - m1) * (x - m1)))
        val c01 = first.zip(second).map((x, y) => (x - m0) * (y - m1)).sum / n
        withinStandardErrors(m0, mu(0), math.sqrt(s00 / n), 4.0, "the first mean")
          .and(withinStandardErrors(m1, mu(1), math.sqrt(s11 / n), 4.0, "the second mean"))
          .and(
            withinStandardErrors(
              c00,
              s00,
              s00 * math.sqrt(2.0 / n),
              4.0,
              "the first variance against (L L^T)(0, 0)"
            )
          )
          .and(
            withinStandardErrors(
              c11,
              s11,
              s11 * math.sqrt(2.0 / n),
              4.0,
              "the second variance against (L L^T)(1, 1)"
            )
          )
          .and(
            withinStandardErrors(
              c01,
              s01,
              math.sqrt((s00 * s11 + s01 * s01) / n),
              4.0,
              "the covariance against (L L^T)(0, 1)"
            )
          )
  }
