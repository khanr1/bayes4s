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
import bayes4s.testkit.Fixtures
import weaver.SimpleIOSuite

/** The warmup's three machines, against Stan's own tests and against the port of its code.
  *
  * None of the three can be seen to be wrong from a finished fit. A window schedule that is one
  * iteration out still produces samples; a dual averaging that reports `exp(x)` instead of
  * `exp(xBar)` still converges; a variance regulariser without its ridge still works until a
  * coordinate stops moving. So all three are asserted as values, against the schedules and
  * trajectories the fixture carries.
  */
object AdaptationSuite extends SimpleIOSuite with Approx:

  private val settings = AdaptationSettings()

  pureTest("the 9 window schedules of windowed_adaptation.hpp") {
    Fixtures.windowCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val schedule = WindowSchedule.of(settings, c.warmup)
          val buffers =
            if !c.adapt then success
            else
              check(
                schedule.initBuffer == c.initBuffer && schedule.termBuffer == c.termBuffer &&
                  schedule.baseWindow == c.baseWindow,
                s"warmup ${c.warmup}: buffers ${schedule.initBuffer}/${schedule.baseWindow}/" +
                  s"${schedule.termBuffer}, expected ${c.initBuffer}/${c.baseWindow}/${c.termBuffer}"
              )
          check(schedule.adapt == c.adapt, s"warmup ${c.warmup}: adapt is ${schedule.adapt}")
            .and(buffers)
            .and(
              check(
                schedule.ends == c.ends,
                s"warmup ${c.warmup}: the metric is re-estimated at ${schedule.ends}, " +
                  s"expected ${c.ends}"
              )
            )
        }
        check(cases.length == 9, s"${cases.length} schedules, expected 9").and(assertions)
  }

  pureTest("below twenty iterations the metric is not adapted at all") {
    val schedule = WindowSchedule.of(settings, 19)
    check(!schedule.adapt, "19 iterations should not adapt the metric")
      .and(check(schedule.ends.isEmpty, s"and should end no window, not ${schedule.ends}"))
      .and(check(!schedule.inWindow(0) && !schedule.endsWindow(0), "no iteration is in a window"))
  }

  pureTest("the step size Stan's own test asserts") {
    Fixtures.dualAveragingStanCase match
      case Left(message) => failure(message)
      case Right((mu, delta, acceptStat, expected)) =>
        // mu = 0 means an initial step size of 1, and an acceptance statistic equal to the target
        // must leave it exactly there: the first thing a dual averaging has to get right.
        val start = DualAveraging(
          settings.copy(targetAccept = delta),
          mu,
          0,
          0.0,
          0.0,
          0.0
        )
        val learnt = start.learn(acceptStat)
        closeRel(learnt.stepSize, expected, 1e-14, "the step size after one equal statistic")
  }

  pureTest("the dual-averaging trajectory, step by step, against the port") {
    Fixtures.dualAveragingCase match
      case Left(message) => failure(message)
      case Right(c) =>
        val tuned = AdaptationSettings(
          targetAccept = c.delta,
          gamma = c.gamma,
          t0 = c.t0,
          kappa = c.kappa
        )
        // The port sets mu = log(10 * 0.5), so the step size it starts from is 0.5.
        val start = DualAveraging.of(tuned, 0.5)
        val trajectory = (0 until c.acceptStats.length)
          .foldLeft((start, Vector.empty[Double])) { case ((state, collected), i) =>
            val next = state.learn(c.acceptStats(i))
            (next, collected :+ next.stepSize)
          }
        val (finished, steps) = trajectory
        check(
          math.abs(start.mu - c.mu) < 1e-15,
          s"mu is ${start.mu}, expected ${c.mu}"
        )
          .and(forEach((0 until c.stepSizes.length).toList) { i =>
            closeRel(steps(i), c.stepSizes(i), 1e-12, s"the step size after ${i + 1} statistics")
          })
          .and(
            closeRel(
              finished.completed,
              c.finalStepSize,
              1e-12,
              "the completed step size, exp of the averaged iterate"
            )
          )
          .and(
            check(
              math.abs(finished.completed - steps.last) > 0.3,
              "the averaged iterate and the last one are nearly equal here, so the test " +
                "would not notice the difference"
            )
          )
  }

  pureTest("a statistic above one counts as one, and a NaN as a rejection") {
    val start = DualAveraging.of(settings, 1.0)
    check(
      start.learn(1.2).stepSize == start.learn(1.0).stepSize,
      "a statistic of 1.2 must behave as 1"
    )
      .and(
        check(
          start.learn(Double.NaN).stepSize < start.learn(0.5).stepSize,
          "a NaN statistic must pull the step size down at least as hard as a bad one"
        )
      )
  }

  pureTest("the variance regulariser, on the case var_adaptation_test.cpp asserts") {
    Fixtures.varianceRegularization match
      case Left(message) => failure(message)
      case Right(expected) =>
        // Ten draws, all zero, in ten dimensions: the sample variance is zero and what is left is
        // the ridge. Without the ridge this would be zero and the step size infinite.
        val accumulated = (1 to 10).foldLeft(DiagonalMoments.empty(10)) { (moments, _) =>
          moments.add(IArray.fill(10)(0.0))
        }
        val variances = accumulated.regularised
        check(accumulated.count == 10, s"${accumulated.count} draws accumulated")
          .and(forEach((0 until 10).toList) { i =>
            closeRel(variances(i), expected, 1e-14, s"variance $i")
          })
  }

  pureTest("the accumulators recover a known variance and covariance") {
    val n = 2000
    val draws = (0 until n).toList.map { i =>
      val a = math.sin(i * 0.7)
      val b = math.cos(i * 0.31)
      IArray(2.0 * a, 0.5 * a + 0.5 * b)
    }
    val diagonal = draws.foldLeft(DiagonalMoments.empty(2))(_.add(_))
    val dense = draws.foldLeft(DenseMoments.empty(2))(_.add(_))
    def sampleVariance(xs: List[Double]): Double =
      val mean = xs.sum / xs.length
      xs.map(x => (x - mean) * (x - mean)).sum / (xs.length - 1)
    def sampleCovariance(xs: List[Double], ys: List[Double]): Double =
      val mx = xs.sum / xs.length
      val my = ys.sum / ys.length
      xs.zip(ys).map((x, y) => (x - mx) * (y - my)).sum / (xs.length - 1)
    val first = draws.map(_(0))
    val second = draws.map(_(1))
    val scale = n.toDouble / (n + 5.0)
    val ridge = 1e-3 * 5.0 / (n + 5.0)
    val variances = diagonal.regularised
    dense.regularised match
      case Left(error) => failure(error.message)
      case Right(covariance) =>
        closeRel(
          variances(0),
          scale * sampleVariance(first) + ridge,
          1e-12,
          "the first variance"
        )
          .and(
            closeRel(variances(1), scale * sampleVariance(second) + ridge, 1e-12, "the second")
          )
          .and(
            closeRel(
              covariance(0, 0),
              scale * sampleVariance(first) + ridge,
              1e-12,
              "the dense diagonal"
            )
          )
          .and(
            closeRel(
              covariance(0, 1),
              scale * sampleCovariance(first, second),
              1e-10,
              "the dense off-diagonal"
            )
          )
          .and(
            check(
              covariance(0, 1) == covariance(1, 0),
              "the estimated covariance is not symmetric, so Cholesky would refuse it"
            )
          )
          .and(check(Metric.dense(covariance).isRight, "the estimate is not positive definite"))
  }

  /** A standard Gaussian and a badly scaled one: the heuristic has to find very different step
    * sizes, and in both cases one that leaves the energy error near `log(0.8)`.
    */
  pureTest("the initial step size heuristic lands where Stan's does") {
    val standard: DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A = -(Real[A].dot(x, x) * 0.5)
    val narrow: DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A = -(Real[A].dot(x, x) * 0.5 * 10000.0)
    def found(target: DiffFn, dimension: Int, seed: Long): Double =
      Adaptation
        .initialStepSize[Rand](
          target,
          Point.at(target, IArray.fill(dimension)(0.5)),
          Metric.unit(dimension)
        )
        .runA(Seed(seed))
        .value
    val wide = found(standard, 3, 11L)
    val tight = found(narrow, 3, 12L)
    check(wide > 0.25 && wide < 4.0, s"a standard Gaussian got a step size of $wide")
      .and(check(tight < 0.05, s"a Gaussian 100 times narrower got $tight, which is too large"))
      .and(check(tight > 1e-4, s"and $tight is smaller than it needs to be"))
      .and(
        check(
          wide / tight > 20.0,
          s"the two scales got step sizes $wide and $tight, which are too close to tell apart"
        )
      )
  }

  /** The state machine as a whole: a thousand warmup iterations, the metric re-estimated five
    * times, and the step size still the averaged one at the end.
    */
  pureTest("the windowed adaptation re-estimates the metric exactly where the schedule says") {
    val dimension = 3
    val initial = Tuning(0.5, Metric.unit(dimension))
    val transitions = (0 until 1000).toList.map { i =>
      val q = IArray.tabulate(dimension)(d => math.sin(i * 0.37 + d) * (d + 1.0))
      Transition(Point(q, -0.5, q), 0.8, 0.5, 2, 3, false, 1.0)
    }
    val walked = transitions.foldLeft[Either[BayesError, (Adaptation, List[Int])]](
      Right((Adaptation.windowed(settings, 1000, initial), Nil))
    ) { (state, t) =>
      state.flatMap { (adaptation, changes) =>
        adaptation.update(t).map { next =>
          val at = 1000 - transitions.length + transitions.indexOf(t)
          (next, if next.metricChanged then changes :+ at else changes)
        }
      }
    }
    walked match
      case Left(error) => failure(error.message)
      case Right((finished, changes)) =>
        val variances = finished.tuning.metric match
          case Metric.Diagonal(inverse) => inverse.toList
          case Metric.Dense(_, _) => Nil
        check(changes == List(99, 149, 249, 449, 949), s"the metric changed at $changes")
          .and(check(variances.length == dimension, "the metric lost its dimension"))
          .and(
            check(
              variances.forall(v => v > 0.0 && v.isFinite),
              s"a variance is not positive and finite: $variances"
            )
          )
          .and(
            check(
              finished.completed.stepSize != finished.tuning.stepSize,
              "the completed step size is the last one, so the averaging did nothing"
            )
          )
  }
