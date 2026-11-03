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

package bayes4s.testkit

import weaver.Expectations
import weaver.SourceLocation

/** Numeric comparisons with a tolerance, and a failure message that says what was compared, at
  * which input, and against which tolerance.
  *
  * An IEEE value counts as equal to itself: an expected `NaN` against an obtained `NaN` passes,
  * and so does `+Inf` against `+Inf`. The reference files assert `NaN` and `±Inf` for
  * out-of-domain arguments, so this is not optional.
  */
trait Approx:

  /** The absolute difference is at most `tolerance`. */
  def close(obtained: Double, expected: Double, tolerance: Double, clue: => String)(using
      SourceLocation
  ): Expectations =
    val delta = math.abs(obtained - expected)
    if sameIeee(obtained, expected) || delta <= tolerance then Expectations.Helpers.success
    else
      Expectations.Helpers.failure(
        s"$clue: obtained $obtained, expected $expected, |difference| = $delta > tolerance $tolerance"
      )

  /** Relative above magnitude 1, absolute below it. */
  def closeRel(obtained: Double, expected: Double, relative: Double, clue: => String)(using
      SourceLocation
  ): Expectations =
    val scale = math.max(1.0, math.abs(expected))
    val tolerance = relative * scale
    val delta = math.abs(obtained - expected)
    if sameIeee(obtained, expected) || delta <= tolerance then Expectations.Helpers.success
    else
      Expectations.Helpers.failure(
        s"$clue: obtained $obtained, expected $expected, |difference| = $delta > " +
          s"$relative relative (scale $scale, tolerance $tolerance)"
      )

  def check(condition: Boolean, clue: => String)(using SourceLocation): Expectations =
    if condition then Expectations.Helpers.success else Expectations.Helpers.failure(clue)

  /** IEEE equality, `NaN` included, for the values the references assert. */
  def sameIeee(a: Double, b: Double): Boolean =
    (a.isNaN && b.isNaN) || a == b

object Approx extends Approx
