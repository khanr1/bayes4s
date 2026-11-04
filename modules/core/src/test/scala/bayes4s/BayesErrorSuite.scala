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
import cats.kernel.laws.discipline.EqTests
import cats.syntax.all.*
import org.scalacheck.Arbitrary
import org.scalacheck.Cogen
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.discipline.Discipline

/** Errors are values here, so they are held to the same standard as any other published value:
  * their `Eq` obeys its laws, their message names what went wrong, and they cost nothing to
  * create because they carry no stack trace.
  */
object BayesErrorSuite extends SimpleIOSuite with Discipline with Approx:

  given Arbitrary[BayesError] = Arbitrary(
    Gen.oneOf(
      Gen.const(BayesError.InvalidArgument("Normal.of", "sigma", 0.0, "sigma > 0")),
      Gen.const(BayesError.InvalidArgument("Gamma.of", "alpha", -1.0, "alpha > 0")),
      Gen.const(BayesError.DimensionMismatch("Fft.forward", 8, 5)),
      Gen.const(BayesError.UnknownParameter("mu")),
      Gen.const(BayesError.NumericalOverflow("Special.lgamma")),
      Gen.const(BayesError.Malformed("stan.csv", "no header"))
    )
  )
  given Cogen[BayesError] = Cogen[String].contramap(_.message)

  checkAll("BayesError.EqLaws", EqTests[BayesError].eqv)

  pureTest("an invalid argument names the place, the parameter, the value and the rule") {
    val error: BayesError = BayesError.InvalidArgument("Normal.of", "sigma", -1.0, "sigma > 0")
    check(error.message.contains("Normal.of"), s"the place is missing from '${error.message}'")
      .and(check(error.message.contains("sigma"), "the parameter name is missing"))
      .and(check(error.message.contains("-1"), "the offending value is missing"))
      .and(check(error.message.contains("sigma > 0"), "the rule is missing"))
      .and(check(error.getMessage == error.message, "getMessage and message disagree"))
      .and(check(error.show == error.message, "Show and message disagree"))
  }

  pureTest("an error carries no stack trace") {
    val error: BayesError = BayesError.NumericalOverflow("somewhere")
    check(error.getStackTrace.isEmpty, s"${error.getStackTrace.length} frames were captured")
  }

  pureTest("the dimension mismatch says what it expected and what it got") {
    val error = BayesError.DimensionMismatch("Fft.forward", 8, 5)
    check(error.message.contains("8") && error.message.contains("5"), error.message)
  }
