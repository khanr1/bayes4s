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

/** An analytic gradient beside a numeric one, and the largest disagreement between them. */
final case class GradientCheckResult(analytic: IArray[Double], numeric: IArray[Double]):
  /** Relative above magnitude one, absolute below it: the same rule the suites use. */
  def maxError: Double =
    (0 until analytic.length).foldLeft(0.0) { (worst, i) =>
      val scale = math.max(1.0, math.abs(numeric(i)))
      math.max(worst, math.abs(analytic(i) - numeric(i)) / scale)
    }

/** Central finite differences, for the secondary check.
  *
  * Secondary because it is blunt: a central difference costs about a third of the available digits
  * and says nothing useful where the function is stiff — at a Poisson rate of 1e-300 the step
  * cannot be small enough to mean anything. The oracle for derivatives is the dual-number
  * interpreter; this is what catches a sign error at an ordinary point, and what a user can run on
  * their own model without writing a second implementation of it.
  */
object GradientCheck:
  def apply(f: DiffFn, x: IArray[Double], step: Double = 1e-6): GradientCheckResult =
    val analytic = Reverse.valueAndGradient(f, x).gradient
    val numeric = IArray.tabulate(x.length) { i =>
      val h = step * math.max(1.0, math.abs(x(i)))
      val up = IArray.tabulate(x.length)(j => if j == i then x(j) + h else x(j))
      val down = IArray.tabulate(x.length)(j => if j == i then x(j) - h else x(j))
      (Reverse.value(f, up) - Reverse.value(f, down)) / (2.0 * h)
    }
    GradientCheckResult(analytic, numeric)
