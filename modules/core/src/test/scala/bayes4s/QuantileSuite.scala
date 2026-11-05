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
import bayes4s.testkit.Fixtures
import weaver.SimpleIOSuite

/** Type-7 empirical quantiles: the default of R, of NumPy's `linear` and of
  * `stan::math::quantile`. Every later summary and interval rests on this one definition.
  */
object QuantileSuite extends SimpleIOSuite with Approx:

  pureTest("the 8 type-7 cases asserted by NumPy and R") {
    Fixtures.quantile7 match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 8, s"${cases.length} cases, expected 8").and(
          forEach(cases) { c =>
            closeRel(Quantile.quantile(c.data, c.p), c.value, 1e-12, s"type7(p = ${c.p})")
          }
        )
  }

  pureTest("p = 0 is the minimum and p = 1 is the maximum") {
    val xs = IArray(3.2, -1.0, 7.5, 0.0, 0.0, 2.25, 9.0, -4.5, 1.1, 6.0, 5.5)
    check(Quantile.quantile(xs, 0.0) == -4.5, s"p = 0 gives ${Quantile.quantile(xs, 0.0)}")
      .and(check(Quantile.quantile(xs, 1.0) == 9.0, s"p = 1 gives ${Quantile.quantile(xs, 1.0)}"))
  }

  pureTest("the quantile of 1 to n has the closed form 1 + p (n - 1)") {
    val n = 101
    val xs = IArray.from((1 to n).map(_.toDouble))
    forEach(List(0.0, 0.025, 0.1, 0.25, 0.5, 0.75, 0.9, 0.975, 1.0)) { p =>
      closeRel(Quantile.quantile(xs, p), 1.0 + p * (n - 1), 1e-12, s"type7(1..$n, $p)")
    }
  }

  pureTest("the quantile is increasing in p and ignores the input order") {
    val xs = IArray(3.2, -1.0, 7.5, 0.0, 0.0, 2.25, 9.0, -4.5, 1.1, 6.0, 5.5)
    val ps = List(0.0, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 1.0)
    val increasing = forEach(ps.zip(ps.tail)) { (a, b) =>
      check(
        Quantile.quantile(xs, a) <= Quantile.quantile(xs, b),
        s"type7($a) <= type7($b): ${Quantile.quantile(xs, a)} and ${Quantile.quantile(xs, b)}"
      )
    }
    val shuffled = IArray.from(xs.toList.reverse)
    val orderFree = forEach(ps) { p =>
      check(
        Quantile.quantile(shuffled, p) == Quantile.quantile(xs, p),
        s"reversing the input changed type7($p)"
      )
    }
    val sorted = IArray.from(xs.toList.sorted)
    val sortedPath = forEach(ps) { p =>
      check(
        Quantile.quantileOfSorted(sorted, p) == Quantile.quantile(xs, p),
        s"quantileOfSorted disagrees with type7 at $p"
      )
    }
    increasing.and(orderFree).and(sortedPath)
  }

  pureTest("an empty input and a p outside [0, 1] give NaN") {
    val xs = IArray(1.0, 2.0, 3.0)
    check(Quantile.quantile(IArray.empty[Double], 0.5).isNaN, "an empty input gives NaN")
      .and(check(Quantile.quantile(xs, -0.1).isNaN, "p = -0.1 gives NaN"))
      .and(check(Quantile.quantile(xs, 1.1).isNaN, "p = 1.1 gives NaN"))
      .and(check(Quantile.quantile(xs, Double.NaN).isNaN, "p = NaN gives NaN"))
  }

  pureTest("a single value is its own quantile everywhere") {
    forEach(List(0.0, 0.5, 1.0)) { p =>
      check(Quantile.quantile(IArray(42.0), p) == 42.0, s"type7([42], $p)")
    }
  }
