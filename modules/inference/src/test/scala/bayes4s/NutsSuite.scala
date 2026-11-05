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
import cats.Monad
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The sampler on targets whose answers are known, and on one whose answer is that it is hard.
  *
  * Every threshold here is a Monte Carlo standard error computed from the chains themselves,
  * through the effective sample size: a chain of a thousand correlated draws does not carry a
  * thousand draws' worth of information, and a threshold built on the thousand would fail a
  * correct sampler. Four standard errors is the budget, stated in each message.
  */
object NutsSuite extends SimpleIOSuite with MonteCarlo with Approx:

  /** Four chains of the same target, each from its own derived seed, as `runNuts` does. */
  private def chains(
      target: DiffFn,
      dimension: Int,
      howMany: Int = 4,
      warmup: Int = 1000,
      samples: Int = 1000,
      nuts: NutsSettings = NutsSettings(),
      seed: Long = 20261105L
  ): Either[BayesError, List[Chain]] =
    (0 until howMany).toList
      .traverse { c =>
        val program = for
          start <- Monad[Rand]
            .replicateA(dimension, Rng[Rand].uniform)
            .map(us => IArray.from(us.map(u => 2.0 * (2.0 * u - 1.0))))
          chain <- Mcmc.run[Rand](
            Nuts.kernel[Rand](target, nuts),
            target,
            start,
            warmup,
            samples,
            AdaptationSettings()
          )
        yield chain
        program.runA(Seed(seed).fold(c.toLong)).value
      }
      .map(_.toList)

  private def coordinate(cs: List[Chain], i: Int): List[IArray[Double]] =
    cs.map(c => IArray.from(c.draws.map(_(i))))

  private def pooled(cs: List[Chain], i: Int): List[Double] =
    cs.flatMap(_.draws.map(_(i)))

  pureTest("a standard Gaussian in five dimensions: means, variances and quantiles") {
    chains(Targets.standardGaussian, 5) match
      case Left(error) => failure(error.message)
      case Right(cs) =>
        val assertions = forEach((0 until 5).toList) { i =>
          val byChain = coordinate(cs, i)
          val values = pooled(cs, i)
          val mean = values.sum / values.length
          val variance =
            values.map(x => (x - mean) * (x - mean)).sum / (values.length - 1)
          val ess = Ess.of(byChain)
          val meanError = Ess.standardError(byChain)
          // The standard error of a variance is sd² sqrt(2 / ESS) for a Gaussian.
          val varianceError = math.sqrt(2.0 / ess)
          val sorted = IArray.from(values.sorted)
          val lower = Quantile.quantileOfSorted(sorted, 0.05)
          val upper = Quantile.quantileOfSorted(sorted, 0.95)
          // The standard error of a quantile is sqrt(p(1-p)/ESS) / density at that quantile.
          val quantileError = math.sqrt(0.05 * 0.95 / ess) / 0.1031
          withinStandardErrors(mean, 0.0, meanError, 4.0, s"the mean of coordinate $i")
            .and(
              withinStandardErrors(variance, 1.0, varianceError, 4.0, s"the variance of $i")
            )
            .and(
              withinStandardErrors(lower, -1.6448536269514729, quantileError, 4.0, s"the 5% of $i")
            )
            .and(
              withinStandardErrors(upper, 1.6448536269514729, quantileError, 4.0, s"the 95% of $i")
            )
            .and(check(ess > 400.0, s"coordinate $i has an effective sample size of only $ess"))
        }
        check(cs.length == 4, s"${cs.length} chains").and(assertions)
  }

  pureTest("a Gaussian correlated at 0.8 in ten dimensions: means and variances") {
    val rho = 0.8
    chains(Targets.equicorrelated(10, rho), 10) match
      case Left(error) => failure(error.message)
      case Right(cs) =>
        forEach((0 until 10).toList) { i =>
          val byChain = coordinate(cs, i)
          val values = pooled(cs, i)
          val mean = values.sum / values.length
          val variance = values.map(x => (x - mean) * (x - mean)).sum / (values.length - 1)
          val ess = Ess.of(byChain)
          withinStandardErrors(
            mean,
            0.0,
            Ess.standardError(byChain),
            4.0,
            s"the mean of coordinate $i"
          ).and(
            withinStandardErrors(
              variance,
              1.0,
              math.sqrt(2.0 / ess),
              4.0,
              s"the variance of coordinate $i"
            )
          )
        }
  }

  pureTest("the correlations of a correlated Gaussian come out right") {
    val rho = 0.8
    chains(Targets.equicorrelated(6, rho), 6) match
      case Left(error) => failure(error.message)
      case Right(cs) =>
        val draws = cs.flatMap(_.draws)
        val n = draws.length
        def mean(i: Int) = draws.map(_(i)).sum / n
        val pairs = for
          i <- (0 until 6).toList
          j <- i + 1 until 6
        yield (i, j)
        forEach(pairs) { (i, j) =>
          val mi = mean(i)
          val mj = mean(j)
          val covariance = draws.map(q => (q(i) - mi) * (q(j) - mj)).sum / n
          val ess = Ess.of(coordinate(cs, i))
          // The standard error of a correlation is about (1 - rho²) / sqrt(ESS).
          val error = (1.0 - rho * rho) / math.sqrt(ess)
          withinStandardErrors(covariance, rho, error, 4.0, s"the correlation of $i and $j")
        }
  }

  /** What the adapted step size realises, and why it is not the target itself.
    *
    * The dual averaging drives the acceptance **of its own trajectory** to 0.8, and the last fifty
    * warmup iterations here average 0.77 to 0.79, which is the statistic it controls. The step size
    * it leaves behind, `exp(xBar)` averaged over the fifty iterations that follow the last restart,
    * is conservative: sampling with it realises 0.84 to 0.93 on this target, above the target and
    * not at it. That is Stan's behaviour — `accept_stat__` after warmup routinely sits above
    * `adapt_delta` — and docs/validation.md records the measurement.
    *
    * So the target is pinned from both sides instead of asserted: the adapted step size accepts
    * above 0.8, and twice that step size accepts below it. A step size that was far too small
    * would fail the second half, and one that was too large the first.
    */
  pureTest("the adapted step size brackets the target: above at one, below at two") {
    val target = Targets.equicorrelated(8, 0.5)
    chains(target, 8) match
      case Left(error) => failure(error.message)
      case Right(cs) =>
        forEach(cs.zipWithIndex) { (chain, c) =>
          val stats = chain.transitions.map(_.acceptStat)
          val realised = stats.sum / stats.length
          val doubled = Tuning(2.0 * chain.tuning.stepSize, chain.tuning.metric)
          val at = Point.at(target, chain.draws.last)
          val coarser = Monad[Rand]
            .replicateA(200, Nuts.kernel[Rand](target, NutsSettings()).transition(at, doubled))
            .runA(Seed(911L).fold(c.toLong))
            .value
          val coarse = coarser.map(_.acceptStat).sum / coarser.length
          check(
            realised >= 0.8 && realised <= 0.99,
            s"chain $c accepted $realised of the time, expected between the target and one"
          ).and(
            check(
              coarse < 0.8,
              s"chain $c with twice the step size accepted $coarse, which is not below the target"
            )
          )
        }
  }

  pureTest("a standard Gaussian needs shallow trees, and reports what it used") {
    chains(Targets.standardGaussian, 3, howMany = 1) match
      case Left(error) => failure(error.message)
      case Right(cs) =>
        val chain = cs.head
        val depths = chain.transitions.map(_.treeDepth)
        val meanDepth = depths.sum.toDouble / depths.length
        val steps = chain.transitions.map(_.leapfrogSteps)
        check(
          meanDepth > 1.0 && meanDepth < 4.0,
          s"the mean tree depth is $meanDepth; an easy target should need 2 or 3 doublings"
        )
          .and(check(depths.forall(_ <= 10), s"a depth of ${depths.max} passed the maximum of 10"))
          .and(
            check(
              steps.forall(s => s >= 1 && s <= 1024),
              s"a trajectory used ${steps.max} leapfrog steps"
            )
          )
          .and(check(chain.transitions.forall(!_.divergent), "an easy target diverged"))
          .and(
            check(
              chain.transitions.forall(t => t.energy.isFinite && t.stepSize > 0.0),
              "a transition reported no energy or no step size"
            )
          )
  }

  /** With a step size far below what the target needs, the trajectory never turns round and the
    * depth saturates: the one case where hitting the maximum is the right answer.
    */
  pureTest("a minuscule step size saturates the tree depth") {
    val target = Targets.standardGaussian
    val tiny = Tuning(1e-4, Metric.unit(2))
    val kernel = Nuts.kernel[Rand](target, NutsSettings(maxTreeDepth = 6))
    val start = Point.at(target, IArray(0.3, -0.2))
    val transitions = Monad[Rand]
      .replicateA(20, kernel.transition(start, tiny))
      .runA(Seed(31L))
      .value
    val depths = transitions.map(_.treeDepth)
    check(depths.forall(_ == 6), s"the depths were $depths, expected all 6")
      .and(check(transitions.forall(_.leapfrogSteps == 63), "a saturated tree took 2^6 - 1 steps"))
      .and(check(transitions.forall(!_.divergent), "a tiny step size must not diverge"))
  }

  /** The funnel, centred: no single step size fits it, and the sampler has to say so. */
  pureTest("the funnel is reported as divergent, and a Gaussian is not") {
    val difficult = chains(Targets.funnel(10), 10, howMany = 2, warmup = 400, samples = 600)
    val easy = chains(Targets.standardGaussian, 10, howMany = 2, warmup = 400, samples = 600)
    (difficult, easy) match
      case (Right(hard), Right(simple)) =>
        val divergences = hard.map(_.transitions.count(_.divergent)).sum
        val none = simple.map(_.transitions.count(_.divergent)).sum
        check(
          divergences > 0,
          "the centred funnel produced no divergent transition, which means they are never reported"
        ).and(
          check(none == 0, s"a standard Gaussian produced $none divergent transitions")
        )
      case (Left(error), _) => failure(s"the funnel: ${error.message}")
      case (_, Left(error)) => failure(s"the Gaussian: ${error.message}")
  }

  /** The metric has to find scales spanning four orders of magnitude; without it the sampler
    * cannot move in the narrow coordinates at all.
    */
  pureTest("the adapted metric is close to the variances it should have found") {
    val scales = IArray(0.01, 0.1, 1.0, 10.0, 100.0)
    chains(Targets.badlyScaled(scales), 5, howMany = 2, warmup = 1000, samples = 500) match
      case Left(error) => failure(error.message)
      case Right(cs) =>
        forEach(cs.zipWithIndex) { (chain, c) =>
          chain.tuning.metric match
            case Metric.Dense(_, _) => failure("the default metric should be diagonal")
            case Metric.Diagonal(inverse) =>
              forEach((0 until 5).toList) { i =>
                val truth = scales(i) * scales(i)
                val ratio = inverse(i) / truth
                check(
                  ratio > 0.4 && ratio < 2.5,
                  s"chain $c estimated the variance of coordinate $i as ${inverse(i)}, " +
                    s"${ratio} times the true $truth"
                )
              }
        }
  }

  /** Section 2.4's obligatory test: four chains computed in parallel are the four computed in
    * sequence, bit for bit.
    *
    * It holds because a chain is a pure function of its seed, and the thing it rules out is a
    * sampler that shares anything between chains — a tape, an accumulator, a cached gradient. The
    * tape is the one piece of mutation in the library, and this is where it would show.
    */
  test("four chains computed in parallel are the four computed in sequence, bit for bit") {
    val target = Targets.equicorrelated(5, 0.6)
    val seed = Seed(20261105L)
    def chain(c: Int): Chain =
      val program = for
        start <- Monad[Rand]
          .replicateA(5, Rng[Rand].uniform)
          .map(us => IArray.from(us.map(u => 2.0 * (2.0 * u - 1.0))))
        run <- Mcmc.run[Rand](
          Nuts.kernel[Rand](target, NutsSettings()),
          target,
          start,
          200,
          200,
          AdaptationSettings()
        )
      yield run
      program.runA(seed.fold(c.toLong)).value.toOption.get
    val sequential = (0 until 4).toList.map(chain)
    (0 until 4).toList
      .parTraverse(c => cats.effect.IO(chain(c)))
      .map { parallel =>
        forEach(sequential.zip(parallel).zipWithIndex) { (pair, c) =>
          val (one, other) = pair
          check(
            one.draws.map(_.toList) == other.draws.map(_.toList),
            s"chain $c differs between the parallel and the sequential run"
          ).and(
            check(
              one.tuning.stepSize == other.tuning.stepSize,
              s"chain $c adapted to a different step size"
            )
          )
        }
      }
  }

  /** A transition is a function: same arguments, same answer, and the arrays it was handed are
    * the arrays it leaves behind.
    */
  pureTest("a transition is a function of its arguments and leaves them alone") {
    val target = Targets.standardGaussian
    val point = Point.at(target, IArray(0.3, -0.7, 1.1, 0.0))
    val before = point.q.toList
    val tuning = Tuning(0.4, Metric.Diagonal(IArray(1.0, 0.5, 2.0, 1.0)))
    val kernel = Nuts.kernel[Rand](target, NutsSettings())
    val one = kernel.transition(point, tuning).runA(Seed(42L)).value
    val other = kernel.transition(point, tuning).runA(Seed(42L)).value
    check(one.point.q.toList == other.point.q.toList, "two calls gave different points")
      .and(check(one.energy == other.energy, "two calls gave different energies"))
      .and(check(one.leapfrogSteps == other.leapfrogSteps, "two calls took different trajectories"))
      .and(check(point.q.toList == before, "the call modified the position it was given"))
  }

  pureTest("the same seed gives the same chain, and a different seed a different one") {
    val target = Targets.standardGaussian
    def once(seed: Long) = chains(target, 3, howMany = 1, warmup = 100, samples = 50, seed = seed)
    (once(5L), once(5L), once(6L)) match
      case (Right(a), Right(b), Right(c)) =>
        val first = a.head.draws.map(_.toList).toList
        val again = b.head.draws.map(_.toList).toList
        val other = c.head.draws.map(_.toList).toList
        check(first == again, "the same seed gave two different chains")
          .and(check(first != other, "two seeds gave the same chain"))
          .and(check(a.head.tuning.stepSize == b.head.tuning.stepSize, "the tuning differs"))
      case _ => failure("a chain failed")
  }
