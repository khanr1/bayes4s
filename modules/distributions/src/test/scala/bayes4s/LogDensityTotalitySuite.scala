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

import bayes4s.syntax.{*, given}
import bayes4s.testkit.Approx
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** The densities written against `Real[A]` are total, and so is the tape that differentiates them.
  *
  * `TotalitySuite` in `core` makes the same statement about the special functions. This is the
  * version that matters to a sampler: a proposal lands wherever the dynamics take it, including on
  * a scale of `-1e300` or a `NaN` that arrived from somewhere else, and what must happen there is
  * a documented value — never an exception, and never a plausible number.
  *
  * The rule is restated here rather than read from the implementation: a parameter must be finite,
  * a scale strictly positive, and an invalid one gives `NaN`. Restating it is the point, since a
  * test that asked the code what its rule was would agree with any rule at all.
  */
object LogDensityTotalitySuite extends SimpleIOSuite with Checkers with Approx:

  private val awkward: Gen[Double] = Gen.oneOf(
    Double.NaN,
    Double.PositiveInfinity,
    Double.NegativeInfinity,
    0.0,
    -0.0,
    Double.MinPositiveValue,
    -Double.MinPositiveValue,
    Double.MaxValue,
    Double.MinValue,
    1.0,
    -1.0,
    1e-320,
    800.0,
    -800.0
  )

  private val anyDouble: Gen[Double] =
    Gen.frequency(3 -> awkward, 2 -> Gen.choose(-1e10, 1e10), 1 -> Gen.choose(-1e300, 1e300))

  private val anyCount: Gen[Int] =
    Gen.oneOf(Gen.oneOf(-1, 0, 1, 2, Int.MaxValue, Int.MinValue), Gen.choose(0, 40))

  private def isFinite(x: Double): Boolean = !x.isNaN && !x.isInfinite
  private def isScale(x: Double): Boolean = isFinite(x) && x > 0.0

  /** Returned something, rather than threw: `got == got` is false only for a `NaN`, so the
    * disjunction is true for every double and the assertion is that the call completed at all.
    */
  private def returned(got: Double, clue: String) =
    check(got == got || got.isNaN, clue)

  test("the location-scale densities are total, and NaN exactly where the rule says") {
    forall(Gen.zip(anyDouble, anyDouble, anyDouble)) { (x, mu, sigma) =>
      val valid = isFinite(mu) && isScale(sigma)
      val cases = List(
        ("normal", LogDensity.normal(x, mu, sigma)),
        ("logNormal", LogDensity.logNormal(x, mu, sigma)),
        ("logistic", LogDensity.logistic(x, mu, sigma)),
        ("cauchy", LogDensity.cauchy(x, mu, sigma))
      )
      forEach(cases) { (name, got) =>
        returned(got, s"$name($x | $mu, $sigma) threw")
          .and(check(valid || got.isNaN, s"$name($x | $mu, $sigma) = $got, expected NaN"))
      }
    }
  }

  test("the positive-support densities are total, and -Inf below zero") {
    forall(Gen.zip(anyDouble, anyDouble, anyDouble)) { (x, nu, sigma) =>
      val cases = List(
        ("halfNormal", LogDensity.halfNormal(x, sigma), isScale(sigma)),
        ("halfCauchy", LogDensity.halfCauchy(x, sigma), isScale(sigma)),
        ("exponential", LogDensity.exponential(x, sigma), isScale(sigma)),
        ("halfStudentT", LogDensity.halfStudentT(x, nu, sigma), isScale(nu) && isScale(sigma)),
        ("gamma", LogDensity.gamma(x, nu, sigma), isScale(nu) && isScale(sigma))
      )
      forEach(cases) { (name, got, valid) =>
        returned(got, s"$name($x | $nu, $sigma) threw")
          .and(check(valid || got.isNaN, s"$name($x | $nu, $sigma) = $got, expected NaN"))
          .and(
            check(
              !valid || !(x < 0.0) || got == Double.NegativeInfinity,
              s"$name at $x < 0 gave $got, expected -Inf"
            )
          )
      }
    }
  }

  test("the beta density is total and never leaves the unit interval unaccounted for") {
    forall(Gen.zip(anyDouble, anyDouble, anyDouble)) { (x, alpha, beta) =>
      val got = LogDensity.beta(x, alpha, beta)
      val valid = isScale(alpha) && isScale(beta)
      val outside = x < 0.0 || x > 1.0
      returned(got, s"beta($x | $alpha, $beta) threw")
        .and(check(valid || got.isNaN, s"beta($x | $alpha, $beta) = $got, expected NaN"))
        .and(
          check(
            !valid || !outside || got == Double.NegativeInfinity,
            s"beta at $x outside [0, 1] gave $got, expected -Inf"
          )
        )
    }
  }

  test("the masses are total, and -Inf outside their support") {
    forall(Gen.zip(anyCount, anyDouble, anyDouble)) { (k, a, b) =>
      val cases = List(
        ("poisson", LogMass.poisson(k, a), isFinite(a) && a >= 0.0),
        ("poissonLog", LogMass.poissonLog(k, a), isFinite(a)),
        ("bernoulliLogit", LogMass.bernoulliLogit(k, a), isFinite(a)),
        ("negativeBinomial2", LogMass.negativeBinomial2(k, a, b), isScale(a) && isScale(b)),
        ("negativeBinomial", LogMass.negativeBinomial(k, a, b), isScale(a) && isScale(b)),
        ("binomial", LogMass.binomial(k, 7, a), !a.isNaN && a >= 0.0 && a <= 1.0),
        ("binomialLogit", LogMass.binomialLogit(k, 7, a), isFinite(a)),
        ("betaBinomial", LogMass.betaBinomial(k, 7, a, b), isScale(a) && isScale(b))
      )
      forEach(cases) { (name, got, valid) =>
        returned(got, s"$name($k | $a, $b) threw")
          .and(check(valid || got.isNaN, s"$name($k | $a, $b) = $got, expected NaN"))
          .and(
            check(
              !valid || k >= 0 || got == Double.NegativeInfinity,
              s"$name at a count of $k gave $got, expected -Inf"
            )
          )
      }
    }
  }

  test("the ordered logistic is total, and its categories never hold more than all the mass") {
    forall(Gen.zip(anyDouble, anyDouble, anyDouble, anyCount)) { (eta, first, second, k) =>
      val cuts = IArray(first, second)
      val got = LogDensity.orderedLogistic(k, eta, cuts)
      val valid = isFinite(eta) && isFinite(first) && isFinite(second) && first <= second
      val total =
        LogExp.logSumExp(IArray.tabulate(3)(i => LogDensity.orderedLogistic(i + 1, eta, cuts)))
      returned(got, s"orderedLogistic($k | $eta, $first, $second) threw")
        .and(check(valid || got.isNaN, s"orderedLogistic($k | $eta, $first, $second) = $got"))
        .and(
          check(
            !valid || total.isNaN || total <= 1e-9,
            s"the three categories at $eta, ($first, $second) sum to exp($total)"
          )
        )
    }
  }

  /** The tape is handed the same arbitrary points, through a model that reads a scale and a
    * location, and must come back with a value and a gradient of the right length every time.
    */
  test("the tape is total, and agrees with the Double interpreter wherever both are numbers") {
    val model: DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        LogDensity.normal(IArray(0.3, -1.1), Real[A].filled(2)(x(0)), x(1)) +
          LogDensity.halfNormal(x(1), 1.0.lift[A]) +
          LogMass.poisson(3, exp(x(0)))
    forall(Gen.zip(anyDouble, anyDouble)) { (mu, sigma) =>
      val at = IArray(mu, sigma)
      val taped = Reverse.valueAndGradient(model, at)
      val plain = Reverse.value(model, at)
      returned(plain, s"the Double interpreter threw at ($mu, $sigma)")
        .and(returned(taped.value, s"the tape threw at ($mu, $sigma)"))
        .and(check(taped.gradient.length == 2, "the gradient has the wrong length"))
        .and(forEach(taped.gradient.toList)(g => returned(g, "a component of the gradient")))
        .and(closeRel(taped.value, plain, 1e-12, s"the two interpreters at ($mu, $sigma)"))
    }
  }
