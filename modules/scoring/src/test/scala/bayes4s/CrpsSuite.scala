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
import bayes4s.testkit.MonteCarlo
import cats.Monad
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** The continuous ranked probability score: how far a whole predictive distribution is from the
  * one number that happened.
  *
  * Two forms of the same thing. The **energy form** is the definition —
  * `E|X - y| - 0.5 E|X - X'|`, with the `1/n²` estimator properscoring uses — and costs `O(n²)`.
  * The **sorted form** is `O(n log n)` and is what anything with a thousand samples per period has
  * to use. They must agree exactly, not approximately, and the phase's own criterion says so.
  *
  * The references are properscoring's, at 1e-12 relative, and a closed form for the Gaussian case
  * that lets the sample estimator be checked against something that is not a sample at all.
  */
object CrpsSuite extends SimpleIOSuite with Checkers with Approx with MonteCarlo:

  test("the sample CRPS matches properscoring, on every case of the fixture") {
    Fixtures.crpsEnsemble match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(cases) =>
        cats.effect.IO {
          val checks = cases.map { c =>
            closeRel(
              Crps.ensemble(c.ensemble, c.observation),
              c.crps,
              1e-12,
              s"the CRPS of ${c.ensemble.length} samples against ${c.observation}"
            )
          }
          check(cases.length == 6, s"${cases.length} cases in the fixture")
            .and(checks.reduce(_.and(_)))
        }
  }

  test("ties are handled by the sorted form with no special case") {
    // Three equal samples and one apart: the sorted form's weights depend on position, so ties are
    // exactly where an implementation that deduplicated or used strict inequalities would drift.
    // properscoring says 0.1875 and the arithmetic is exact, so this is asserted to the bit.
    Fixtures.crpsTied match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(c) =>
        cats.effect.IO {
          check(
            Crps.ensemble(c.ensemble, c.observation) == c.crps,
            s"the tied case gives ${Crps.ensemble(c.ensemble, c.observation)}, not exactly ${c.crps}"
          ).and(
            closeRel(
              Crps.energy(c.ensemble, c.observation),
              c.crps,
              1e-15,
              "the energy form of the tied case"
            )
          )
        }
  }

  test("the Gaussian CRPS matches its closed form, on every case of the fixture") {
    Fixtures.crpsGaussian match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(cases) =>
        cats.effect.IO {
          val checks = cases.map { c =>
            closeRel(
              Crps.gaussian(c.mu, c.sigma, c.observation),
              c.crps,
              1e-12,
              s"the CRPS of Gaussian(${c.mu}, ${c.sigma}) against ${c.observation}"
            )
          }
          check(cases.length == 4, s"${cases.length} Gaussian cases").and(checks.reduce(_.and(_)))
        }
  }

  /** The criterion of 8.2: the sorted form and the energy form are the same function. */
  test("the sorted form and the energy form agree, including on ties and on one sample") {
    val sample =
      for
        n <- Gen.choose(1, 40)
        values <- Gen.listOfN(n, Gen.choose(-20.0, 20.0))
        // Ties on purpose, often: rounding half the samples to a coarse grid makes them collide.
        rounded <- Gen.oneOf(true, false)
        observation <- Gen.choose(-25.0, 25.0)
      yield (
        IArray.from(if rounded then values.map(v => (v / 5.0).round * 5.0) else values),
        observation
      )
    // A `Show` for the generated case, so that a counterexample is printed as numbers rather
    // than as an array's identity.
    given cats.Show[(IArray[Double], Double)] =
      cats.Show.show((e, y) => s"ensemble [${e.mkString(", ")}] against $y")
    forall(sample) { (ensemble, observation) =>
      val sorted = Crps.ensemble(ensemble, observation)
      val energy = Crps.energy(ensemble, observation)
      // Both sum the same terms in a different order, so they agree to rounding and not beyond:
      // the claim is a few parts in 1e-12 of the score, not bit equality.
      closeRel(sorted, energy, 1e-12, s"${ensemble.length} samples against $observation")
        .and(check(sorted >= 0.0, s"a negative score: $sorted"))
    }
  }

  pureTest("the score of a point forecast is the distance, and a perfect one is zero") {
    closeRel(Crps.ensemble(IArray(3.0), 5.0), 2.0, 1e-15, "one sample at distance two")
      .and(closeRel(Crps.ensemble(IArray(7.0), 7.0), 0.0, 1e-15, "one sample on the observation"))
      .and(
        closeRel(
          Crps.ensemble(IArray.fill(50)(4.0), 4.0),
          0.0,
          1e-15,
          "fifty samples all on the observation"
        )
      )
      // A forecast that is certain and wrong scores its error exactly, whatever its size.
      .and(closeRel(Crps.ensemble(IArray.fill(10)(0.0), 9.0), 9.0, 1e-14, "certain and wrong"))
  }

  pureTest(
    "the score is proper in the direction that matters: spread costs, being wrong costs more"
  ) {
    val tight = IArray.tabulate(201)(i => 5.0 + (i - 100) * 0.01)
    val wide = IArray.tabulate(201)(i => 5.0 + (i - 100) * 0.1)
    val shifted = IArray.tabulate(201)(i => 9.0 + (i - 100) * 0.01)
    val observation = 5.0
    val tightScore = Crps.ensemble(tight, observation)
    val wideScore = Crps.ensemble(wide, observation)
    val shiftedScore = Crps.ensemble(shifted, observation)
    check(tightScore < wideScore, s"a tight forecast scored $tightScore against $wideScore")
      .and(check(wideScore < shiftedScore, s"a wide forecast scored worse than a wrong one"))
      // Scale equivariance: the score has the units of the observation.
      .and(
        closeRel(
          Crps.ensemble(IArray.tabulate(201)(i => 2.0 * wide(i)), 2.0 * observation),
          2.0 * wideScore,
          1e-13,
          "the score doubles with the scale"
        )
      )
      // And it does not depend on the order the samples arrive in.
      .and(
        closeRel(
          Crps.ensemble(IArray.from(wide.toList.reverse), observation),
          wideScore,
          1e-15,
          "the score of the same samples, reversed"
        )
      )
  }

  test("the sample score converges on the Gaussian closed form") {
    // The independent check that ties the two together: draw from a Gaussian, score with the
    // sample form, and compare against the closed form that knows nothing about samples. The
    // sample estimator is biased upwards by about sigma/(n sqrt(pi)) at this size, which is why
    // the comparison is made at a large n and stated as a tolerance rather than an error bar.
    val mu = 1.5
    val sigma = 2.0
    val observation = 2.3
    val n = 200_000
    val program = Monad[Rand].replicateA(n, Rng[Rand].normal.map(z => mu + sigma * z))
    val drawn = IArray.from(program.runA(Seed(20261106L)).value)
    val sampled = Crps.ensemble(drawn, observation)
    val closed = Crps.gaussian(mu, sigma, observation)
    // The standard error of the sample CRPS at this size: the score is an average of n terms with
    // a spread of order sigma, so sigma/sqrt(n) is the right scale, and 4 of them is the threshold.
    val standardError = sigma / math.sqrt(n.toDouble)
    cats.effect.IO {
      withinStandardErrors(
        sampled,
        closed,
        standardError,
        4.0,
        "the sample score against the closed form"
      )
    }
  }

  pureTest("the score is total: nonsense in, NaN out, and no exception") {
    check(Crps.ensemble(IArray.empty[Double], 1.0).isNaN, "an empty ensemble gave a score")
      .and(check(Crps.ensemble(IArray(1.0, Double.NaN), 1.0).isNaN, "a NaN sample gave a score"))
      .and(
        check(Crps.ensemble(IArray(1.0, 2.0), Double.NaN).isNaN, "a NaN observation gave a score")
      )
      .and(
        check(Crps.energy(IArray.empty[Double], 1.0).isNaN, "an empty ensemble has an energy score")
      )
      .and(check(Crps.gaussian(0.0, 0.0, 1.0) == 1.0, "a Gaussian of no width is not its distance"))
      .and(check(Crps.gaussian(0.0, -1.0, 1.0).isNaN, "a negative scale gave a score"))
      .and(check(Crps.gaussian(Double.NaN, 1.0, 1.0).isNaN, "a NaN mean gave a score"))
      // An infinite sample gives NaN, and that is the honest answer rather than a shortcut: both
      // terms of the score diverge — the mean distance and the mean pairwise spread are each
      // infinite — so their difference is indeterminate, not infinite.
      .and(
        check(
          Crps.ensemble(IArray(1.0, Double.PositiveInfinity), 1.0).isNaN,
          "an infinite sample gave a number where the two infinities do not cancel"
        )
      )
      .and(
        check(
          Crps.energy(IArray(1.0, Double.PositiveInfinity), 1.0).isNaN,
          "the energy form disagrees with the sorted one about an infinite sample"
        )
      )
  }
