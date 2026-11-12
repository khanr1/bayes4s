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

import bayes4s.syntax.tagFromReal
import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** `Optimize.hessian` against exact second derivatives.
  *
  * The oracle is dual numbers applied to an **analytic gradient**: each test function's gradient
  * is written out as a `Real[A]` program, `Dual` carries an exact derivative through every
  * operation of it, and the matrix that comes out is the Hessian with no truncation error at all.
  * It shares no code with the central differencing under test — a differenced gradient compared
  * against another differenced gradient would agree about the wrong answer.
  *
  * The tolerance is the one a central difference earns: the truncation error is O(h²) and the
  * rounding error O(eps/h), so at `h` around 1e-5 about seven digits survive. A test that
  * demanded twelve would be a test of nothing but the step size.
  */
object HessianOracleSuite extends SimpleIOSuite with Approx:

  /** The gradient of Rosenbrock's function, written once for any scalar. */
  private def rosenbrockGradient[A: Real](x: IArray[A]): IArray[A] =
    val r = Real[A]
    val b = r.minus(x(1), r.times(x(0), x(0)))
    IArray(
      r.minus(
        r.times(r.const(-2.0), r.minus(r.const(1.0), x(0))),
        r.times(r.const(400.0), r.times(x(0), b))
      ),
      r.times(r.const(200.0), b)
    )

  /** The gradient of `6.5 log x0 - 1.6 x0 - 0.5 x1² (1 + x0²)`: a Gamma kernel coupled to a
    * Gaussian whose scale depends on the first coordinate.
    *
    * It has to be the gradient of an actual function, or its Hessian is not symmetric and the
    * oracle and the symmetrised differencing disagree for a reason that is the test's fault. The
    * first draft of this was not one, and the oracle said so.
    */
  private def mixedGradient[A: Real](x: IArray[A]): IArray[A] =
    val r = Real[A]
    IArray(
      r.minus(
        r.minus(r.div(r.const(6.5), x(0)), r.const(1.6)),
        r.times(r.times(x(1), x(1)), x(0))
      ),
      r.negate(r.times(x(1), r.plus(r.const(1.0), r.times(x(0), x(0)))))
    )

  /** Column `j` of the exact Hessian: the gradient differentiated by `x(j)` through `Dual`. */
  private def exactColumn(
      gradient: [A] => (IArray[A], Real[A]) => IArray[A],
      at: IArray[Double],
      j: Int
  ): IArray[Double] =
    val duals = IArray.tabulate(at.length)(i => Dual(at(i), if i == j then 1.0 else 0.0))
    gradient[Dual](duals, Dual.real).map(_.derivative)

  pureTest("Rosenbrock's Hessian agrees with the dual-number oracle") {
    val at = IArray(0.3, -0.7)
    val exact = IArray.tabulate(2)(j =>
      exactColumn([A] => (x: IArray[A], r: Real[A]) => rosenbrockGradient(x)(using r), at, j)
    )
    Optimize.hessian(x => rosenbrockGradient[Double](x), at) match
      case Left(error) => failure(error.message)
      case Right(h) =>
        val wrong = for
          i <- 0 until 2
          j <- 0 until 2
          // `exact(j)(i)` is d(grad_i)/d(x_j), which is H(i, j).
          if math.abs(h(i, j) - exact(j)(i)) > 1e-6 * math.max(1.0, math.abs(exact(j)(i)))
        yield (i, j, h(i, j), exact(j)(i))
        check(wrong.isEmpty, s"entries disagreeing with the oracle: ${wrong.toList}")
  }

  pureTest("a curved, coupled Hessian agrees too, off the diagonal included") {
    val at = IArray(2.5, 0.8)
    val exact = IArray.tabulate(2)(j =>
      exactColumn([A] => (x: IArray[A], r: Real[A]) => mixedGradient(x)(using r), at, j)
    )
    Optimize.hessian(x => mixedGradient[Double](x), at) match
      case Left(error) => failure(error.message)
      case Right(h) =>
        // The off-diagonal is the one a careless differencing gets wrong, and it is not zero here.
        check(math.abs(exact(0)(1)) > 1.0, "the test function has no coupling to check")
          .and(
            closeRel(h(0, 0), exact(0)(0), 1e-6, s"H(0,0) is ${h(0, 0)} against ${exact(0)(0)}")
          )
          .and(
            closeRel(h(1, 1), exact(1)(1), 1e-6, s"H(1,1) is ${h(1, 1)} against ${exact(1)(1)}")
          )
          .and(
            closeRel(h(0, 1), exact(1)(0), 1e-6, s"H(0,1) is ${h(0, 1)} against ${exact(1)(0)}")
          )
  }
