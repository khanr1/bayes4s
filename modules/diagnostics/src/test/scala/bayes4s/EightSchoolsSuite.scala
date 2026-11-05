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

/** The two parameterisations of the eight schools, and what the sampler says about each.
  *
  * The centred form has a funnel in it: when `tau` is small the eight effects are squeezed into a
  * band the integrator cannot follow, and a correct sampler **reports divergent transitions**
  * rather than quietly failing to visit that region. The non-centred form does not, and reports
  * none. That contrast is the specification's own test of whether divergences are detected at all,
  * and it is worth more than either half alone: a sampler that reported divergences everywhere
  * would pass the first check and fail the second.
  *
  * Phase 3 asserted that the two describe the same posterior, exactly, through the change of
  * variables. This is about the sampler, not the model.
  */
object EightSchoolsSuite extends SimpleIOSuite with Approx:

  private val data = Fixtures
    .posteriordbData("eight_schools-eight_schools_noncentered")
    .flatMap { json =>
      val cursor = json.hcursor
      for
        y <- cursor.get[List[Double]]("y").left.map(_.getMessage)
        sigma <- cursor.get[List[Double]]("sigma").left.map(_.getMessage)
        built <- EightSchools.Data.of(IArray.from(y), IArray.from(sigma)).left.map(_.message)
      yield built
    }

  private val settings = RunSettings(chains = 4, warmup = 1000, samples = 1000)

  private def fitted[T[_]](model: Model[T]): Either[String, List[Chain]] =
    Mcmc.runNuts(model, settings).runA(Seed(20261105L)).value.left.map(_.message)

  pureTest("the centred parameterisation diverges and the non-centred one does not") {
    data match
      case Left(message) => failure(message)
      case Right(schools) =>
        (fitted(EightSchools.centred(schools)), fitted(EightSchools.nonCentred(schools))) match
          case (Right(centred), Right(nonCentred)) =>
            val hard = SamplerReport.of(centred, settings.nuts.maxTreeDepth)
            val easy = SamplerReport.of(nonCentred, settings.nuts.maxTreeDepth)
            val total = settings.chains * settings.samples
            check(
              hard.divergent > 0,
              "the centred eight schools produced no divergent transition, which means " +
                "divergences are never reported"
            )
              .and(
                check(
                  hard.divergent.toDouble / total > 0.005,
                  s"only ${hard.divergent} of $total transitions diverged, which is too few to be " +
                    "the funnel being reported"
                )
              )
              // The non-centred form is allowed the odd one — a divergence is a threshold on a
              // continuous quantity — but not a hundredth of its transitions.
              .and(
                check(
                  easy.divergent.toDouble / total < 0.002,
                  s"the non-centred form produced ${easy.divergent} divergent transitions of $total"
                )
              )
              .and(
                check(
                  hard.divergent > 10 * math.max(easy.divergent, 1),
                  s"the centred form had ${hard.divergent} divergences and the non-centred one " +
                    s"${easy.divergent}: too close to say the parameterisation is what matters"
                )
              )
          case (Left(message), _) => failure(s"centred: $message")
          case (_, Left(message)) => failure(s"non-centred: $message")
  }

  /** The other two things the centred form shows and the non-centred one does not. */
  pureTest("the centred form is also worse by every other diagnostic") {
    data match
      case Left(message) => failure(message)
      case Right(schools) =>
        val centredModel = EightSchools.centred(schools)
        val nonCentredModel = EightSchools.nonCentred(schools)
        (fitted(centredModel), fitted(nonCentredModel)) match
          case (Right(centred), Right(nonCentred)) =>
            val hardRows = Summary.of(centredModel, centred)
            val easyRows = Summary.of(nonCentredModel, nonCentred)
            val hardTau = hardRows.find(_.name == "tau")
            val easyTau = easyRows.find(_.name == "tau")
            val hardEnergy = SamplerReport.of(centred, 10).eBfmi
            val easyEnergy = SamplerReport.of(nonCentred, 10).eBfmi
            (hardTau, easyTau) match
              case (Some(hard), Some(easy)) =>
                check(
                  easy.essBulk > 2.0 * hard.essBulk,
                  f"tau's bulk effective size is ${hard.essBulk}%.0f centred and " +
                    f"${easy.essBulk}%.0f non-centred, which is not the gain the " +
                    "reparameterisation is for"
                )
                  .and(
                    check(
                      easyEnergy.min > hardEnergy.min,
                      s"the energy diagnostic is ${hardEnergy.min} at worst centred and " +
                        s"${easyEnergy.min} non-centred"
                    )
                  )
                  // Both must still be sampling the same posterior: a reparameterisation that
                  // changed the answer would be a bug, not an improvement.
                  .and(
                    check(
                      math.abs(hard.mean - easy.mean) < 1.5,
                      f"tau's posterior mean is ${hard.mean}%.3f centred and ${easy.mean}%.3f " +
                        "non-centred, which are not the same posterior"
                    )
                  )
              case _ => failure("no row for tau")
          case (Left(message), _) => failure(s"centred: $message")
          case (_, Left(message)) => failure(s"non-centred: $message")
  }
