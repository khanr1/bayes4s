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

/** Monte Carlo comparisons stated in standard errors.
  *
  * A statistical test announces its error budget: the standard error it used and the threshold in
  * standard errors. The failure message carries both, so a failure can be read without opening
  * the code. The standard error is `sd/sqrt(n)` for independent draws, and an ESS-based MCSE for
  * MCMC chains: using the raw number of draws would make the test far too strict.
  */
trait MonteCarlo:

  def withinStandardErrors(
      estimate: Double,
      truth: Double,
      standardError: Double,
      sigmas: Double,
      clue: => String
  )(using SourceLocation): Expectations =
    if !(standardError > 0.0) || !standardError.isFinite then
      Expectations.Helpers.failure(
        s"$clue: the standard error must be finite and positive, got $standardError"
      )
    else
      val z = (estimate - truth) / standardError
      if math.abs(z) <= sigmas then Expectations.Helpers.success
      else
        Expectations.Helpers.failure(
          s"$clue: estimate $estimate, truth $truth, standard error $standardError, " +
            s"|z| = ${math.abs(z)} > $sigmas standard errors"
        )

object MonteCarlo extends MonteCarlo
