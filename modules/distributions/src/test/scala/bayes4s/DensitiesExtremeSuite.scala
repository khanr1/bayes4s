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
import weaver.SimpleIOSuite

/** Densities where the naive formula falls apart: a scale of 1e-8, a rate of 1e-300, a log-rate of
  * -700, a dispersion of 1e8. mpmath at 50 significant digits says what the answer is.
  *
  * These are the cases a model actually meets during warm-up, when a parameter wanders into the
  * far tail of its prior, and they are the reason the densities are written in terms of `log1p`,
  * `lgamma` and `logSumExp` rather than in their textbook form.
  */
object DensitiesExtremeSuite extends SimpleIOSuite with Approx:

  private def value(name: String, args: IArray[Double]): Either[String, Double] =
    def of[L](built: Either[BayesError.InvalidArgument, L])(
        f: L => Double
    ): Either[String, Double] =
      built.left.map(_.message).map(f)
    name match
      case "normal_lpdf" => of(Normal.of(args(1), args(2)))(_.logPdf(args(0)))
      case "half_normal_lpdf" => of(HalfNormal.of(args(1)))(_.logPdf(args(0)))
      case "lognormal_lpdf" => of(LogNormal.of(args(1), args(2)))(_.logPdf(args(0)))
      case "gamma_lpdf" => of(Gamma.of(args(1), args(2)))(_.logPdf(args(0)))
      case "beta_lpdf" => of(Beta.of(args(1), args(2)))(_.logPdf(args(0)))
      case "poisson_lpmf" => of(Poisson.of(args(1)))(_.logMass(args(0).toInt))
      case "poisson_log_lpmf" => of(PoissonLog.of(args(1)))(_.logMass(args(0).toInt))
      case "neg_binomial_2_lpmf" => of(NegBinomial2.of(args(1), args(2)))(_.logMass(args(0).toInt))
      case other => Left(s"no mapping for $other")

  pureTest("the 29 extreme cases, against mpmath at 50 digits") {
    Fixtures.extremeCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val arguments = c.args.toList.mkString(", ")
          value(c.distribution, c.args) match
            case Left(message) => failure(s"${c.distribution}($arguments): $message")
            case Right(got) => closeRel(got, c.value, 1e-12, s"${c.distribution}($arguments)")
        }
        check(cases.length == 29, s"${cases.length} cases, expected 29").and(assertions)
  }

  pureTest("the eight laws of the extreme fixture are all mapped") {
    Fixtures.extremeCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val laws = cases.map(_.distribution).distinct.sorted
        check(laws.length == 8, s"${laws.length} laws, expected 8").and(
          forEach(laws)(law => check(cases.exists(c => c.distribution == law), law))
        )
  }

  pureTest("a Poisson rate of zero follows the convention the fixture states") {
    Poisson.of(0.0) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        check(law.logMass(0) == 0.0, s"poisson(0 | 0) = ${law.logMass(0)}, expected exactly 0")
          .and(
            check(
              law.logMass(5) == Double.NegativeInfinity,
              s"poisson(5 | 0) = ${law.logMass(5)}, expected -Inf"
            )
          )
  }
