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

/** Many-dimensional optimisation, and the curvature at the answer.
  *
  * Two functions whose minima are known exactly, chosen because they fail different things.
  * Rosenbrock's valley is curved, so a method that trusts a single direction crawls along it;
  * the ill-conditioned quadratic is straight but stretched, so a method with no curvature model
  * zig-zags across it. A quasi-Newton method should walk both, and the test says how far it is
  * allowed to be wrong.
  *
  * The Hessian is checked against **dual numbers applied to an analytic gradient**, which share
  * no code with the differencing under test: the gradient of each function is written out, `Dual`
  * differentiates it exactly, and that is the matrix the finite differences have to reproduce.
  */
object OptimizeMultiSuite extends SimpleIOSuite with Approx:

  /** `f(x, y) = (1 - x)^2 + 100 (y - x^2)^2`, with its minimum of 0 at (1, 1). */
  private def rosenbrock(x: IArray[Double]): Double =
    val a = 1.0 - x(0)
    val b = x(1) - x(0) * x(0)
    a * a + 100.0 * b * b

  private def rosenbrockGradient(x: IArray[Double]): IArray[Double] =
    val b = x(1) - x(0) * x(0)
    IArray(-2.0 * (1.0 - x(0)) - 400.0 * x(0) * b, 200.0 * b)

  /** `f(x) = 0.5 sum c_i x_i^2`, whose condition number is the ratio of the extreme `c`. */
  private def quadratic(scales: IArray[Double])(x: IArray[Double]): Double =
    (0 until x.length).foldLeft(0.0)((sum, i) => sum + 0.5 * scales(i) * x(i) * x(i))

  private def quadraticGradient(scales: IArray[Double])(x: IArray[Double]): IArray[Double] =
    IArray.tabulate(x.length)(i => scales(i) * x(i))

  pureTest("Rosenbrock's minimum is found from a long way off") {
    Optimize.lbfgs(rosenbrock, rosenbrockGradient, IArray(-1.2, 1.0)) match
      case Left(error) => failure(error.message)
      case Right(found) =>
        // The valley is flat near the answer, so the *argument* is the thing to hold to a
        // tolerance and the value is exact to many more digits.
        close(found.argument(0), 1.0, 1e-6, s"x is ${found.argument(0)}")
          .and(close(found.argument(1), 1.0, 1e-6, s"y is ${found.argument(1)}"))
          .and(close(found.value, 0.0, 1e-12, s"the minimum is ${found.value}"))
  }

  pureTest("an ill-conditioned quadratic is solved in far fewer steps than steepest descent") {
    // A condition number of ten thousand: steepest descent needs thousands of steps, a
    // quasi-Newton method a handful, because it learns the scaling it is walking through.
    val scales = IArray(1e-2, 1.0, 1e2)
    val start = IArray(3.0, -2.0, 1.5)
    Optimize.lbfgs(quadratic(scales), quadraticGradient(scales), start) match
      case Left(error) => failure(error.message)
      case Right(found) =>
        check(
          (0 until 3).forall(i => math.abs(found.argument(i)) < 1e-6),
          s"the minimum is at ${found.argument.toList}"
        ).and(check(found.iterations < 60, s"it took ${found.iterations} iterations"))
          .and(close(found.value, 0.0, 1e-12, s"the value is ${found.value}"))
  }

  pureTest("a function that cannot be evaluated is an error rather than a wrong answer") {
    val broken = (_: IArray[Double]) => Double.NaN
    val gradient = (x: IArray[Double]) => IArray.fill(x.length)(Double.NaN)
    check(Optimize.lbfgs(broken, gradient, IArray(0.0)).isLeft, "a NaN objective was optimised")
      .and(check(Optimize.lbfgs(rosenbrock, rosenbrockGradient, IArray.empty).isLeft, "no start"))
      .and {
        val tooFew = Optimize.lbfgs(
          rosenbrock,
          rosenbrockGradient,
          IArray(-1.2, 1.0),
          maxIterations = 2
        )
        check(tooFew.isLeft, "two iterations were declared enough for Rosenbrock")
      }
  }

  pureTest("the Hessian of the quadratic is its diagonal of scales, exactly") {
    val scales = IArray(0.25, 4.0, 9.0)
    Optimize.hessian(quadraticGradient(scales), IArray(1.0, -2.0, 0.5)) match
      case Left(error) => failure(error.message)
      case Right(h) =>
        // A quadratic's Hessian is constant, so differencing its gradient is exact up to
        // rounding: this one is held far tighter than the Rosenbrock case below.
        val wrong = for
          i <- 0 until 3
          j <- 0 until 3
          expected = if i == j then scales(i) else 0.0
          if math.abs(h(i, j) - expected) > 1e-8
        yield (i, j, h(i, j), expected)
        check(wrong.isEmpty, s"wrong entries: ${wrong.toList}")
  }

  pureTest("the Hessian is symmetric, which a one-sided difference would not be") {
    Optimize.hessian(rosenbrockGradient, IArray(0.3, -0.7)) match
      case Left(error) => failure(error.message)
      case Right(h) =>
        check(math.abs(h(0, 1) - h(1, 0)) < 1e-12, s"off-diagonals ${h(0, 1)} and ${h(1, 0)}")
  }
