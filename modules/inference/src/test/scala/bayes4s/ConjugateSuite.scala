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

import bayes4s.syntax.{*, given}
import bayes4s.testkit.Approx
import bayes4s.testkit.Ess
import bayes4s.testkit.MonteCarlo
import cats.Id
import weaver.SimpleIOSuite

/** Three posteriors with closed forms, sampled.
  *
  * A conjugate pair is the only kind of posterior whose mean and standard deviation are known
  * exactly, so these are the cases where the sampler can be wrong by a stated number of standard
  * errors rather than merely look plausible. Each one also exercises a different constraint —
  * positive, unconstrained, the unit interval — so the log Jacobian of each is in the path.
  */
object ConjugateSuite extends SimpleIOSuite with MonteCarlo with Approx:

  private def sampled(
      model: Model[Id],
      start: Double,
      seed: Long,
      samples: Int = 4000
  ): Either[BayesError, IArray[Double]] =
    val target = Model.target(model)
    Mcmc
      .run[Rand](
        Nuts.kernel[Rand](target, NutsSettings()),
        target,
        IArray(start),
        1000,
        samples,
        AdaptationSettings()
      )
      .runA(Seed(seed))
      .value
      .map(chain => IArray.from(chain.draws.map(q => Model.constrain(model, q))))

  private def moments(xs: IArray[Double]): (Double, Double) =
    val values = (0 until xs.length).map(xs.apply).toList
    val mean = values.sum / values.length
    val variance = values.map(x => (x - mean) * (x - mean)).sum / (values.length - 1)
    (mean, math.sqrt(variance))

  private def against(
      xs: IArray[Double],
      mean: Double,
      sd: Double,
      what: String
  ) =
    val (gotMean, gotSd) = moments(xs)
    val ess = Ess.of(List(xs))
    withinStandardErrors(gotMean, mean, Ess.standardError(List(xs)), 4.0, s"$what: the mean")
      .and(
        withinStandardErrors(gotSd, sd, sd / math.sqrt(2.0 * ess), 4.0, s"$what: the sd")
      )
      .and(check(ess > 300.0, s"$what: the effective sample size is only $ess"))

  /** Gamma(1.5, 0.2) and the counts of `worked_examples.json`: the posterior is Gamma(33.5, 4.2),
    * whose moments the fixture states.
    */
  pureTest("Gamma-Poisson: the posterior of worked_examples.json") {
    val counts = IArray(7, 11, 5, 9)
    val model: Model[Id] = new Model[Id]:
      val params: ParamSpec[Id] = ParamSpec.positive("rate")
      def logPrior[A: Real](rate: A): A =
        LogDensity.gamma(rate, 1.5.lift[A], 0.2.lift[A])
      def logLikelihood[A: Real](rate: A): A =
        Real[A].poissonSum(counts, Real[A].filled(counts.length)(rate))
    sampled(model, 2.0, 101L) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        against(draws, 7.976190476190475, 1.378075821760741, "Gamma-Poisson")
  }

  /** A Gaussian mean with a known scale: the posterior is Gaussian, and its two parameters are
    * written out here rather than taken from anywhere.
    */
  pureTest("Normal-Normal: the posterior of a mean with a known scale") {
    val y = IArray(1.2, 0.8, 1.9, 1.1, 0.4)
    val sigma = 0.5
    val priorMean = 0.0
    val priorSd = 10.0
    val precision = 1.0 / (priorSd * priorSd) + y.length / (sigma * sigma)
    val posteriorSd = math.sqrt(1.0 / precision)
    val posteriorMean =
      (priorMean / (priorSd * priorSd) + (0 until y.length).foldLeft(0.0)(
        _ + y(_)
      ) / (sigma * sigma)) /
        precision
    val model: Model[Id] = new Model[Id]:
      val params: ParamSpec[Id] = ParamSpec.real("mu")
      def logPrior[A: Real](mu: A): A =
        LogDensity.normal(mu, priorMean.lift[A], priorSd.lift[A])
      def logLikelihood[A: Real](mu: A): A =
        Real[A].normalSum(y, Real[A].filled(y.length)(mu), sigma.lift[A])
    sampled(model, 0.0, 202L) match
      case Left(error) => failure(error.message)
      case Right(draws) => against(draws, posteriorMean, posteriorSd, "Normal-Normal")
  }

  /** A probability with a Beta prior: the posterior is Beta(alpha + k, beta + n - k). */
  pureTest("Beta-Binomial: the posterior of a probability") {
    val trials = 10
    val successes = 7
    val alpha = 2.0 + successes
    val beta = 3.0 + (trials - successes)
    val total = alpha + beta
    val mean = alpha / total
    val sd = math.sqrt(alpha * beta / (total * total * (total + 1.0)))
    val model: Model[Id] = new Model[Id]:
      val params: ParamSpec[Id] = ParamSpec.unitInterval("theta")
      def logPrior[A: Real](theta: A): A =
        LogDensity.beta(theta, 2.0.lift[A], 3.0.lift[A])
      def logLikelihood[A: Real](theta: A): A = LogMass.binomial(successes, trials, theta)
    sampled(model, 0.0, 303L) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        against(draws, mean, sd, "Beta-Binomial")
          .and(
            check(
              (0 until draws.length).forall(i => draws(i) > 0.0 && draws(i) < 1.0),
              "a draw left the unit interval, so the transform is not doing its job"
            )
          )
  }

  /** The eight schools, non-centred, through `runNuts`: four chains, the parameters named, and
    * the posterior compared with what posteriordb's reference summary says about it.
    */
  pureTest("the eight schools agree with posteriordb on mu and tau") {
    EightSchools.Data.of(
      IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0),
      IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
    ) match
      case Left(error) => failure(error.message)
      case Right(data) =>
        val model = EightSchools.nonCentred(data)
        val settings = RunSettings(chains = 4, warmup = 1000, samples = 1000)
        Mcmc.runNuts(model, settings).runA(Seed(20261105L)).value match
          case Left(error) => failure(error.message)
          case Right(chains) =>
            val mus = chains.map(c => IArray.from(c.draws.map(q => Model.constrain(model, q).mu)))
            val taus = chains.map(c => IArray.from(c.draws.map(q => Model.constrain(model, q).tau)))
            val allMu = mus.flatMap(m => (0 until m.length).map(m.apply))
            val allTau = taus.flatMap(t => (0 until t.length).map(t.apply))
            val meanMu = allMu.sum / allMu.length
            val meanTau = allTau.sum / allTau.length
            // posteriordb's reference summary for eight_schools_noncentered, at 10 000 draws per
            // chain over 10 chains: mu near 4.4 and tau near 3.6. The comparison is loose on
            // purpose — the reference is itself Monte Carlo — and what it rules out is a posterior
            // in the wrong place.
            check(
              math.abs(meanMu - 4.4) < 0.6,
              s"the posterior mean of mu is $meanMu, expected near 4.4"
            )
              .and(
                check(
                  math.abs(meanTau - 3.6) < 0.8,
                  s"the posterior mean of tau is $meanTau, expected near 3.6"
                )
              )
              .and(check(allTau.forall(_ > 0.0), "a negative tau came out of the sampler"))
              .and(check(chains.length == 4, s"${chains.length} chains"))
  }
