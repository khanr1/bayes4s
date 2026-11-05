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

import scala.annotation.nowarn

import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** The names the naming rule retired still work, and still agree with their replacements.
  *
  * An alias that drifted from the function it replaces would be worse than no alias at all: code
  * written against the old name would keep compiling and start computing something else. These
  * assertions are what make the deprecation period safe, and they disappear with the aliases at
  * v0.1.
  *
  * The deprecation warnings are silenced here, and only here: this suite exists to call the
  * deprecated names, and CI makes warnings fatal everywhere else.
  */
@nowarn("cat=deprecation")
object DeprecatedNamesSuite extends SimpleIOSuite with Approx:

  private val points = List(-800.0, -30.0, -1.0, -0.0, 0.0, 0.5, 1.0, 30.0, 800.0, Double.NaN)

  pureTest("logistic forwards to invLogit") {
    forEach(points) { x =>
      check(sameIeee(LogExp.logistic(x), LogExp.invLogit(x)), s"at $x")
    }
  }

  pureTest("logSigmoid forwards to logInvLogit") {
    forEach(points) { x =>
      check(sameIeee(LogExp.logSigmoid(x), LogExp.logInvLogit(x)), s"at $x")
    }
  }

  pureTest("normalQuantile forwards to invPhi") {
    forEach(List(0.0, 1e-300, 1e-12, 0.025, 0.5, 0.975, 1.0, 1.5, Double.NaN)) { p =>
      check(sameIeee(Special.normalQuantile(p), Special.invPhi(p)), s"at $p")
    }
  }

  pureTest("type7 and type7OfSorted forward to quantile and quantileOfSorted") {
    val xs = IArray(3.2, -1.0, 7.5, 0.0, 2.25, 9.0, -4.5)
    val sorted = IArray.from(xs.toList.sorted)
    forEach(List(0.0, 0.05, 0.25, 0.5, 0.9, 1.0)) { p =>
      check(sameIeee(Quantile.type7(xs, p), Quantile.quantile(xs, p)), s"type7 at $p")
        .and(
          check(
            sameIeee(Quantile.type7OfSorted(sorted, p), Quantile.quantileOfSorted(sorted, p)),
            s"type7OfSorted at $p"
          )
        )
    }
  }
