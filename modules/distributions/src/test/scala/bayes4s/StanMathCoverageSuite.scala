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

/** What of the Stan Math fixture this phase covers, and what it does not, as numbers.
  *
  * A reference file is only worth what the suite actually reads from it. This suite partitions all
  * 196 valid and 361 invalid cases and asserts the size of each part, so that the coverage written
  * in docs/validation.md cannot quietly stop being true: adding a law, or a cumulative function,
  * changes a number here and the suite says which record to update.
  */
object StanMathCoverageSuite extends SimpleIOSuite with Approx:

  pureTest("the fixture is partitioned exactly as docs/validation.md records") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val entries = for
          (law, functions) <- fixture.laws.toList
          (function, body) <- functions.toList
        yield (law, function, body)

        val validCovered = entries.collect {
          case (law, function, body) if lawsInScope(law) && densityFamily(law, function) =>
            body.valid.length
        }.sum
        val validDeferred = entries.collect {
          case (law, function, body) if !(lawsInScope(law) && densityFamily(law, function)) =>
            body.valid.length
        }.sum

        val parameterCases = for
          (law, function, body) <- entries
          invalid <- body.invalid
          if invalid.index > 0
        yield (law, body.paramNames(invalid.index), invalid.value, lawsInScope(law))
        val coveredParameters = parameterCases.collect {
          case (law, name, value, true) if parameterName(law, name).isDefined =>
            (law, parameterName(law, name).get, value)
        }.distinct
        val deferredParameters = parameterCases.collect {
          case (law, name, value, inScope) if !inScope || parameterName(law, name).isEmpty =>
            (law, name, value)
        }.distinct

        val variateCases = for
          (law, function, body) <- entries
          invalid <- body.invalid
          if invalid.index == 0
        yield (law, function, lawsInScope(law) && densityFamily(law, function))
        val coveredVariates = variateCases.count(_._3)
        val deferredVariates = variateCases.count(!_._3)

        val outOfScope = fixture.laws.keySet.diff(lawsInScope)

        check(validCovered == 30, s"$validCovered valid density cases covered, expected 30")
          .and(check(validDeferred == 166, s"$validDeferred valid cases deferred, expected 166"))
          .and(
            check(
              validCovered + validDeferred == 196,
              s"${validCovered + validDeferred} valid cases in the fixture, expected 196"
            )
          )
          .and(
            check(
              coveredParameters.length == 46,
              s"${coveredParameters.length} distinct parameter rejections covered, expected 46"
            )
          )
          .and(
            check(
              deferredParameters.length == 48,
              s"${deferredParameters.length} distinct parameter rejections deferred, expected 48"
            )
          )
          .and(check(coveredVariates == 7, s"$coveredVariates variate cases covered, expected 7"))
          .and(
            check(deferredVariates == 34, s"$deferredVariates variate cases deferred, expected 34")
          )
          .and(
            check(
              outOfScope.size == 8,
              s"${outOfScope.size} laws out of scope (${outOfScope.toList.sorted.mkString(", ")}), expected 8"
            )
          )
  }

  pureTest("no parameter name of a covered law went untranslated") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val names = for
          (law, functions) <- fixture.laws.toList
          if lawsInScope(law)
          (_, body) <- functions.toList
          (name, index) <- body.paramNames.zipWithIndex
          if index > 0
        yield (law, name)
        forEach(names.distinct) { (law, name) =>
          parameterName(law, name) match
            case None =>
              check(law == "neg_binomial_2" && name == "eta", s"$law.$name maps to nothing")
            case Some(ours) =>
              check(
                parametersOf.getOrElse(law, Set.empty).contains(ours),
                s"$law.$name maps to '$ours', which is not a parameter of our constructor"
              )
        }
  }

  pureTest("every covered law has a density mapping and a constructor mapping") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        forEach(lawsInScope.toList.sorted) { law =>
          val params = base(fixture, law, "lpdf")
          val hasDensity = params.exists(p => density(law, "lpdf", p).isRight)
          val hasConstructor = law == "std_normal" || params.exists(p => construct(law, p).isRight)
          check(hasDensity, s"$law has no density mapping")
            .and(check(hasConstructor, s"$law has no constructor mapping"))
        }
  }
