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

/** The Bernoulli on the log-odds scale, where going through the probability loses everything.
  *
  * At eta = 25 the probability of a zero is about 1.4e-11, and `log(1 - invLogit(25))` computed in
  * that order returns the log of a double that has already rounded to 1 minus nothing. The exact
  * value is -25.000000000013888 (mpmath). Stan asserts -25.0, which is within 1e-12 relative;
  * **SciPy returns -24.99999582, which is not**, and that is why the reference for this one is
  * mpmath and not the usual pair.
  */
object BernoulliLogitSuite extends SimpleIOSuite with Approx:

  /** mpmath at 50 digits, recorded in the reference package's notes. */
  private val exactAtTwentyFive = -25.000000000013888

  pureTest("bernoulliLogit(0 | 25) is exact, where SciPy is not") {
    BernoulliLogit.of(25.0) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        val got = law.logMass(0)
        closeRel(got, exactAtTwentyFive, 1e-12, "log mass of a zero at a log odds of 25")
          .and(
            check(
              math.abs(got - -24.99999582) > 1e-7,
              s"$got agrees with SciPy's wrong value rather than with mpmath"
            )
          )
  }

  pureTest("the symmetric case, a one at a log odds of -25") {
    BernoulliLogit.of(-25.0) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        closeRel(law.logMass(1), exactAtTwentyFive, 1e-12, "log mass of a one at a log odds of -25")
  }

  pureTest("the two masses sum to one, from the far tails to the middle") {
    forEach(List(-40.0, -25.0, -5.0, -0.5, 0.0, 0.5, 5.0, 25.0, 40.0)) { eta =>
      BernoulliLogit.of(eta) match
        case Left(error) => failure(error.message)
        case Right(law) =>
          val total = math.exp(law.logMass(0)) + math.exp(law.logMass(1))
          closeRel(total, 1.0, 1e-12, s"the total mass at a log odds of $eta")
    }
  }

  pureTest("it agrees with the plain Bernoulli where the probability is representable") {
    forEach(List(-5.0, -1.0, 0.0, 1.0, 5.0)) { eta =>
      val probability = LogExp.invLogit(eta)
      val viaLogit = BernoulliLogit.of(eta).map(_.logMass(1)).left.map(_.message)
      val viaProbability = Binomial.of(1, probability).map(_.logMass(1)).left.map(_.message)
      (viaLogit, viaProbability) match
        case (Right(a), Right(b)) => closeRel(a, b, 1e-12, s"at a log odds of $eta")
        case (a, b) => failure(s"one of the two failed: $a, $b")
    }
  }

  pureTest("a count outside {0, 1} has no mass, and an infinite log odds is refused") {
    BernoulliLogit.of(0.0) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        check(law.logMass(2) == Double.NegativeInfinity, "a count of 2")
          .and(check(law.logMass(-1) == Double.NegativeInfinity, "a count of -1"))
          .and(closeRel(law.mean, 0.5, 1e-15, "the mean at a log odds of 0"))
          .and(closeRel(law.variance, 0.25, 1e-15, "the variance at a log odds of 0"))
          .and(check(BernoulliLogit.of(Double.PositiveInfinity).isLeft, "an infinite log odds"))
          .and(check(BernoulliLogit.of(Double.NaN).isLeft, "a NaN log odds"))
  }
