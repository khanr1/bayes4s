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

import org.apache.commons.numbers.gamma.RegularizedBeta

import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import cats.Monad
import weaver.SimpleIOSuite

/** The LKJ prior on a Cholesky factor of correlation: its normalising constant, its density, and
  * the onion sampler that draws from it.
  *
  * The constant is asserted on its own over twenty combinations because it has a separate closed
  * form at eta = 1, a branch that a density test on a single eta would never reach. The sampler is
  * judged by a theorem rather than by its moments: under LKJ(eta) every marginal correlation
  * follows a Beta(a, a) on (-1, 1) with a = eta - 1 + K/2, which is what
  * `lkj_corr_test.cpp` checks and what a chi-squared can see.
  */
object LkjSuite extends SimpleIOSuite with Approx:

  private def factor(rows: IArray[IArray[Double]]): Either[String, Cholesky] =
    Matrix.ofRows(rows).flatMap(Cholesky.ofLower).left.map(_.message)

  pureTest("the 20 normalising constants of multilevel.json, including the branch at eta = 1") {
    Fixtures.lkjConstants match
      case Left(message) => failure(message)
      case Right(cases) =>
        val unit = cases.count(_.eta == 1.0)
        val assertions = forEach(cases) { c =>
          LkjCorrCholesky.of(c.k, c.eta) match
            case Left(error) => failure(s"K = ${c.k}, eta = ${c.eta}: ${error.message}")
            case Right(law) =>
              closeRel(
                law.logConstant,
                c.constant,
                1e-12,
                s"constant at K = ${c.k}, eta = ${c.eta}"
              )
        }
        check(cases.length == 20, s"${cases.length} constants, expected 20")
          .and(check(unit > 0, "no case has eta = 1, so the separate closed form is never reached"))
          .and(assertions)
  }

  pureTest("the 9 densities on a Cholesky factor") {
    Fixtures.lkjCholesky match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val got = for
            l <- factor(c.l)
            law <- LkjCorrCholesky.of(c.k, c.eta).left.map(_.message)
          yield law.logPdf(l)
          got match
            case Left(message) => failure(s"K = ${c.k}, eta = ${c.eta}: $message")
            case Right(value) =>
              closeRel(value, c.value, 1e-12, s"density at K = ${c.k}, eta = ${c.eta}")
        }
        check(cases.length == 9, s"${cases.length} cases, expected 9").and(assertions)
  }

  // Beta(a, a) on (-1, 1) for every marginal correlation, a = eta - 1 + K/2. The critical value is
  // the 0.1 % point of chi-squared with 9 degrees of freedom, 27.877, from any standard table.
  pureTest("the onion sampler gives the marginal correlations the theorem predicts") {
    val draws = 20_000
    forEach(List((2, 1.0), (4, 0.7), (4, 2.5))) { (dimension, eta) =>
      LkjCorrCholesky.of(dimension, eta) match
        case Left(error) => failure(error.message)
        case Right(law) =>
          val factors =
            Monad[Rand]
              .replicateA(draws, law.sample[Rand])
              .runA(Seed(20261105L).fold(dimension.toLong))
              .value
          // The correlation between the first two components is the dot product of rows 0 and 1.
          val correlations = factors.map { l =>
            (0 until dimension).map(k => l.matrix(0, k) * l.matrix(1, k)).sum
          }
          val a = eta - 1.0 + dimension / 2.0
          val bins = 10
          val observed = Array.ofDim[Double](bins)
          val counts = correlations.groupBy(r => math.min((0.5 * (r + 1.0) * bins).toInt, bins - 1))
          val expected = (0 until bins).map { b =>
            val lo = b.toDouble / bins
            val hi = (b + 1.0) / bins
            (RegularizedBeta.value(hi, a, a) - RegularizedBeta.value(lo, a, a)) * draws
          }
          val statistic = (0 until bins).map { b =>
            val o = counts.get(b).map(_.length.toDouble).getOrElse(0.0)
            val e = expected(b)
            if e <= 0.0 then 0.0 else (o - e) * (o - e) / e
          }.sum
          val inRange = correlations.forall(r => r > -1.0 && r < 1.0)
          check(inRange, s"K = $dimension, eta = $eta: a correlation left (-1, 1)")
            .and(
              check(
                statistic <= 27.877,
                s"K = $dimension, eta = $eta: chi-squared = $statistic on 9 degrees of freedom, " +
                  s"0.1 % point 27.877, against Beta($a, $a)"
              )
            )
            .and(check(observed.length == bins, "bins"))
    }
  }

  pureTest("a sampled factor is a factor of a correlation matrix") {
    LkjCorrCholesky.of(3, 2.0) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        val factors = Monad[Rand].replicateA(200, law.sample[Rand]).runA(Seed(7L)).value
        forEach(factors.take(20)) { l =>
          // Unit row norms: L L^T has a unit diagonal, which is what makes it a correlation.
          forEach((0 until 3).toList) { i =>
            val norm = (0 until 3).map(k => l.matrix(i, k) * l.matrix(i, k)).sum
            closeRel(norm, 1.0, 1e-12, s"the squared norm of row $i")
          }
        }
  }

  pureTest("the constructor refuses a dimension or a concentration it cannot use") {
    check(LkjCorrCholesky.of(1, 1.0).isLeft, "a dimension of 1")
      .and(check(LkjCorrCholesky.of(0, 1.0).isLeft, "a dimension of 0"))
      .and(check(LkjCorrCholesky.of(3, 0.0).isLeft, "a concentration of 0"))
      .and(check(LkjCorrCholesky.of(3, -1.0).isLeft, "a negative concentration"))
      .and(check(LkjCorrCholesky.of(3, Double.NaN).isLeft, "a NaN concentration"))
      .and(check(LkjCorrCholesky.of(2, 1.0).isRight, "the uniform case is valid"))
  }
