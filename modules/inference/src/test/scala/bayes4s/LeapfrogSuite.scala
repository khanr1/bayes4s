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
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** The leapfrog integrator, against the eleven cases of Stan's `expl_leapfrog_test.cpp`, and
  * against the two properties that say it is a symplectic integrator rather than merely a
  * plausible one.
  *
  * Reversibility is what makes the Metropolis correction valid: run a step forwards, flip the
  * momentum, run it again, and you must be back where you started to the last bits. The energy
  * error in `epsilon²` is what makes a step size mean something: halve it and the error must fall
  * by four, which no first-order integrator would manage.
  */
object LeapfrogSuite extends SimpleIOSuite with Checkers with Approx:

  /** The fixture's model, in this library's variables: `V = q²/2` is a standard Gaussian log
    * density of `-q²/2`, and Stan's `g = q` is a gradient of `-q`.
    */
  private val oneDimensionalGaussian: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A = -(x(0) * x(0) * 0.5)

  private val unit: Metric = Metric.unit(1)

  pureTest("the 11 cases of expl_leapfrog_test.cpp, in Stan's own variables") {
    Fixtures.leapfrogCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          // The fixture states V, q, p and g; a Point holds the log density and its gradient.
          val start = Point(IArray(c.position), -c.potential, IArray(-c.gradient))
          val applied = (1 to c.repeat).foldLeft((start, IArray(c.momentum))) {
            case ((point, p), _) =>
              c.operations.foldLeft((point, p)) {
                case ((at, momentum), "begin_update_p") =>
                  (at, Leapfrog.updateMomentum(at, momentum, 0.5 * c.epsilon))
                case ((at, momentum), "update_q") =>
                  (
                    Leapfrog.updatePosition(oneDimensionalGaussian, unit, at, momentum, c.epsilon),
                    momentum
                  )
                case ((at, momentum), "end_update_p") =>
                  (at, Leapfrog.updateMomentum(at, momentum, 0.5 * c.epsilon))
                case ((at, momentum), "evolve") =>
                  Leapfrog.evolve(oneDimensionalGaussian, unit, at, momentum, c.epsilon)
                case ((at, momentum), other) => (at, momentum)
              }
          }
          val (point, momentum) = applied
          val unknown = c.operations.filterNot(
            Set("begin_update_p", "update_q", "end_update_p", "evolve").contains
          )
          check(unknown.isEmpty, s"${c.test}: unknown operations $unknown")
            .and(close(point.potential, c.expectedPotential, c.tolerance, s"${c.test}: V"))
            .and(close(point.q(0), c.expectedPosition, c.tolerance, s"${c.test}: q"))
            .and(close(momentum(0), c.expectedMomentum, c.tolerance, s"${c.test}: p"))
            .and(close(-point.gradient(0), c.expectedGradient, c.tolerance, s"${c.test}: g"))
        }
        check(cases.length == 11, s"${cases.length} cases, expected 11").and(assertions)
  }

  /** A correlated Gaussian in three dimensions, written through its precision so that the
    * gradient is exact and the metric has something to do.
    */
  private val correlated: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      // Precision of a 3 by 3 Gaussian with unit variances and correlation 0.5 between neighbours.
      val quadratic = x(0) * x(0) * 1.5 + x(1) * x(1) * 2.0 + x(2) * x(2) * 1.5 +
        x(0) * x(1) * -1.0 + x(1) * x(2) * -1.0
      -(quadratic * 0.5)

  test("a step run forwards and then backwards comes back to where it started") {
    forall(Gen.zip(Gen.listOfN(3, Gen.choose(-2.0, 2.0)), Gen.listOfN(3, Gen.choose(-2.0, 2.0)))) {
      (q, p) =>
        val start = Point.at(correlated, IArray.from(q))
        val metric = Metric.Diagonal(IArray(1.0, 0.4, 2.5))
        val (moved, momentum) = Leapfrog.evolve(correlated, metric, start, IArray.from(p), 0.3)
        // Flipping the momentum and stepping again is the same as stepping with -epsilon.
        val flipped = IArray.tabulate(3)(i => -momentum(i))
        val (back, returned) = Leapfrog.evolve(correlated, metric, moved, flipped, 0.3)
        forEach((0 until 3).toList) { i =>
          closeRel(back.q(i), start.q(i), 1e-14, s"position $i")
            .and(closeRel(-returned(i), p(i), 1e-14, s"momentum $i"))
        }
    }
  }

  /** The energy error of a trajectory of fixed length, as the step size halves.
    *
    * A symplectic integrator of second order has an error in `epsilon²` per unit of trajectory, so
    * each halving divides it by about four. The ratios are asserted between 3 and 5, which is
    * wide enough for the error's own oscillation and far from the 2 a first-order scheme would
    * give.
    */
  pureTest("the energy error falls as epsilon squared") {
    val start = Point.at(correlated, IArray(0.7, -0.3, 1.1))
    val momentum = IArray(0.4, 1.0, -0.6)
    val metric = Metric.unit(3)
    val initial = Leapfrog.energy(metric, start, momentum)
    def errorAt(epsilon: Double): Double =
      val steps = math.round(1.6 / epsilon).toInt
      val (point, p) = (1 to steps).foldLeft((start, momentum)) { case ((at, mom), _) =>
        Leapfrog.evolve(correlated, metric, at, mom, epsilon)
      }
      math.abs(Leapfrog.energy(metric, point, p) - initial)
    val errors = List(0.2, 0.1, 0.05, 0.025).map(errorAt)
    val ratios = errors.zip(errors.tail).map((coarse, fine) => coarse / fine)
    check(errors.forall(_ > 0.0), s"an error was exactly zero: $errors")
      .and(
        forEach(ratios) { ratio =>
          check(ratio > 3.0 && ratio < 5.0, s"the error fell by $ratio, expected about 4")
        }
      )
      .and {
        // Three halvings of the step size, so the whole drop should be about 4³ = 64. Asserting
        // the span as well as the ratios is what rules out an error that happens to shrink by
        // four twice for an unrelated reason.
        val span = errors.head / errors.last
        check(span > 40.0 && span < 100.0, s"the error fell by $span over three halvings")
      }
  }

  pureTest("the energy is the potential plus the kinetic term, and a NaN is an infinity") {
    val point = Point.at(correlated, IArray(0.5, 0.5, 0.5))
    val p = IArray(1.0, -1.0, 0.5)
    val metric = Metric.Diagonal(IArray(2.0, 0.5, 1.0))
    val expected = -point.logDensity + 0.5 * (2.0 * 1.0 + 0.5 * 1.0 + 1.0 * 0.25)
    val broken = Point(IArray(0.0, 0.0, 0.0), Double.NaN, IArray(0.0, 0.0, 0.0))
    closeRel(Leapfrog.energy(metric, point, p), expected, 1e-14, "the energy")
      .and(
        check(
          Leapfrog.energy(metric, broken, p) == Double.PositiveInfinity,
          "a NaN energy must count as +Inf, which is what makes the proposal divergent"
        )
      )
  }

  /** The metric has to enter the position step, and the eleven fixture cases cannot say so: they
    * all use the unit metric, where `M^-1 p` and `p` are the same vector.
    *
    * What pins it is the equivalence a metric *is*. A step with inverse metric `diag(m)` on a
    * target is the same thing as a unit-metric step on the target rescaled by `sqrt(m)`, under
    * `q = S x` and `p = S^-1 p~`. If the metric were dropped from the position update, or applied
    * in the energy and not the step, the two would part company immediately.
    */
  test("a metric is a change of variables: the step agrees with the rescaled target") {
    val scales = IArray(2.0, 0.5, 3.0)
    val inverse = IArray.tabulate(3)(i => scales(i) * scales(i))
    val rescaled: DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        // `correlated` at `q = S x`, written through the same quadratic form.
        val q = r.vectorOf(3)(i => x(i) * scales(i).lift[A])
        val quadratic = q(0) * q(0) * 1.5 + q(1) * q(1) * 2.0 + q(2) * q(2) * 1.5 +
          q(0) * q(1) * -1.0 + q(1) * q(2) * -1.0
        -(quadratic * 0.5)
    forall(Gen.zip(Gen.listOfN(3, Gen.choose(-1.5, 1.5)), Gen.listOfN(3, Gen.choose(-1.5, 1.5)))) {
      (x, momentum) =>
        val epsilon = 0.25
        // The scaled problem, with the unit metric.
        val scaledStart = Point.at(rescaled, IArray.from(x))
        val (scaledEnd, scaledMomentum) =
          Leapfrog.evolve(rescaled, Metric.unit(3), scaledStart, IArray.from(momentum), epsilon)
        // The original problem, with the metric, at the corresponding point and momentum.
        val q = IArray.tabulate(3)(i => x(i) * scales(i))
        val p = IArray.tabulate(3)(i => momentum(i) / scales(i))
        val start = Point.at(correlated, q)
        val (end, endMomentum) =
          Leapfrog.evolve(correlated, Metric.Diagonal(inverse), start, p, epsilon)
        forEach((0 until 3).toList) { i =>
          closeRel(end.q(i), scaledEnd.q(i) * scales(i), 1e-12, s"position $i")
            .and(closeRel(endMomentum(i) * scales(i), scaledMomentum(i), 1e-12, s"momentum $i"))
        }.and(
          closeRel(
            Leapfrog.energy(Metric.Diagonal(inverse), end, endMomentum),
            Leapfrog.energy(Metric.unit(3), scaledEnd, scaledMomentum),
            1e-12,
            "the energy"
          )
        )
    }
  }

  pureTest("a dense metric and the diagonal it contains agree") {
    val diagonal = IArray(2.0, 0.5, 1.5)
    val rows = IArray.tabulate(3)(i => IArray.tabulate(3)(j => if i == j then diagonal(i) else 0.0))
    val built = Matrix.ofRows(rows).flatMap(Metric.dense)
    built match
      case Left(error) => failure(error.message)
      case Right(dense) =>
        val p = IArray(0.3, -1.2, 0.8)
        val asDiagonal = Metric.Diagonal(diagonal)
        forEach((0 until 3).toList) { i =>
          closeRel(dense.velocity(p)(i), asDiagonal.velocity(p)(i), 1e-14, s"velocity $i")
        }.and(closeRel(dense.kinetic(p), asDiagonal.kinetic(p), 1e-14, "the kinetic term"))
          .and(check(dense.dimension == 3, "the dimension"))
  }

  /** A momentum drawn from `N(0, M)` has covariance `M`, which is the **inverse** of what the
    * metric holds. Getting that upside down is a sampler that works and mixes badly, so it is
    * asserted on a correlated metric where the two differ in every entry.
    */
  pureTest("a drawn momentum has the covariance the metric implies, diagonal and dense") {
    val n = 40_000
    val rows = IArray(IArray(2.0, 0.8, 0.0), IArray(0.8, 1.0, -0.3), IArray(0.0, -0.3, 0.5))
    // Variances far from one in both directions: `sqrt(inverse)` and `1 / sqrt(inverse)` are then
    // nowhere near each other, which is the mistake this test exists to catch.
    val diagonal = Metric.Diagonal(IArray(2.0, 0.5, 4.0))
    Matrix.ofRows(rows).flatMap(Metric.dense) match
      case Left(error) => failure(error.message)
      case Right(dense) =>
        forEach(List(("diagonal", diagonal), ("dense", dense))) { (name, metric) =>
          val drawn = cats
            .Monad[Rand]
            .replicateA(n, metric.sampleMomentum[Rand])
            .runA(Seed(20261105L))
            .value
          // The covariance of p is M = (M^-1)^-1, so p' M^-1 p is a chi-squared of as many
          // degrees of freedom as there are dimensions: mean 3 and variance 6.
          val quadratic = drawn.map(p => 2.0 * metric.kinetic(p))
          val mean = quadratic.sum / n
          close(
            mean,
            3.0,
            4.0 * math.sqrt(6.0 / n),
            s"$name: the mean of p' M^-1 p is $mean, expected 3"
          )
        }
  }
