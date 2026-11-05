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
import bayes4s.testkit.Ess
import bayes4s.testkit.MonteCarlo
import weaver.SimpleIOSuite

/** Static HMC and the random walk: the two samplers that come before NUTS.
  *
  * They are here for the reason the specification gives them: static HMC has no tree, no
  * multinomial sampling and no criterion, so when it works and NUTS does not the fault is in the
  * tree; and the random walk has no gradient, so when it works and HMC does not the fault is in
  * the gradient or the integrator. A hundred dimensions is where the difference between them
  * becomes the whole argument for Hamiltonian methods.
  */
object HmcSuite extends SimpleIOSuite with MonteCarlo with Approx:

  private def runWith(
      kernel: Kernel[Rand],
      target: DiffFn,
      start: IArray[Double],
      warmup: Int,
      samples: Int,
      seed: Long,
      settings: AdaptationSettings = AdaptationSettings()
  ): Either[BayesError, Chain] =
    Mcmc
      .run[Rand](kernel, target, start, warmup, samples, settings)
      .runA(Seed(seed))
      .value

  /** A random walk wants an acceptance near 0.23, not the 0.8 a Hamiltonian method wants: driving
    * it to 0.8 leaves a step size so small that the chain crawls. Stan's default is for HMC, and
    * the random walk is handed its own target here (docs/validation.md).
    */
  private val forRandomWalk: AdaptationSettings = AdaptationSettings(targetAccept = 0.234)

  /** A correlated Gaussian in a hundred dimensions, which is the specification's own test for
    * static HMC. Twenty leapfrog steps a transition, so a trajectory crosses the distribution.
    */
  pureTest("static HMC on a Gaussian correlated at 0.5 in a hundred dimensions") {
    val dimension = 100
    val rho = 0.5
    val target = Targets.equicorrelated(dimension, rho)
    val start = IArray.fill(dimension)(0.0)
    runWith(Hmc.kernel[Rand](target, 20), target, start, 500, 1000, 4242L) match
      case Left(error) => failure(error.message)
      case Right(chain) =>
        val draws = chain.draws
        val accept = chain.transitions.map(_.acceptStat).sum / chain.transitions.length
        // Every coordinate has variance 1 and every pair correlation 0.5, so the mean of all
        // hundred coordinates has variance (1 + 99 * 0.5) / 100: the slowest direction by far,
        // and the one worth testing.
        val totals =
          IArray.from(draws.map(q => (0 until dimension).foldLeft(0.0)(_ + q(_)) / dimension))
        val mean = (0 until totals.length).foldLeft(0.0)(_ + totals(_)) / totals.length
        val ess = Ess.of(List(totals))
        val truth = (1.0 + (dimension - 1) * rho) / dimension
        withinStandardErrors(mean, 0.0, math.sqrt(truth / ess), 4.0, "the mean of the average")
          .and(check(accept > 0.6, s"static HMC accepted only $accept of the time"))
          .and(check(ess > 50.0, s"the slowest direction has an effective sample size of $ess"))
          .and {
            // Each coordinate's own variance, pooled across the hundred of them: a hundred
            // estimates of the same 1, so the standard error shrinks by the square root of a
            // hundred and the test is sharp.
            val variances = (0 until dimension).map { i =>
              val values = draws.map(_(i))
              val m = values.sum / values.length
              values.map(x => (x - m) * (x - m)).sum / (values.length - 1)
            }
            val average = variances.sum / dimension
            val perCoordinate = Ess.of(List(IArray.from(draws.map(_(0)))))
            withinStandardErrors(
              average,
              1.0,
              math.sqrt(2.0 / perCoordinate) / math.sqrt(dimension / (1.0 + (dimension - 1) * rho)),
              4.0,
              "the average of the hundred variances"
            )
          }
  }

  /** The target the manual uses, and the one `worked_examples.json` states the moments of. */
  pureTest("a random walk recovers the Gamma(33.5, 4.2) of worked_examples.json") {
    val shape = 33.5
    val rate = 4.2
    val model: Model[cats.Id] = new Model[cats.Id]:
      val params: ParamSpec[cats.Id] = ParamSpec.positive("rate")
      def logPrior[A: Real](p: A): A =
        LogDensity.gamma(p, Real[A].const(shape), Real[A].const(rate))
      def logLikelihood[A: Real](p: A): A = Real[A].const(0.0)
    val target = Model.target(model)
    runWith(
      RandomWalkMetropolis.kernel[Rand](target, 1.0),
      target,
      IArray(2.0),
      1000,
      20_000,
      77L,
      forRandomWalk
    ) match
      case Left(error) => failure(error.message)
      case Right(chain) =>
        // The chain is on the log scale; the law is about the rate itself.
        val rates = IArray.from(chain.draws.map(q => math.exp(q(0))))
        val values = (0 until rates.length).map(rates.apply).toList
        val mean = values.sum / values.length
        val variance = values.map(x => (x - mean) * (x - mean)).sum / (values.length - 1)
        val ess = Ess.of(List(rates))
        val accept = chain.transitions.map(_.acceptStat).sum / chain.transitions.length
        withinStandardErrors(
          mean,
          7.976190476190475,
          Ess.standardError(List(rates)),
          4.0,
          "the posterior mean of the rate"
        )
          .and(
            withinStandardErrors(
              math.sqrt(variance),
              1.378075821760741,
              1.378075821760741 / math.sqrt(2.0 * ess),
              4.0,
              "its standard deviation"
            )
          )
          .and(check(ess > 100.0, s"the random walk gave an effective sample size of $ess"))
          .and(check(accept > 0.1 && accept < 0.9, s"it accepted $accept of the time"))
  }

  /** The same target through all three kernels: they must agree about the posterior, because the
    * posterior is a property of the model and not of the sampler.
    */
  pureTest("the three kernels agree on the mean of the same target") {
    val target = Targets.equicorrelated(4, 0.6)
    val start = IArray.fill(4)(0.0)
    val runs = List(
      ("NUTS", Nuts.kernel[Rand](target, NutsSettings()), 1000, 2000, AdaptationSettings()),
      ("static HMC", Hmc.kernel[Rand](target, 12), 1000, 2000, AdaptationSettings()),
      ("random walk", RandomWalkMetropolis.kernel[Rand](target, 1.0), 2000, 60_000, forRandomWalk)
    )
    forEach(runs) { (name, kernel, warmup, samples, settings) =>
      runWith(kernel, target, start, warmup, samples, 1234L, settings) match
        case Left(error) => failure(s"$name: ${error.message}")
        case Right(chain) =>
          val first = IArray.from(chain.draws.map(_(0)))
          val values = (0 until first.length).map(first.apply).toList
          val mean = values.sum / values.length
          val variance = values.map(x => (x - mean) * (x - mean)).sum / (values.length - 1)
          val ess = Ess.of(List(first))
          // A chain that has not explored can still look precise to an autocorrelation estimator,
          // so the span of the draws is checked as well: a standard Gaussian reaches past 2.5.
          val span = values.max - values.min
          withinStandardErrors(
            mean,
            0.0,
            Ess.standardError(List(first)),
            4.0,
            s"$name: the mean of the first coordinate"
          ).and(
            withinStandardErrors(
              variance,
              1.0,
              math.sqrt(2.0 / ess),
              4.0,
              s"$name: its variance"
            )
          ).and(
            check(span > 5.0, s"$name: the draws only span $span, so the chain did not explore")
          )
    }
  }

  pureTest("a starting point where the density is not a number is refused, not sampled") {
    val target = Targets.standardGaussian
    val refused = runWith(
      Nuts.kernel[Rand](target, NutsSettings()),
      target,
      IArray(Double.NaN, 0.0),
      10,
      10,
      1L
    )
    check(refused.isLeft, s"a NaN starting point gave $refused")
      .and(
        check(
          refused.left.exists(_.message.contains("starting point")),
          s"the error does not say what was wrong: $refused"
        )
      )
  }
