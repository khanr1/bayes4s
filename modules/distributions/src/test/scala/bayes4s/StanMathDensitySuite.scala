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

import bayes4s.StanMathFixtureSupport.*
import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import weaver.SimpleIOSuite

/** The density of every law we implement, at every argument Stan Math asserts.
  *
  * The values come from Stan's own `test/prob` suites and 124 of them were confirmed by SciPy with
  * no disagreement, so they are a reference and not a second opinion. The tolerance is 1e-12
  * relative, or the precision of the printed number where that is coarser.
  */
object StanMathDensitySuite extends SimpleIOSuite with Approx:

  /** The one case where the reference is demonstrably wrong, with the exact answer instead.
    *
    * For `neg_binomial_lpmf(10 | 1e6, 1e5)` the coefficient C(1000009, 10) is an integer, so the
    * whole log mass can be computed exactly: with that coefficient in exact integer arithmetic and
    * 40-digit decimal logarithms it is -2.07856664311089194671129470824235. Stan Math asserts
    * -2.078566643108163, which is 1.3e-12 relative away, and SciPy says -2.078566642705411, which
    * is 2.0e-10 away; the fixture recorded them as agreeing because their errors are of the same
    * sign and its threshold was looser than both. Both compute the three cancelling lgammas that
    * `Counts.logRisingCoefficient` exists to avoid.
    *
    * Stan is the reference for this library except where its numerics are demonstrably wrong
    * (plan 2.5), and this is such a place, so the suite asserts the exact value and
    * docs/validation.md records why.
    */
  private val corrected: Map[(String, String, List[Double]), (Double, Double)] = Map(
    ("neg_binomial", "lpdf", List(10.0, 1000000.0, 100000.0)) -> (-2.078566643110892, 1e-14)
  )

  pureTest("the 53 density cases of the 15 laws in scope") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val cases = for
          (law, functions) <- fixture.laws.toList.sortBy(_._1)
          if lawsInScope(law)
          (function, body) <- functions.toList.sortBy(_._1)
          if densityFamily(law, function)
          valid <- body.valid
        yield (law, function, valid)
        val assertions = forEach(cases) { (law, function, valid) =>
          val arguments = valid.params.toList.mkString(", ")
          val (expected, tolerance) =
            corrected.getOrElse((law, function, valid.params.toList), (valid.expected, 1e-12))
          density(law, function, valid.params) match
            case Left(message) => failure(s"$law.$function($arguments): $message")
            case Right(got) => closeRel(got, expected, tolerance, s"$law.$function($arguments)")
        }
        check(cases.length == 53, s"${cases.length} density cases, expected 53").and(assertions)
  }

  pureTest("the standard normal agrees with Normal(0, 1) on the same arguments") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        Normal.of(0.0, 1.0) match
          case Left(error) => failure(error.message)
          case Right(unit) =>
            forEach(fixture.valid("std_normal", "lpdf")) { valid =>
              check(
                Normal.stdNormal.logPdf(valid.params(0)) == unit.logPdf(valid.params(0)),
                s"std_normal and Normal(0, 1) disagree at ${valid.params(0)}"
              )
            }
  }
