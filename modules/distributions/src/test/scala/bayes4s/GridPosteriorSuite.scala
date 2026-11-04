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
import bayes4s.testkit.Reference
import weaver.SimpleIOSuite

/** The smallest Bayesian computation there is: a posterior over five candidate rates, on a grid.
  *
  * It is here because it is the first chapter of the manual and because it exercises the pieces
  * that everything else is built from — a mass function, a normalisation in the log domain, and a
  * sequential update — against numbers computed independently in Python.
  */
object GridPosteriorSuite extends SimpleIOSuite with Approx:

  private def field(name: String): Either[String, IArray[Double]] =
    Fixtures.workedExample("grid_posterior").flatMap { json =>
      json.hcursor.downField(name).as(using Reference.doubleArray).left.map(_.getMessage)
    }

  /** Posterior masses from log priors and log likelihoods, normalised in the log domain. */
  private def normalise(logWeights: IArray[Double]): IArray[Double] =
    val total = LogExp.logSumExp(logWeights)
    logWeights.map(w => math.exp(w - total))

  pureTest("the likelihood of 7 events at each candidate rate") {
    val wanted = for
      candidates <- field("candidates")
      likelihood <- field("likelihood")
      observed <- Fixtures
        .workedExample("grid_posterior")
        .flatMap(_.hcursor.downField("observed").as[Int].left.map(_.getMessage))
    yield (candidates, likelihood, observed)
    wanted match
      case Left(message) => failure(message)
      case Right((candidates, likelihood, observed)) =>
        forEach(candidates.toList.zipWithIndex) { (rate, i) =>
          Poisson.of(rate) match
            case Left(error) => failure(error.message)
            case Right(law) =>
              closeRel(
                math.exp(law.logMass(observed)),
                likelihood(i),
                1e-12,
                s"P($observed | rate = $rate)"
              )
        }
  }

  pureTest("the posterior over the grid, and the posterior after a second observation") {
    val wanted = for
      candidates <- field("candidates")
      prior <- field("prior")
      posterior <- field("posterior")
      observed <- Fixtures
        .workedExample("grid_posterior")
        .flatMap(_.hcursor.downField("observed").as[Int].left.map(_.getMessage))
      second <- Fixtures
        .workedExample("grid_posterior")
        .flatMap(
          _.hcursor
            .downField("after_second_observation")
            .downField("observed")
            .as[Int]
            .left
            .map(_.getMessage)
        )
      secondPosterior <- Fixtures
        .workedExample("grid_posterior")
        .flatMap(
          _.hcursor
            .downField("after_second_observation")
            .downField("posterior")
            .as(using Reference.doubleArray)
            .left
            .map(_.getMessage)
        )
    yield (candidates, prior, posterior, observed, second, secondPosterior)
    wanted match
      case Left(message) => failure(message)
      case Right((candidates, prior, posterior, observed, second, secondPosterior)) =>
        val laws = candidates.toList.map(Poisson.of)
        laws.collectFirst { case Left(error) => error } match
          case Some(error) => failure(error.message)
          case None =>
            val built = laws.collect { case Right(law) => law }
            val logFirst = IArray.from(
              built.zipWithIndex.map((law, i) => math.log(prior(i)) + law.logMass(observed))
            )
            val first = normalise(logFirst)
            val logSecond = IArray.from(
              built.zipWithIndex.map((law, i) => math.log(first(i)) + law.logMass(second))
            )
            val afterSecond = normalise(logSecond)
            val firstCheck = forEach(first.toList.zipWithIndex) { (p, i) =>
              closeRel(p, posterior(i), 1e-12, s"the posterior mass of candidate $i")
            }
            val secondCheck = forEach(afterSecond.toList.zipWithIndex) { (p, i) =>
              closeRel(p, secondPosterior(i), 1e-12, s"after the second observation, candidate $i")
            }
            val sums = closeRel(first.toList.sum, 1.0, 1e-12, "the first posterior sums to one")
              .and(closeRel(afterSecond.toList.sum, 1.0, 1e-12, "the second posterior sums to one"))
            firstCheck.and(secondCheck).and(sums)
  }
