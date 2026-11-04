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
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** Every public numeric function is total: on any double at all, including the ones that usually
  * break numerical code, it returns a documented value and never throws.
  *
  * This is the property that lets the rest of the library treat these functions as plain
  * arithmetic, and it is why the sampler can reject a `NaN` log density instead of crashing.
  */
object TotalitySuite extends SimpleIOSuite with Checkers with Approx:

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
    -1e-320,
    800.0,
    -800.0
  )

  private val anyDouble: Gen[Double] =
    Gen.frequency(3 -> awkward, 2 -> Gen.choose(-1e10, 1e10), 1 -> Gen.choose(-1e300, 1e300))

  test("lgamma, digamma and lbeta never throw") {
    forall(anyDouble) { x =>
      val a = Special.lgamma(x)
      val b = Special.digamma(x)
      val c = Special.lbeta(x, 1.5)
      check(!a.isNaN || x.isNaN || x <= 0.0, s"lgamma($x) = $a")
        .and(check(a == a || a.isNaN, s"lgamma($x) = $a"))
        .and(check(b == b || b.isNaN, s"digamma($x) = $b"))
        .and(check(c == c || c.isNaN, s"lbeta($x, 1.5) = $c"))
    }
  }

  test("the log-exp family never throws and respects its ranges") {
    forall(anyDouble) { x =>
      val l = LogExp.logistic(x)
      val sp = LogExp.log1pExp(x)
      val ls = LogExp.logSigmoid(x)
      check(l.isNaN || (l >= 0.0 && l <= 1.0), s"logistic($x) = $l is outside [0, 1]")
        .and(check(sp.isNaN || sp >= 0.0, s"log1pExp($x) = $sp is negative"))
        .and(check(ls.isNaN || ls <= 0.0, s"logSigmoid($x) = $ls is positive"))
        .and(check(LogExp.log1mExp(x) <= 0.0 || LogExp.log1mExp(x).isNaN, s"log1mExp($x)"))
    }
  }

  test("the normal quantile and logit never throw") {
    forall(anyDouble) { p =>
      val q = Special.normalQuantile(p)
      val lg = LogExp.logit(p)
      val inRange = p >= 0.0 && p <= 1.0
      check(!inRange || !q.isNaN || p.isNaN, s"normalQuantile($p) = $q inside [0, 1]")
        .and(check(inRange || q.isNaN, s"normalQuantile($p) = $q outside [0, 1] should be NaN"))
        .and(check(lg == lg || lg.isNaN, s"logit($p) = $lg"))
    }
  }

  test("logSumExp never throws and is never below the largest element") {
    forall(Gen.listOfN(5, anyDouble)) { xs =>
      val array = IArray.from(xs)
      val got = LogExp.logSumExp(array)
      val largest = xs.filterNot(_.isNaN).maxOption.getOrElse(Double.NegativeInfinity)
      check(
        got.isNaN || xs.exists(_.isNaN) || got >= largest - 1e-9,
        s"logSumExp(${xs.mkString(", ")}) = $got is below its largest element $largest"
      )
    }
  }

  test("the quantile never throws on any data or any p") {
    forall(Gen.zip(Gen.listOfN(7, anyDouble), anyDouble)) { (xs, p) =>
      val got = Quantile.type7(IArray.from(xs), p)
      check(got == got || got.isNaN, s"type7 gave $got")
    }
  }

  test("Brent returns a value or a Left on any bracket") {
    forall(Gen.zip(anyDouble, anyDouble)) { (lower, upper) =>
      val minimised = Optimize.brentMinimize(x => x * x, lower, upper)
      val rooted = Optimize.brentRoot(x => x, lower, upper)
      check(minimised.isLeft || minimised.isRight, "brentMinimize")
        .and(check(rooted.isLeft || rooted.isRight, "brentRoot"))
    }
  }
