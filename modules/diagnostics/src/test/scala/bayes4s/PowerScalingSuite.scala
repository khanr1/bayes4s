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

/** Prior and likelihood sensitivity, against ArviZ's `psense`.
  *
  * Two cases, built to land on opposite sides of the diagnosis: a tight prior placed away from the
  * data, where both sensitivities are large and the posterior is a compromise neither the prior nor
  * the data would recognise, and a wide one where neither is. A diagnostic that reported the same
  * thing for both would be useless, so the suite asserts the **numbers** against ArviZ at 1e-6 and
  * the **reading** against the rule the fixture states.
  */
object PowerScalingSuite extends SimpleIOSuite with Approx:

  pureTest("the two sensitivities of both cases, against az.psense at 1e-6") {
    Fixtures.powerScalingCases match
      case Left(message) => failure(message)
      case Right((cases, lower, upper, _)) =>
        val assertions = forEach(cases) { c =>
          val prior = PowerScaling.sensitivity(c.draws, c.logPrior, lower, upper)
          val likelihood = PowerScaling.sensitivity(c.draws, c.logLikelihood, lower, upper)
          check(c.draws.length == 4000, s"${c.label}: ${c.draws.length} draws")
            .and(closeRel(prior, c.priorSensitivity, 1e-6, s"${c.label}: the prior sensitivity"))
            .and(
              closeRel(
                likelihood,
                c.likelihoodSensitivity,
                1e-6,
                s"${c.label}: the likelihood sensitivity"
              )
            )
        }
        check(cases.length == 2, s"${cases.length} cases, expected 2")
          .and(check(lower == 0.99 && upper == 1.01, s"the exponents are $lower and $upper"))
          .and(assertions)
  }

  pureTest("the diagnosis is the one the fixture's rule gives, and the two cases differ") {
    Fixtures.powerScalingCases match
      case Left(message) => failure(message)
      case Right((cases, lower, upper, threshold)) =>
        val readings = cases.map { c =>
          val prior = PowerScaling.sensitivity(c.draws, c.logPrior, lower, upper)
          val likelihood = PowerScaling.sensitivity(c.draws, c.logLikelihood, lower, upper)
          (c.label, PowerScaling.diagnose(prior, likelihood, threshold), prior, likelihood)
        }
        val conflict = readings.find(_._1 == "conflict")
        val fine = readings.find(_._1 == "no_conflict")
        (conflict, fine) match
          case (Some((_, hard, hardPrior, hardLikelihood)), Some((_, easy, easyPrior, _))) =>
            check(
              hard == "prior-data conflict",
              s"the conflicting case was read as '$hard' (prior $hardPrior, " +
                s"likelihood $hardLikelihood)"
            )
              .and(
                check(
                  easy != "prior-data conflict",
                  s"the harmless case was also read as a conflict (prior $easyPrior)"
                )
              )
              .and(
                check(
                  hardPrior > 5.0 * easyPrior,
                  s"the two prior sensitivities are $hardPrior and $easyPrior, too close to tell " +
                    "the cases apart"
                )
              )
              .and(check(threshold == 0.05, s"the threshold is $threshold"))
          case _ => failure(s"the fixture's labels are ${readings.map(_._1)}")
  }

  /** The distance itself, on inputs whose answer is known. */
  pureTest("the cumulative Jensen-Shannon distance is zero against the sample's own weights") {
    val draws = IArray.tabulate(200)(i => math.sin(i * 0.7) * 2.0)
    val uniform = IArray.fill(200)(1.0 / 200.0)
    val tilted = IArray.tabulate(200)(i => if draws(i) > 0.0 then 2.0 / 200.0 else 0.5 / 200.0)
    val none = PowerScaling.jensenShannon(draws, uniform)
    val some = PowerScaling.jensenShannon(draws, tilted)
    check(
      none < 1e-3,
      s"equal weights gave a distance of $none, which should be near zero"
    )
      .and(check(some > 10.0 * math.max(none, 1e-9), s"a tilt gave $some against $none"))
      .and(check(some < 1.0, s"the distance is bounded by one and came out $some"))
      .and(check(PowerScaling.jensenShannon(IArray(1.0), IArray(1.0)).isNaN, "a single draw"))
  }

  pureTest("a sensitivity of a sample it cannot read is NaN, not a number") {
    val draws = IArray(1.0, 2.0, 3.0)
    check(PowerScaling.sensitivity(draws, draws).isNaN, "three draws is not enough")
      .and(
        check(
          PowerScaling
            .sensitivity(IArray.tabulate(100)(i => i.toDouble), IArray.fill(50)(0.0))
            .isNaN,
          "a component of the wrong length"
        )
      )
  }

  /** The rule, stated as the fixture states it, over all four combinations. */
  pureTest("the four readings of the two numbers") {
    check(PowerScaling.diagnose(0.2, 0.2, 0.05) == "prior-data conflict", "both large")
      .and(
        check(
          PowerScaling.diagnose(0.01, 0.2, 0.05) == "the data is doing the work",
          "only the likelihood"
        )
      )
      .and(
        check(
          PowerScaling.diagnose(0.2, 0.01, 0.05) == "the prior is doing the work",
          "only the prior"
        )
      )
      .and(check(PowerScaling.diagnose(0.01, 0.01, 0.05) == "neither is sensitive", "neither"))
  }
