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

/** Pareto-smoothed leave-one-out cross-validation, against ArviZ.
  *
  * ArviZ is the reference here and nowhere else: it is the only implementation of PSIS-LOO to
  * compare against, and its disagreement with Stan is about the convergence diagnostics (phase 5),
  * not about this. The fixture carries four thousand draws of a twenty-observation log likelihood
  * whose twentieth observation is an outlier, so the Pareto shape has to come out above 0.7 there
  * and below it everywhere else — which is the whole diagnostic.
  */
object LooSuite extends SimpleIOSuite with Approx:

  /** The fixture stores the log likelihood by draw; `Loo` takes it by observation. */
  private val transposed: Either[String, (IArray[IArray[Double]], Int, Int)] =
    Fixtures.psisLogLikelihood.map { byDraw =>
      val draws = byDraw.length
      val observations = if draws == 0 then 0 else byDraw(0).length
      (
        IArray.tabulate(observations)(i => IArray.tabulate(draws)(s => byDraw(s)(i))),
        draws,
        observations
      )
    }

  pureTest("the elpd, its standard error and p_loo, against az.loo at 1e-6") {
    (transposed, Fixtures.psisLoo) match
      case (Right((logLik, draws, observations)), Right(expected)) =>
        // A single chain, which is what the fixture holds, so ArviZ uses a relative effective
        // sample size of exactly one for every observation.
        val result = Loo.psis(logLik, 1.0)
        check(draws == 4000 && observations == 20, s"$draws draws and $observations observations")
          .and(closeRel(result.elpd, expected.elpd, 1e-6, "the elpd"))
          .and(closeRel(result.se, expected.se, 1e-6, "its standard error"))
          .and(closeRel(result.pLoo, expected.pLoo, 1e-6, "the effective number of parameters"))
      case (Left(message), _) => failure(message)
      case (_, Left(message)) => failure(message)
  }

  pureTest("the 20 pointwise estimates and the 20 Pareto shapes, at 1e-6") {
    (transposed, Fixtures.psisLoo) match
      case (Right((logLik, _, observations)), Right(expected)) =>
        val result = Loo.psis(logLik, 1.0)
        forEach((0 until observations).toList) { i =>
          closeRel(result.pointwiseElpd(i), expected.pointwiseElpd(i), 1e-6, s"the elpd of $i")
            .and(closeRel(result.paretoK(i), expected.paretoK(i), 1e-6, s"the Pareto shape of $i"))
        }
      case (Left(message), _) => failure(message)
      case (_, Left(message)) => failure(message)
  }

  /** The diagnostic the whole method rests on: the outlier is named and nothing else is. */
  pureTest("the outlier is the one observation whose weights cannot be trusted") {
    (transposed, Fixtures.psisLoo) match
      case (Right((logLik, draws, observations)), Right(expected)) =>
        val result = Loo.psis(logLik, 1.0)
        val threshold = Loo.kThreshold(draws)
        val flagged = result.problematic(threshold)
        check(flagged == List(observations - 1), s"the flagged observations are $flagged")
          .and(
            check(result.paretoK(observations - 1) > 0.7, "the outlier's shape is not above 0.7")
          )
          .and(
            check(
              (0 until observations - 1).forall(i => result.paretoK(i) < 0.7),
              "another observation's shape is above 0.7"
            )
          )
          .and(closeRel(threshold, 0.7, 1e-12, "the threshold at four thousand draws"))
          // At a hundred draws the threshold tightens, which is the point of the formula.
          .and(closeRel(Loo.kThreshold(100), 0.5, 1e-12, "the threshold at a hundred draws"))
      case (Left(message), _) => failure(message)
      case (_, Left(message)) => failure(message)
  }

  /** The exact log pointwise predictive density, which the fixture computes independently.
    *
    * `lpd - elpd` is the effective number of parameters, so getting the first wrong would move the
    * second, and the fixture's `lpd_pointwise` is a plain log-sum-exp with no smoothing in it: a
    * check on the arithmetic around the smoothing rather than on the smoothing itself.
    */
  pureTest("the log pointwise predictive density, against the fixture's own") {
    (transposed, Fixtures.psisLoo) match
      case (Right((logLik, draws, observations)), Right(expected)) =>
        val result = Loo.psis(logLik, 1.0)
        forEach((0 until observations).toList) { i =>
          val lpd = LogExp.logSumExp(logLik(i)) - math.log(draws.toDouble)
          val implied = result.pointwiseElpd(i) + (lpd - result.pointwiseElpd(i))
          closeRel(lpd, expected.lpdPointwise(i), 1e-12, s"the lpd of $i")
            .and(closeRel(implied, lpd, 1e-12, s"the identity lpd = elpd + p_loo at $i"))
        }
      case (Left(message), _) => failure(message)
      case (_, Left(message)) => failure(message)
  }

  pureTest(
    "a comparison puts the best first and the error of a difference is not a difference of errors"
  ) {
    val better = LooResult(
      -10.0,
      1.0,
      2.0,
      IArray.tabulate(20)(i => -0.5 - 0.01 * i),
      IArray.fill(20)(0.1)
    )
    val worse = LooResult(
      -14.0,
      1.2,
      2.5,
      IArray.tabulate(20)(i => -0.7 - 0.01 * i),
      IArray.fill(20)(0.1)
    )
    val ranked = Loo.compare(List(("worse", worse), ("better", better)))
    check(ranked.map(_.name) == List("better", "worse"), s"the order is ${ranked.map(_.name)}")
      .and(check(ranked.head.difference == 0.0, "the best model differs from itself"))
      .and(check(ranked.head.se == 0.0, "the best model has an error against itself"))
      .and(closeRel(ranked(1).difference, -4.0, 1e-12, "the difference in elpd"))
      // The two differ by exactly 0.2 at every observation, so the pointwise differences have no
      // spread at all and the error of the difference is zero — while each elpd's own error is
      // about 1. That is the whole reason the error is computed from the differences.
      .and(close(ranked(1).se, 0.0, 1e-12, "the error of a constant difference"))
      .and(check(Loo.compare(Nil).isEmpty, "an empty comparison"))
  }

  pureTest("an elpd of one observation has no standard error, and an empty one is NaN") {
    val single = Loo.psis(IArray(IArray.tabulate(400)(s => -0.5 - 0.001 * s)), 1.0)
    val none = Loo.psis(IArray.empty[IArray[Double]], 1.0)
    check(single.se == 0.0, s"one observation gave a standard error of ${single.se}")
      .and(check(single.elpd.isFinite, s"its elpd is ${single.elpd}"))
      .and(check(none.elpd.isNaN, s"no observations gave an elpd of ${none.elpd}"))
  }
