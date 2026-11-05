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

/** The out-of-domain contract, case by case, against the 361 invalid arguments of Stan Math.
  *
  * The contract splits by what is invalid, and that split is the whole point of the suite:
  *
  *   - an invalid **parameter** is refused by the smart constructor, as a `Left(InvalidArgument)`
  *     that names the parameter — so a model can report which one and why;
  *   - a variate **outside the support** is not an error at all: the density is `-Inf`, which is
  *     what a sampler needs in order to reject a proposal rather than crash on it.
  *
  * Stan throws in both cases. We deliberately differ in the second, and these are the cases that
  * pin that decision down.
  */
object StanMathRejectionSuite extends SimpleIOSuite with Approx:

  pureTest("78 invalid parameters are refused by name") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val wanted = for
          (law, functions) <- fixture.laws.toList
          if lawsInScope(law)
          (function, body) <- functions.toList
          invalid <- body.invalid
          if invalid.index > 0
          ours <- parameterName(law, function, body.paramNames(invalid.index)).toList
          params <- base(fixture, law, function).toList
        yield (
          law,
          function,
          ours,
          invalid.value,
          substituted(params, invalid.index, invalid.value)
        )
        val distinct = wanted.distinctBy((law, _, name, value, _) => (law, name, value))
        val assertions = forEach(distinct) { (law, function, name, value, params) =>
          construct(law, function, params) match
            case Left(message) => failure(message)
            case Right(Right(_)) =>
              failure(s"$law accepted $name = $value, which Stan Math rejects")
            case Right(Left(error)) =>
              check(
                error.name == name,
                s"$law with $name = $value was refused, but the error names '${error.name}'"
              ).and(
                check(
                  error.where.startsWith(law.take(3).capitalize) || error.where.nonEmpty,
                  "the error names no place"
                )
              )
        }
        check(distinct.length == 78, s"${distinct.length} distinct rejections, expected 78")
          .and(assertions)
  }

  // Thirteen of the fourteen carry a finite value outside the support, and one — `exponential` —
  // carries a NaN. The two are not the same thing and the contract does not treat them alike: a
  // finite value the law cannot produce has density zero, while a NaN is not a value at all and
  // propagates as IEEE arithmetic requires. Asserting -Inf for both would have been asserting
  // something false about one of them.
  pureTest("14 variates outside the support follow the contract: -Inf, or NaN for a NaN") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val wanted = for
          (law, functions) <- fixture.laws.toList
          if lawsInScope(law)
          (function, body) <- functions.toList
          if densityFamily(law, function)
          invalid <- body.invalid
          if invalid.index == 0
          params <- base(fixture, law, function).toList
        yield (law, function, invalid.value, substituted(params, 0, invalid.value))
        val assertions = forEach(wanted) { (law, function, value, params) =>
          density(law, function, params) match
            case Left(message) => failure(s"$law.$function: $message")
            case Right(got) =>
              if value.isNaN then
                check(got.isNaN, s"$law.$function at a NaN variate gave $got, expected NaN")
              else
                check(
                  got == Double.NegativeInfinity,
                  s"$law.$function at a variate of $value gave $got, expected -Inf"
                )
        }
        check(wanted.length == 14, s"${wanted.length} out-of-support cases, expected 14")
          .and(assertions)
  }

  pureTest("a NaN parameter is refused and a NaN variate gives NaN") {
    val refused = check(Normal.of(0.0, Double.NaN).isLeft, "sigma = NaN was accepted")
      .and(check(Normal.of(Double.NaN, 1.0).isLeft, "mu = NaN was accepted"))
      .and(check(Gamma.of(Double.NaN, 1.0).isLeft, "alpha = NaN was accepted"))
      .and(check(Poisson.of(Double.NaN).isLeft, "rate = NaN was accepted"))
    val variate = Normal.of(0.0, 1.0) match
      case Left(error) => failure(error.message)
      case Right(law) => check(law.logPdf(Double.NaN).isNaN, "a NaN variate did not give NaN")
    refused.and(variate)
  }

  pureTest("every parameter must be finite, uniformly across the laws") {
    forEach(List(Double.PositiveInfinity, Double.NegativeInfinity)) { bad =>
      check(Normal.of(bad, 1.0).isLeft, s"Normal accepted mu = $bad")
        .and(check(Normal.of(0.0, bad).isLeft, s"Normal accepted sigma = $bad"))
        .and(check(Gamma.of(bad, 1.0).isLeft, s"Gamma accepted alpha = $bad"))
        .and(check(Beta.of(1.0, bad).isLeft, s"Beta accepted beta = $bad"))
        .and(check(NegBinomial2.of(1.0, bad).isLeft, s"NegBinomial2 accepted phi = $bad"))
    }
  }

  pureTest("a rate of exactly zero is a Poisson, with all its mass at zero") {
    Poisson.of(0.0) match
      case Left(error) => failure(s"a rate of 0 was refused: ${error.message}")
      case Right(law) =>
        check(law.logMass(0) == 0.0, s"logMass(0) = ${law.logMass(0)}, expected exactly 0")
          .and(check(law.logMass(1) == Double.NegativeInfinity, "logMass(1) should be -Inf"))
          .and(check(law.logMass(-1) == Double.NegativeInfinity, "logMass(-1) should be -Inf"))
  }
