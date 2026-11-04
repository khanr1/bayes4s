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

import scala.math.abs
import scala.math.log

import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import weaver.SimpleIOSuite

/** lgamma, digamma, lbeta and the normal quantile against mpmath at 50 significant digits, plus
  * the two recurrences they must satisfy exactly.
  *
  * Tolerance: 1e-13 relative above magnitude 1, absolute below it. Near lgamma's zeros at x = 1
  * and x = 2 that rule is absolute by construction, which is what the fixture asks for.
  */
object SpecialFunctionsSuite extends SimpleIOSuite with Approx:

  pureTest("lgamma on 72 points from 0.001 to 1e5, negatives included (mpmath, 50 digits)") {
    Fixtures.specialPoints("lgamma") match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 72, s"${cases.length} points, expected 72").and(
          forEach(cases)(c => closeRel(Special.lgamma(c.x), c.value, 1e-13, s"lgamma(${c.x})"))
        )
  }

  pureTest("digamma on 72 points (mpmath, 50 digits)") {
    Fixtures.specialPoints("digamma") match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 72, s"${cases.length} points, expected 72").and(
          forEach(cases)(c => closeRel(Special.digamma(c.x), c.value, 1e-13, s"digamma(${c.x})"))
        )
  }

  pureTest("lbeta on 6 pairs (mpmath, 50 digits)") {
    Fixtures.specialPairs("lbeta") match
      case Left(message) => failure(message)
      case Right(cases) =>
        forEach(cases) { c =>
          closeRel(Special.lbeta(c.a, c.b), c.value, 1e-13, s"lbeta(${c.a}, ${c.b})")
        }
  }

  pureTest("the normal quantile down to p = 1e-300 (mpmath, 50 digits)") {
    Fixtures.specialProbs("normal_quantile") match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 16, s"${cases.length} points, expected 16").and(
          forEach(cases) { c =>
            closeRel(Special.normalQuantile(c.p), c.value, 1e-13, s"normalQuantile(${c.p})")
          }
        )
  }

  // Antisymmetry can only be asserted where `1 - p` recovers `p`. At p = 1e-12 the subtraction
  // keeps eight digits, and comparing the two tails then measures the representation of 1 - p
  // rather than anything about the quantile: the grid stops where the arithmetic does.
  pureTest("the normal quantile is antisymmetric and increasing") {
    val ps = List(0.001, 0.01, 0.1, 0.25, 0.4, 0.5, 0.6, 0.75, 0.9, 0.99, 0.999)
    val antisymmetric = forEach(ps.filter(_ <= 0.5)) { p =>
      closeRel(
        Special.normalQuantile(p),
        -Special.normalQuantile(1.0 - p),
        1e-12,
        s"normalQuantile($p) = -normalQuantile(${1.0 - p})"
      )
    }
    val increasing = forEach(ps.zip(ps.tail)) { (a, b) =>
      check(
        Special.normalQuantile(a) < Special.normalQuantile(b),
        s"normalQuantile($a) < normalQuantile($b)"
      )
    }
    val edges = check(Special.normalQuantile(0.5) == 0.0, "normalQuantile(0.5) is exactly 0")
      .and(check(Special.normalQuantile(0.0) == Double.NegativeInfinity, "p = 0 gives -Inf"))
      .and(check(Special.normalQuantile(1.0) == Double.PositiveInfinity, "p = 1 gives +Inf"))
      .and(check(Special.normalQuantile(1.5).isNaN, "p outside [0, 1] gives NaN"))
      .and(check(Special.normalQuantile(Double.NaN).isNaN, "p = NaN gives NaN"))
    antisymmetric.and(increasing).and(edges)
  }

  // The error budget is stated rather than assumed: each lgamma carries about 1e-13 of its own
  // magnitude, and the difference cancels that magnitude down to log(x), so the bound has to be
  // absolute and scaled by what was cancelled. A relative bound on the difference would be
  // unachievable at x = 1e5 and meaningless.
  pureTest("lgamma(x+1) - lgamma(x) = log x, within the cancellation it costs") {
    val xs = List(0.001, 0.1, 0.5, 1.0, 1.5, 2.0, 3.7, 10.0, 100.0, 1000.0, 1e5)
    forEach(xs) { x =>
      val difference = Special.lgamma(x + 1.0) - Special.lgamma(x)
      val cancelled = math.max(1.0, abs(Special.lgamma(x)))
      close(difference, log(x), 4e-13 * cancelled, s"lgamma(${x + 1}) - lgamma($x) = log($x)")
    }
  }

  pureTest("digamma(x+1) - digamma(x) = 1/x, within the cancellation it costs") {
    val xs = List(0.001, 0.1, 0.5, 1.0, 1.5, 2.0, 3.7, 10.0, 100.0, 1000.0, 1e5)
    forEach(xs) { x =>
      val difference = Special.digamma(x + 1.0) - Special.digamma(x)
      val cancelled = math.max(1.0, abs(Special.digamma(x)))
      close(difference, 1.0 / x, 4e-13 * cancelled, s"digamma(${x + 1}) - digamma($x) = 1/$x")
    }
  }

  pureTest("the poles return an infinity and never throw") {
    val lgammaPoles = Fixtures.poles("lgamma_poles") match
      case Left(message) => failure(message)
      case Right(xs) =>
        check(xs.length == 4, s"${xs.length} poles, expected 4").and(
          forEach(xs.toList) { x =>
            check(
              Special.lgamma(x) == Double.PositiveInfinity,
              s"lgamma($x) = ${Special.lgamma(x)}, expected +Inf"
            )
          }
        )
    val digammaPoles = Fixtures.poles("digamma_poles") match
      case Left(message) => failure(message)
      case Right(xs) =>
        forEach(xs.toList) { x =>
          val got = Special.digamma(x)
          check(got.isNaN || got.isInfinite, s"digamma($x) = $got, expected NaN or an infinity")
        }
    lgammaPoles.and(digammaPoles)
  }
