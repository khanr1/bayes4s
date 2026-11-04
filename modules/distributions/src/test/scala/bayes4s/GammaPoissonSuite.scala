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

/** The Gamma-Poisson conjugate pair against worked examples whose numbers are invented for this
  * library and computed independently in Python.
  *
  * The predictive interval is obtained here by summing the mass function, which exercises
  * `logMass` across its whole range rather than at the handful of arguments Stan Math asserts; and
  * comparing it with the Poisson that merely plugs in the posterior mean is the point of the
  * exercise: the two have the same mean and very different tails.
  */
object GammaPoissonSuite extends SimpleIOSuite with Approx:

  private def pair(section: String, field: String): Either[String, IArray[Double]] =
    Fixtures.workedExample(section).flatMap { json =>
      json.hcursor.downField(field).as(using Reference.doubleArray).left.map(_.getMessage)
    }

  private def scalar(section: String, field: String): Either[String, Double] =
    Fixtures.workedExample(section).flatMap { json =>
      json.hcursor.downField(field).as(using Reference.looseDouble).left.map(_.getMessage)
    }

  /** The smallest k whose cumulative mass reaches each p: the convention SciPy's `ppf` uses. */
  private def quantiles(mass: Int => Double, ps: List[Double], bound: Int): List[Int] =
    val cumulative = (0 to bound).scanLeft(0.0)((acc, k) => acc + math.exp(mass(k))).tail.toList
    ps.map(p => cumulative.indexWhere(_ >= p))

  pureTest("the conjugate update turns Gamma(1.5, 0.2) and four counts into Gamma(33.5, 4.2)") {
    val wanted = for
      prior <- pair("gamma_poisson_conjugate", "prior")
      counts <- pair("gamma_poisson_conjugate", "counts")
      posterior <- pair("gamma_poisson_conjugate", "posterior")
      mean <- scalar("gamma_poisson_conjugate", "mean")
      sd <- scalar("gamma_poisson_conjugate", "sd")
    yield (prior, counts, posterior, mean, sd)
    wanted match
      case Left(message) => failure(message)
      case Right((prior, counts, posterior, mean, sd)) =>
        val updated = Gamma
          .of(prior(0), prior(1))
          .left
          .map(_.message)
          .flatMap(g =>
            GammaPoisson
              .update(g, IArray.from(counts.toList.map(_.toInt)), counts.length.toDouble)
              .left
              .map(_.message)
          )
        updated match
          case Left(message) => failure(message)
          case Right(got) =>
            closeRel(got.alpha, posterior(0), 1e-12, "the posterior shape")
              .and(closeRel(got.beta, posterior(1), 1e-12, "the posterior rate"))
              .and(closeRel(got.mean, mean, 1e-12, "the posterior mean"))
              .and(closeRel(math.sqrt(got.variance), sd, 1e-12, "the posterior standard deviation"))
  }

  pureTest("the update with an exposure of 2.6 and 13 visible counts") {
    val wanted = for
      prior <- pair("gamma_poisson_with_exposure", "prior")
      posterior <- pair("gamma_poisson_with_exposure", "posterior")
      count <- scalar("gamma_poisson_with_exposure", "visible_count")
      exposure <- scalar("gamma_poisson_with_exposure", "exposure")
      mean <- scalar("gamma_poisson_with_exposure", "mean")
    yield (prior, posterior, count, exposure, mean)
    wanted match
      case Left(message) => failure(message)
      case Right((prior, posterior, count, exposure, mean)) =>
        val updated = Gamma
          .of(prior(0), prior(1))
          .left
          .map(_.message)
          .flatMap(g => GammaPoisson.update(g, IArray(count.toInt), exposure).left.map(_.message))
        updated match
          case Left(message) => failure(message)
          case Right(got) =>
            closeRel(got.alpha, posterior(0), 1e-12, "the posterior shape")
              .and(closeRel(got.beta, posterior(1), 1e-12, "the posterior rate"))
              .and(closeRel(got.mean, mean, 1e-12, "the posterior mean"))
  }

  pureTest("the negative binomial predictive, and the Poisson that plugs in the mean") {
    val wanted = for
      posterior <- pair("negative_binomial_predictive", "posterior")
      mean <- scalar("negative_binomial_predictive", "mean")
      sd <- scalar("negative_binomial_predictive", "sd")
      interval <- pair("negative_binomial_predictive", "interval_90")
      pluginSd <- scalar("negative_binomial_predictive", "plugin_poisson_sd")
      pluginInterval <- pair("negative_binomial_predictive", "plugin_interval_90")
    yield (posterior, mean, sd, interval, pluginSd, pluginInterval)
    wanted match
      case Left(message) => failure(message)
      case Right((posterior, mean, sd, interval, pluginSd, pluginInterval)) =>
        val predictive = Gamma
          .of(posterior(0), posterior(1))
          .flatMap(GammaPoisson.predictive)
          .left
          .map(_.message)
        predictive match
          case Left(message) => failure(message)
          case Right(nb) =>
            val plugin = Poisson.of(nb.mean).left.map(_.message)
            val moments = closeRel(nb.mean, mean, 1e-12, "the predictive mean")
              .and(closeRel(math.sqrt(nb.variance), sd, 1e-12, "the predictive standard deviation"))
            val bounds = quantiles(nb.logMass, List(0.05, 0.95), 400)
            val tails = check(
              bounds == List(interval(0).toInt, interval(1).toInt),
              s"the 90 % predictive interval came out as $bounds, expected ${interval.toList}"
            )
            val pluginChecks = plugin match
              case Left(message) => failure(message)
              case Right(p) =>
                val pluginBounds = quantiles(p.logMass, List(0.05, 0.95), 200)
                closeRel(math.sqrt(p.variance), pluginSd, 1e-12, "the plug-in standard deviation")
                  .and(
                    check(
                      pluginBounds == List(pluginInterval(0).toInt, pluginInterval(1).toInt),
                      s"the plug-in interval came out as $pluginBounds, expected ${pluginInterval.toList}"
                    )
                  )
                  .and(
                    check(
                      math.sqrt(p.variance) < math.sqrt(nb.variance),
                      "the plug-in Poisson should be narrower than the predictive it approximates"
                    )
                  )
            moments.and(tails).and(pluginChecks)
  }

  pureTest("an update refuses a negative count or a non-positive exposure") {
    Gamma.of(1.5, 0.2) match
      case Left(error) => failure(error.message)
      case Right(prior) =>
        check(GammaPoisson.update(prior, IArray(-1), 1.0).isLeft, "a negative count was accepted")
          .and(
            check(GammaPoisson.update(prior, IArray(1), 0.0).isLeft, "a zero exposure was accepted")
          )
          .and(check(GammaPoisson.update(prior, IArray(1), -2.0).isLeft, "a negative exposure"))
          .and(
            check(
              GammaPoisson.update(prior, IArray.empty[Int], 1.0).isRight,
              "no counts is a valid update"
            )
          )
  }
