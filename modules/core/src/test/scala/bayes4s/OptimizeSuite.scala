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

/** Brent's minimiser and root finder against functions whose answers are known in closed form.
  *
  * One honest limit is recorded here rather than hidden: near a smooth minimum the function
  * changes by less than a double's resolution once the argument is within about sqrt(eps), so the
  * **argument** of a minimum cannot be pinned to 1e-10 in double precision, while its **value**
  * can. Roots are well conditioned and are asserted to 1e-12 on the argument itself.
  */
object OptimizeSuite extends SimpleIOSuite with Approx:

  pureTest("the minimum of (x - 2)^2 on [0, 5]") {
    Optimize.brentMinimize(x => (x - 2.0) * (x - 2.0), 0.0, 5.0) match
      case Left(error) => failure(error.message)
      case Right(found) =>
        close(found.argument, 2.0, 1e-7, "the argument, to the sqrt(eps) a smooth minimum allows")
          .and(close(found.value, 0.0, 1e-12, "the value at the minimum"))
          .and(check(found.iterations > 0 && found.iterations <= 200, s"${found.iterations} steps"))
  }

  pureTest("the minimum of sin on [3, 6] is at 3 pi / 2") {
    Optimize.brentMinimize(math.sin, 3.0, 6.0) match
      case Left(error) => failure(error.message)
      case Right(found) =>
        close(found.argument, 3.0 * math.Pi / 2.0, 1e-7, "the argument")
          .and(close(found.value, -1.0, 1e-12, "the value"))
  }

  pureTest("the quartic double well x^4 - 2 x^2 is found on the bracket that contains one well") {
    val f = (x: Double) => x * x * x * x - 2.0 * x * x
    Optimize.brentMinimize(f, 0.2, 3.0) match
      case Left(error) => failure(error.message)
      case Right(found) =>
        close(found.argument, 1.0, 1e-7, "the right-hand well is at x = 1")
          .and(close(found.value, -1.0, 1e-12, "and its value is -1"))
  }

  pureTest("the root of x^2 - 2 is the square root of two, to 1e-12") {
    Optimize.brentRoot(x => x * x - 2.0, 0.0, 2.0) match
      case Left(error) => failure(error.message)
      case Right(root) => closeRel(root, math.sqrt(2.0), 1e-12, "sqrt(2)")
  }

  pureTest("the root of cos x - x, the Dottie number") {
    Optimize.brentRoot(x => math.cos(x) - x, 0.0, 1.0) match
      case Left(error) => failure(error.message)
      case Right(root) => closeRel(root, 0.7390851332151607, 1e-12, "the Dottie number")
  }

  pureTest("a root at a bracket endpoint is found exactly") {
    check(Optimize.brentRoot(x => x - 1.0, 1.0, 5.0) == Right(1.0), "the lower endpoint")
      .and(check(Optimize.brentRoot(x => x - 5.0, 1.0, 5.0) == Right(5.0), "the upper endpoint"))
  }

  pureTest("a bracket that does not change sign is a Left") {
    check(Optimize.brentRoot(x => x * x + 1.0, 0.0, 2.0).isLeft, "x^2 + 1 has no real root")
      .and(check(Optimize.brentRoot(x => x - 10.0, 0.0, 2.0).isLeft, "the root is outside"))
  }

  pureTest("a function that cannot be evaluated is a Left, never a silently wrong answer") {
    check(Optimize.brentRoot(_ => Double.NaN, 0.0, 1.0).isLeft, "a NaN root function")
      .and(check(Optimize.brentMinimize(_ => Double.NaN, 0.0, 1.0).isLeft, "a NaN objective"))
  }

  pureTest("an exhausted iteration budget is a Left") {
    check(
      Optimize.brentMinimize(math.sin, 3.0, 6.0, tolerance = 1e-16, maxIterations = 1).isLeft,
      "one iteration cannot reach a tolerance of 1e-16"
    ).and(
      check(
        Optimize.brentRoot(x => x * x - 2.0, 0.0, 2.0, tolerance = 1e-16, maxIterations = 1).isLeft,
        "one iteration cannot bracket to 1e-16"
      )
    )
  }

  pureTest("an inverted or degenerate bracket is a Left") {
    check(Optimize.brentMinimize(x => x * x, 5.0, 0.0).isLeft, "upper below lower")
      .and(check(Optimize.brentMinimize(x => x * x, 1.0, 1.0).isLeft, "an empty bracket"))
      .and(check(Optimize.brentRoot(x => x, Double.NaN, 1.0).isLeft, "a NaN bound"))
  }

  pureTest("both searches are referentially transparent") {
    val f = (x: Double) => (x - 2.0) * (x - 2.0)
    check(
      Optimize.brentMinimize(f, 0.0, 5.0) == Optimize.brentMinimize(f, 0.0, 5.0),
      "two minimisations disagree"
    ).and(
      check(
        Optimize.brentRoot(x => x * x - 2.0, 0.0, 2.0) == Optimize
          .brentRoot(x => x * x - 2.0, 0.0, 2.0),
        "two root searches disagree"
      )
    )
  }
