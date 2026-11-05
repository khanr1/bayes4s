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

/** The log-exp family against mpmath, and the identities that pin down its edges.
  *
  * The point of these functions is the far tails: `logistic(-800)` must be 0 rather than a
  * division by an overflowed exponential, and `log1pExp(800)` must be 800 rather than `+Inf`.
  */
object LogExpSuite extends SimpleIOSuite with Approx:

  pureTest("logistic on 21 points from -800 to 800 (mpmath, 50 digits)") {
    Fixtures.specialPoints("sigmoid") match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 21, s"${cases.length} points, expected 21").and(
          forEach(cases)(c => closeRel(LogExp.invLogit(c.x), c.value, 1e-13, s"logistic(${c.x})"))
        )
  }

  pureTest("logInvLogit on 21 points (mpmath, 50 digits)") {
    Fixtures.specialPoints("log_sigmoid") match
      case Left(message) => failure(message)
      case Right(cases) =>
        forEach(cases)(c =>
          closeRel(LogExp.logInvLogit(c.x), c.value, 1e-13, s"logInvLogit(${c.x})")
        )
  }

  pureTest("log1pExp on 21 points, the softplus (mpmath, 50 digits)") {
    Fixtures.specialPoints("softplus_log1p_exp") match
      case Left(message) => failure(message)
      case Right(cases) =>
        forEach(cases)(c => closeRel(LogExp.log1pExp(c.x), c.value, 1e-13, s"log1pExp(${c.x})"))
  }

  pureTest("log1mExp on 8 points (mpmath, 50 digits)") {
    Fixtures.specialPoints("log1m_exp") match
      case Left(message) => failure(message)
      case Right(cases) =>
        forEach(cases)(c => closeRel(LogExp.log1mExp(c.x), c.value, 1e-13, s"log1mExp(${c.x})"))
  }

  pureTest("logit on 8 points down to p = 1e-300 (mpmath, 50 digits)") {
    Fixtures.specialProbs("logit") match
      case Left(message) => failure(message)
      case Right(cases) =>
        forEach(cases)(c => closeRel(LogExp.logit(c.p), c.value, 1e-13, s"logit(${c.p})"))
  }

  pureTest("logSumExp on 6 vectors (mpmath, 50 digits)") {
    Fixtures.specialVectors("log_sum_exp") match
      case Left(message) => failure(message)
      case Right(cases) =>
        forEach(cases) { c =>
          closeRel(
            LogExp.logSumExp(c.x),
            c.value,
            1e-13,
            s"logSumExp(${c.x.toList.mkString(", ")})"
          )
        }
  }

  pureTest("logSumExp is invariant under a shift of every element") {
    val xs = IArray(-3.25, 0.5, 7.0, 7.25, 120.0)
    val base = LogExp.logSumExp(xs)
    forEach(List(-500.0, -10.0, 10.0, 500.0)) { shift =>
      closeRel(
        LogExp.logSumExp(xs.map(_ + shift)) - shift,
        base,
        1e-13,
        s"logSumExp(xs + $shift) - $shift"
      )
    }
  }

  pureTest("the degenerate inputs of logSumExp are -Inf, not NaN") {
    check(
      LogExp.logSumExp(IArray.empty[Double]) == Double.NegativeInfinity,
      s"an empty vector gives ${LogExp.logSumExp(IArray.empty[Double])}"
    ).and {
      val allMinusInf = IArray(Double.NegativeInfinity, Double.NegativeInfinity)
      check(
        LogExp.logSumExp(allMinusInf) == Double.NegativeInfinity,
        s"all -Inf gives ${LogExp.logSumExp(allMinusInf)}"
      )
    }.and {
      val one = IArray(Double.NegativeInfinity, 2.5)
      closeRel(LogExp.logSumExp(one), 2.5, 1e-15, "a single finite element among -Inf")
    }
  }

  pureTest("the far tails stay finite") {
    check(LogExp.invLogit(-800.0) == 0.0, s"logistic(-800) = ${LogExp.invLogit(-800.0)}")
      .and(check(LogExp.invLogit(800.0) == 1.0, s"logistic(800) = ${LogExp.invLogit(800.0)}"))
      .and(close(LogExp.log1pExp(800.0), 800.0, 1e-13, "log1pExp(800) = 800"))
      .and(check(LogExp.log1pExp(-800.0) == 0.0, s"log1pExp(-800) = ${LogExp.log1pExp(-800.0)}"))
      .and(close(LogExp.logInvLogit(-800.0), -800.0, 1e-13, "logInvLogit(-800) = -800"))
  }

  // The two directions of this round trip are not equally informative. Going from a probability
  // and back keeps every digit, even at p = 1e-300, because the small number stays small. Going
  // from a log odds and back cannot: logistic(30) is 1 - 9.4e-14, and a double near 1 holds that
  // deviation to three digits only, so the odds come back with an absolute error near 1e-3. The
  // grid for that direction therefore stops where the arithmetic keeps the information, and the
  // demanding assertion is made on the direction that does.
  pureTest("logistic and logit undo each other, each where its arithmetic allows") {
    val fromProbability = forEach(List(1e-300, 1e-30, 1e-10, 0.001, 0.25, 0.5, 0.75, 0.9)) { p =>
      closeRel(LogExp.invLogit(LogExp.logit(p)), p, 1e-14, s"logistic(logit($p))")
    }
    val fromLogOdds = forEach(List(-14.0, -5.0, -0.5, 0.0, 0.5, 5.0, 14.0)) { x =>
      closeRel(LogExp.logit(LogExp.invLogit(x)), x, 1e-10, s"logit(logistic($x))")
    }
    // Where the round trip gives up, and what it gives up to: at 30 the odds come back to three
    // digits, at 300 the probability has saturated to exactly 1 and the only honest answer is an
    // infinity. A model that works on log odds of this size has to stay on the log scale.
    val beyond = close(
      LogExp.logit(LogExp.invLogit(30.0)),
      30.0,
      1e-2,
      "at a log odds of 30 the round trip keeps three digits"
    ).and(check(LogExp.invLogit(300.0) == 1.0, "logistic(300) saturates to exactly 1"))
      .and(
        check(
          LogExp.logit(LogExp.invLogit(300.0)) == Double.PositiveInfinity,
          "and the round trip through a saturated probability is +Inf, not a wrong number"
        )
      )
    fromProbability.and(fromLogOdds).and(beyond)
  }

  pureTest("logDiffExp of two equal arguments is -Inf, and it matches the direct form") {
    check(
      LogExp.logDiffExp(3.0, 3.0) == Double.NegativeInfinity,
      s"logDiffExp(3, 3) = ${LogExp.logDiffExp(3.0, 3.0)}"
    ).and(
      closeRel(
        LogExp.logDiffExp(2.0, 0.5),
        math.log(math.exp(2.0) - math.exp(0.5)),
        1e-13,
        "logDiffExp(2, 0.5)"
      )
    )
  }
