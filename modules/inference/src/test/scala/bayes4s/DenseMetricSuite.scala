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

/** The dense metric, against the diagonal one, on the posterior that makes the difference.
  *
  * A diagonal metric can rescale each coordinate and nothing else, so on a posterior correlated at
  * 0.95 it leaves the sampler to crawl along a ridge it cannot see. A dense metric learns the ridge
  * and travels along it. The claim is a ratio, so the test measures both and reports the number;
  * the figure is recorded in docs/validation.md with the machine it was taken on.
  *
  * The cost is counted in **gradients**, not transitions: a dense metric is only worth having if
  * it buys more effective draws per unit of work, and the unit of work of a Hamiltonian method is
  * one gradient of the target.
  *
  * Measured on this machine: the diagonal metric reached an effective sample size of 21 over 9 764
  * gradients, the dense one 600 over 6 472 — 0.0021 against 0.0926 effective draws per gradient, a
  * factor of **43**. The adapted step size grew from 0.16 to 0.66 at the same time. The threshold
  * below is 5, which leaves room for the seed and still fails if most of the gain is lost.
  */
object DenseMetricSuite extends SimpleIOSuite with MonteCarlo with Approx:

  private val dimension = 10
  private val rho = 0.95
  private val target = Targets.equicorrelated(dimension, rho)

  private def run(shape: MetricShape, seed: Long): Either[BayesError, Chain] =
    Mcmc
      .run[Rand](
        Nuts.kernel[Rand](target, NutsSettings()),
        target,
        IArray.fill(dimension)(0.0),
        1000,
        1000,
        AdaptationSettings(metric = shape)
      )
      .runA(Seed(seed))
      .value

  /** The effective draws of the slowest direction, per gradient of the target.
    *
    * The slowest direction of an equicorrelated Gaussian is the average of the coordinates, whose
    * variance is `(1 + (n-1) rho) / n`; every other direction mixes faster, so this is the number
    * a user waits for.
    */
  private def efficiency(chain: Chain): (Double, Double, Int) =
    val average = IArray.from(
      chain.draws.map(q => (0 until dimension).foldLeft(0.0)((sum, i) => sum + q(i)) / dimension)
    )
    val gradients = chain.transitions.map(_.leapfrogSteps).sum
    val ess = Ess.of(List(average))
    (ess, ess / gradients, gradients)

  pureTest("a dense metric buys more effective draws per gradient than a diagonal one") {
    val diagonal = run(MetricShape.Diagonal, 20261105L)
    val dense = run(MetricShape.Dense, 20261105L)
    (diagonal, dense) match
      case (Right(withDiagonal), Right(withDense)) =>
        val (diagonalEss, diagonalRate, diagonalGradients) = efficiency(withDiagonal)
        val (denseEss, denseRate, denseGradients) = efficiency(withDense)
        val ratio = denseRate / diagonalRate
        // Both must be sampling the right thing before their speed means anything.
        val checks = forEach(List(("diagonal", withDiagonal), ("dense", withDense))) {
          (name, chain) =>
            val average = IArray.from(
              chain.draws.map(q =>
                (0 until dimension).foldLeft(0.0)((s, i) => s + q(i)) / dimension
              )
            )
            val values = (0 until average.length).map(average.apply).toList
            val mean = values.sum / values.length
            withinStandardErrors(
              mean,
              0.0,
              Ess.standardError(List(average)),
              4.0,
              s"$name: the mean of the slowest direction"
            )
        }
        checks.and(
          check(
            ratio > 5.0,
            s"the dense metric gave $denseRate effective draws per gradient " +
              f"(ESS $denseEss%.0f over $denseGradients gradients) against $diagonalRate " +
              f"for the diagonal one (ESS $diagonalEss%.0f over $diagonalGradients): " +
              f"a ratio of $ratio%.2f, which is not the clear gain the specification asks for"
          )
        )
      case (Left(error), _) => failure(s"diagonal: ${error.message}")
      case (_, Left(error)) => failure(s"dense: ${error.message}")
  }

  pureTest("the adapted dense metric is close to the covariance it should have found") {
    run(MetricShape.Dense, 4711L) match
      case Left(error) => failure(error.message)
      case Right(chain) =>
        chain.tuning.metric match
          case Metric.Diagonal(_) => failure("a dense metric became diagonal")
          case Metric.Dense(inverse, _) =>
            val diagonal = forEach((0 until dimension).toList) { i =>
              val ratio = inverse(i, i)
              check(
                ratio > 0.5 && ratio < 2.0,
                s"the estimated variance of coordinate $i is $ratio, expected near 1"
              )
            }
            val pairs = for
              i <- (0 until dimension).toList
              j <- i + 1 until dimension
            yield (i, j)
            val offDiagonal = forEach(pairs) { (i, j) =>
              check(
                inverse(i, j) > 0.6 && inverse(i, j) < 1.2,
                s"the estimated correlation of $i and $j is ${inverse(i, j)}, expected near $rho"
              )
            }
            diagonal.and(offDiagonal)
  }
