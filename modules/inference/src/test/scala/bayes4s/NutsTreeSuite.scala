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

/** `build_tree` and the U-turn criterion, against the case Stan's own `unit_e_nuts_test.cpp`
  * asserts.
  *
  * This is the test that says the translation of `base_nuts.hpp` is faithful. Stan's version
  * writes its results into references the caller passes in; this one returns them, and the two
  * have to come to the same numbers: the summed momenta, the velocities at both ends of the
  * subtree, the number of leapfrog steps, the log of the summed weights and the summed Metropolis
  * probability. Five of those would be unaffected by a wrong criterion and three by a wrong
  * weight, which is why all of them are asserted.
  *
  * Two sets of expectations, at their own precision: Stan's assertions are `EXPECT_FLOAT_EQ`, so
  * they carry six digits, and the fixture also ships what an independent NumPy port of the same
  * code computed, which carries sixteen.
  */
object NutsTreeSuite extends SimpleIOSuite with Approx:

  /** `gauss3D` of Stan's test: three independent standard Gaussians, `V = x.x / 2`. */
  private val gauss3D: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      -(Real[A].dot(x, x) * 0.5)

  pureTest("the generalised U-turn criterion, on the two cases Stan asserts") {
    Fixtures.criterionCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val got = Nuts.criterion(c.sharpStart, c.sharpEnd, c.rho)
          check(
            got == c.expected,
            s"criterion(${c.sharpStart.toList}, ${c.sharpEnd.toList}, ${c.rho.toList}) = $got"
          )
        }
        check(cases.length == 2, s"${cases.length} cases, expected 2").and(assertions)
  }

  /** The two cases the fixture carries both have a symmetric criterion, so neither can tell
    * whether the implementation checks one end or both.
    *
    * These are the asymmetric cases, read off the definition: the trajectory must still be
    * separating at **both** ends, so a positive product at one end and a non-positive one at the
    * other is a turn. A criterion that looked at one end only would accept them.
    */
  pureTest("the criterion needs both ends, which the fixture's own cases cannot show") {
    val asymmetric = List(
      ("the start has turned", IArray(-1.0), IArray(1.0), IArray(2.0), false),
      ("the end has turned", IArray(1.0), IArray(-1.0), IArray(2.0), false),
      ("both still separate", IArray(1.0), IArray(1.0), IArray(2.0), true),
      ("both have turned", IArray(-1.0), IArray(-1.0), IArray(2.0), false),
      // In more than one dimension it is the projections that matter, not the signs.
      ("separating in two dimensions", IArray(1.0, -1.0), IArray(0.5, 0.5), IArray(2.0, 1.0), true),
      (
        "the start's projection is negative",
        IArray(-1.0, 0.1),
        IArray(0.5, 0.5),
        IArray(2.0, 1.0),
        false
      )
    )
    forEach(asymmetric) { (name, start, end, rho, expected) =>
      val got = Nuts.criterion(start, end, rho)
      check(got == expected, s"$name: criterion gave $got, expected $expected")
    }
  }

  /** A trajectory that moves towards the mode gains probability, and Stan adds **one** for such a
    * step rather than an acceptance probability above one.
    *
    * The fixture's own case starts at an energy well above `H0`, so every step of it contributes
    * less than one and the cap is never reached. This starts far out instead, where the first
    * steps all gain, and asserts what the cap is for: an acceptance statistic that cannot exceed
    * one, which is what the dual averaging is handed.
    */
  pureTest("the Metropolis sum is capped at one per step, which the fixture cannot show") {
    val start = Phase(Point.at(gauss3D, IArray(6.0, 6.0, 6.0)), IArray(-1.0, -1.0, -1.0))
    val metric = Metric.unit(3)
    // An initial energy far above the trajectory's: every step of it gains.
    val inflated = Leapfrog.energy(metric, start.point, start.momentum)
    val subtree = Nuts
      .buildTree[Rand](gauss3D, NutsSettings(), 3, start, 0.2, inflated, metric)
      .runA(Seed(5L))
      .value
    check(
      subtree.sumMetropolisProbability <= subtree.leapfrogSteps.toDouble,
      s"the summed Metropolis probability is ${subtree.sumMetropolisProbability} over " +
        s"${subtree.leapfrogSteps} steps, which is more than one per step"
    )
      .and(
        check(
          subtree.sumMetropolisProbability > 0.5 * subtree.leapfrogSteps,
          s"only ${subtree.sumMetropolisProbability} of ${subtree.leapfrogSteps} steps gained " +
            "probability, so the cap is not being reached and the test says nothing"
        )
      )
  }

  pureTest("build_tree at depth 3 on gauss3D, against Stan and against the port") {
    Fixtures.buildTreeCase match
      case Left(message) => failure(message)
      case Right(c) =>
        val start = Phase(Point.at(gauss3D, c.position), c.momentum)
        val metric = Metric.unit(c.position.length)
        val settings = NutsSettings()
        // The proposal is chosen with the generator, which Stan's test does not assert; any seed
        // gives the same subtree summary, and the suite below checks that it does.
        val subtree = Nuts
          .buildTree[Rand](
            gauss3D,
            settings,
            c.depth,
            start,
            c.sign * c.stepSize,
            c.initialEnergy,
            metric
          )
          .runA(Seed(20261105L))
          .value
        // Stan's `rho` is the caller's accumulator, which starts at the initial momentum; the
        // subtree's own `rho` is the sum over the states it visited.
        val rho = IArray.tabulate(c.position.length)(i => c.momentum(i) + subtree.rho(i))

        def vectors(
            name: String,
            got: IArray[Double],
            expected: IArray[Double],
            tolerance: Double
        ) =
          forEach((0 until expected.length).toList) { i =>
            closeRel(got(i), expected(i), tolerance, s"$name($i)")
          }

        def against(e: bayes4s.testkit.TreeExpectation, tolerance: Double, who: String) =
          check(subtree.valid == e.validSubtree, s"$who: valid_subtree is ${subtree.valid}")
            .and(vectors(s"$who rho", rho, e.rho, tolerance))
            .and(vectors(s"$who p_begin", subtree.momentumBegin, e.momentumBegin, tolerance))
            .and(vectors(s"$who p_sharp_begin", subtree.sharpBegin, e.sharpBegin, tolerance))
            .and(vectors(s"$who p_end", subtree.momentumEnd, e.momentumEnd, tolerance))
            .and(vectors(s"$who p_sharp_end", subtree.sharpEnd, e.sharpEnd, tolerance))
            .and(vectors(s"$who final_q", subtree.end.point.q, e.finalPosition, tolerance))
            .and(vectors(s"$who final_p", subtree.end.momentum, e.finalMomentum, tolerance))
            .and(
              check(
                subtree.leapfrogSteps == e.leapfrogSteps,
                s"$who: ${subtree.leapfrogSteps} leapfrog steps, expected ${e.leapfrogSteps}"
              )
            )
            .and(closeRel(subtree.logSumWeight, e.logSumWeight, tolerance, s"$who log_sum_weight"))
            .and(
              closeRel(
                subtree.sumMetropolisProbability,
                e.sumMetropolisProbability,
                tolerance,
                s"$who sum_metro_prob"
              )
            )

        check(c.depth == 3 && c.sign == 1.0, "the fixture's setup changed")
          .and(against(c.stan, 1e-6, "Stan"))
          .and(against(c.port, 1e-12, "the port"))
  }

  pureTest("the subtree summary does not depend on the generator, only the proposal does") {
    Fixtures.buildTreeCase match
      case Left(message) => failure(message)
      case Right(c) =>
        val start = Phase(Point.at(gauss3D, c.position), c.momentum)
        val metric = Metric.unit(c.position.length)
        def run(seed: Long) =
          Nuts
            .buildTree[Rand](
              gauss3D,
              NutsSettings(),
              c.depth,
              start,
              c.sign * c.stepSize,
              c.initialEnergy,
              metric
            )
            .runA(Seed(seed))
            .value
        val one = run(1L)
        val other = run(99L)
        check(one.logSumWeight == other.logSumWeight, "the weight depends on the seed")
          .and(
            check(one.leapfrogSteps == other.leapfrogSteps, "the step count depends on the seed")
          )
          .and(check(one.rho.toList == other.rho.toList, "rho depends on the seed"))
          .and(
            check(
              one.end.point.q.toList == other.end.point.q.toList,
              "the end of the trajectory depends on the seed"
            )
          )
          .and(
            check(
              one.sumMetropolisProbability == other.sumMetropolisProbability,
              "the Metropolis sum depends on the seed"
            )
          )
  }

  /** A trajectory that leaves the typical set must be reported as divergent rather than accepted.
    *
    * With a step size of 5 on a standard Gaussian the integrator overshoots immediately, the
    * energy error runs past the threshold, and the subtree is invalid.
    */
  pureTest("a subtree whose energy error passes the threshold is divergent and invalid") {
    val start = Phase(Point.at(gauss3D, IArray(0.0, 0.0, 0.0)), IArray(60.0, 0.0, 0.0))
    val subtree = Nuts
      .buildTree[Rand](
        gauss3D,
        NutsSettings(),
        3,
        start,
        5.0,
        Leapfrog.energy(Metric.unit(3), start.point, start.momentum),
        Metric.unit(3)
      )
      .runA(Seed(7L))
      .value
    check(subtree.divergent, "the energy error did not count as a divergence")
      .and(check(!subtree.valid, "a divergent subtree must be invalid"))
  }
