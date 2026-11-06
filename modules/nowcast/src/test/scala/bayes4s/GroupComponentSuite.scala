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
import bayes4s.testkit.MonteCarlo
import weaver.SimpleIOSuite

/** The group structures: groups that borrow from each other, and populations above them.
  *
  * Phase 9's components gave every group its own level and trend with no connection between them.
  * These add the connection, and the point of each is a different answer to "what does a group
  * with little data of its own fall back on?"
  *
  *   - `Ar1TrendHierarchical` with one population: every group's level and trend are drawn around
  *     a common one, so a quiet group is pulled towards the average of the others.
  *   - the same with two populations: each population has its own level and trend, and what they
  *     share is the **spread** of their groups and the wobble — the common upper level.
  *   - `LocalLevel`: no trend at all, a random walk. Fitted to data that is not trending it must
  *     not invent one, which is the criterion this phase states.
  *   - `OrderedLogitDriftByGroup`: one reporting speed per group around a common one.
  *
  * The centred and non-centred parameterisations are **one component with a flag**, not two
  * models: they describe the same posterior and differ only in which coordinates the sampler
  * walks in, so a test that they agree on the rate is the test that the flag is cosmetic.
  */
object GroupComponentSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val months = 6
  private val groups = 4
  private val t = IArray.tabulate(months)(m => (m - 2.5) / months)
  private val onePopulation = IArray.fill(groups)(0)
  private val twoPopulations = IArray(0, 0, 1, 1)

  pureTest("a hierarchical rate is the group's own level and trend, plus the shared wobble") {
    val component = Ar1TrendHierarchical(onePopulation)
    val p = HierarchicalAr1Params[Double](
      muA = IArray(2.0),
      muB = IArray(0.5),
      sigmaA = 0.3,
      sigmaB = 0.1,
      aRaw = IArray(1.0, -1.0, 0.0, 2.0),
      bRaw = IArray(0.0, 1.0, -1.0, 0.0),
      phi = 0.5,
      tau = 0.0,
      eps = IArray.fill(months)(0.0)
    )
    // Non-centred by default: a[g] = muA + sigmaA * aRaw[g].
    val levels = (0 until groups).map(g => 2.0 + 0.3 * p.aRaw(g))
    val trends = (0 until groups).map(g => 0.5 + 0.1 * p.bRaw(g))
    val checks = (0 until groups).toList.map { g =>
      val rate = component.logRate[Double](p, g, t)
      (0 until months)
        .map(m => closeRel(rate(m), levels(g) + trends(g) * t(m), 1e-14, s"group $g, month $m"))
        .reduce(_.and(_))
    }
    checks
      .reduce(_.and(_))
      // One population: muA and muB are one each, then the two spreads, the two effects per
      // group, phi and tau, and one innovation per month.
      .and(
        check(
          component.params(groups, months).unconstrainedSize == 2 + 2 + 2 * groups + 2 + months,
          s"${component.params(groups, months).unconstrainedSize} reals, expected 20"
        )
      )
  }

  pureTest("centred and non-centred describe the same rate, in different coordinates") {
    // The same group levels, written two ways: centred holds a[g] itself, non-centred holds the
    // standardised version. A test that the rates agree is the test that the flag is cosmetic.
    val nonCentred = Ar1TrendHierarchical(onePopulation, centred = false)
    val centred = Ar1TrendHierarchical(onePopulation, centred = true)
    val muA = 2.0
    val sigmaA = 0.3
    val standardised = IArray(1.0, -1.0, 0.5, 2.0)
    val asLevels = IArray.tabulate(groups)(g => muA + sigmaA * standardised(g))
    val phi = 0.4
    val tau = 0.2
    val eps = IArray.tabulate(months)(m => math.sin(m.toDouble))
    val left = HierarchicalAr1Params[Double](
      IArray(muA),
      IArray(0.0),
      sigmaA,
      1.0,
      standardised,
      IArray.fill(groups)(0.0),
      phi,
      tau,
      eps
    )
    val right = HierarchicalAr1Params[Double](
      IArray(muA),
      IArray(0.0),
      sigmaA,
      1.0,
      asLevels,
      IArray.fill(groups)(0.0),
      phi,
      tau,
      eps
    )
    val checks = (0 until groups).toList.map { g =>
      val a = nonCentred.logRate[Double](left, g, t)
      val b = centred.logRate[Double](right, g, t)
      (0 until months)
        .map(m => closeRel(a(m), b(m), 1e-14, s"group $g, month $m, the two parameterisations"))
        .reduce(_.and(_))
    }
    checks
      .reduce(_.and(_))
      // And the priors differ, which is the whole point: the non-centred one puts a standard
      // Gaussian on the standardised effects, the centred one puts the group's own prior on them.
      .and(
        check(
          nonCentred.logPrior[Double](left) != centred.logPrior[Double](left),
          "the two parameterisations have the same prior, so the flag does nothing"
        )
      )
  }

  pureTest("two populations have their own levels, and share the spread above them") {
    val component = Ar1TrendHierarchical(twoPopulations)
    val p = HierarchicalAr1Params[Double](
      muA = IArray(1.0, 5.0),
      muB = IArray(0.0, 0.0),
      sigmaA = 0.2,
      sigmaB = 0.1,
      aRaw = IArray(0.0, 1.0, 0.0, -1.0),
      bRaw = IArray.fill(groups)(0.0),
      phi = 0.0,
      tau = 0.0,
      eps = IArray.fill(months)(0.0)
    )
    val rates = (0 until groups).map(g => component.logRate[Double](p, g, t).head)
    // Groups 0 and 1 belong to the first population, 2 and 3 to the second: their levels sit
    // around 1 and around 5, and the spread that places them there is the same 0.2.
    closeRel(rates(0), 1.0, 1e-14, "group 0, at its population's level")
      .and(closeRel(rates(1), 1.0 + 0.2, 1e-14, "group 1, one spread above it"))
      .and(closeRel(rates(2), 5.0, 1e-14, "group 2, at the other population's level"))
      .and(closeRel(rates(3), 5.0 - 0.2, 1e-14, "group 3, one spread below it"))
      .and(
        check(
          component.params(groups, months).names.count(_.startsWith("muA")) == 2,
          s"the names are ${component.params(groups, months).names.take(6)}"
        )
      )
      // A population index outside the range is refused when the component is built, not silently
      // read as zero.
      .and(check(Ar1TrendHierarchical.of(IArray(0, 2), 2).isLeft, "a population out of range"))
      .and(check(Ar1TrendHierarchical.of(IArray(0, 1), 2).isRight, "a valid mapping was refused"))
  }

  pureTest("a local level is a random walk, and its projection is flat in expectation") {
    val component = LocalLevel()
    val p = LocalLevelParams[Double](
      level0 = IArray(3.0, 4.0),
      sigma = 0.25,
      eps = IArray.tabulate(months)(m => if m == 0 then 2.0 else 0.0)
    )
    val rate = component.logRate[Double](p, 0, t)
    // One innovation at the start and none after: the level steps once and stays there. A random
    // walk has no trend to continue — that is the whole difference from `Ar1Trend`.
    check(rate.length == months, s"${rate.length} months")
      .and(closeRel(rate(0), 3.0 + 0.25 * 2.0, 1e-14, "the first month"))
      .and(
        (1 until months)
          .map(m => closeRel(rate(m), rate(0), 1e-14, s"month $m, after the step"))
          .reduce(_.and(_))
      )
      // The second group has its own starting level and the same walk.
      .and(closeRel(component.logRate[Double](p, 1, t).head, 4.0 + 0.5, 1e-14, "the second group"))
  }

  test("a local level's projection does not drift, over many seeds") {
    // The criterion of 8.2, read where it can be measured: a random walk's forecast is its last
    // level, so the average projected rate must not move with the horizon. A component that had
    // smuggled in a trend would drift.
    val component = LocalLevel()
    val p = LocalLevelParams[Double](IArray(3.0), 0.3, IArray.fill(months)(0.0))
    val futureT = IArray.tabulate(8)(h => 0.5 + h * 0.1)
    val replications = 20_000
    val projections = IArray.tabulate(replications) { i =>
      component.project(p, 0, futureT, Seed(20261107L).fold(i.toLong))
    }
    cats.effect.IO {
      val checks = (0 until 8).toList.map { h =>
        val column = IArray.tabulate(replications)(i => projections(i)(h))
        val mean = (0 until replications).foldLeft(0.0)((s, i) => s + column(i)) / replications
        // The projected log rate at horizon h is the last level plus h steps of noise: its mean is
        // the last level and its standard error is sigma sqrt(h)/sqrt(n).
        val standardError = 0.3 * math.sqrt(h + 1.0) / math.sqrt(replications.toDouble)
        withinStandardErrors(mean, 3.0, standardError, 4.0, s"the projection at horizon $h")
      }
      checks
        .reduce(_.and(_))
        // And the spread grows with the horizon, which is what a walk does and what makes its
        // forecast honest about the distance.
        .and {
          def spread(h: Int): Double =
            val column = IArray.tabulate(replications)(i => projections(i)(h))
            val mean = (0 until replications).foldLeft(0.0)((s, i) => s + column(i)) / replications
            math.sqrt(
              (0 until replications).foldLeft(0.0)((s, i) => s + math.pow(column(i) - mean, 2.0)) /
                (replications - 1.0)
            )
          check(
            spread(7) > 2.0 * spread(0),
            f"the spread goes from ${spread(0)}%.3f to ${spread(7)}%.3f"
          )
        }
    }
  }

  pureTest("a per-group delay shifts every cut point by its group's own offset") {
    val component = OrderedLogitDriftByGroup()
    val p = GroupedDelayParams[Double](
      cut0 = 0.3,
      increments = IArray(0.8, 0.6),
      beta = 0.0,
      delta = IArray(0.0, 1.5, -1.5, 0.0),
      sigmaCut = 0.5
    )
    val common = OrderedLogitDrift()
    val plain = OrderedLogitDriftParams[Double](0.3, IArray(0.8, 0.6), 0.0)
    val groupZero = component.logPmf[Double](p, 0, 0.0)
    val reference = common.logPmf[Double](plain, 0, 0.0)
    // Group 0 has no offset, so it is the common delay exactly.
    (0 until groupZero.length)
      .map(k => closeRel(groupZero(k), reference(k), 1e-14, s"group 0, delay $k"))
      .reduce(_.and(_))
      // A positive offset raises every cut point, which means more mass at the short delays: that
      // group reports faster.
      .and {
        val fast = math.exp(component.logPmf[Double](p, 1, 0.0).head)
        val slow = math.exp(component.logPmf[Double](p, 2, 0.0).head)
        check(
          fast > math.exp(reference.head) && slow < math.exp(reference.head),
          f"the offsets gave same-month shares $fast%.4f and $slow%.4f around ${math.exp(reference.head)}%.4f"
        )
      }
      // And every group's probabilities still sum to one, offset or not.
      .and {
        (0 until 4)
          .map { g =>
            val pmf = component.logPmf[Double](p, g, 0.3)
            val total = (0 until pmf.length).foldLeft(0.0)((s, k) => s + math.exp(pmf(k)))
            closeRel(total, 1.0, 1e-14, s"group $g's probabilities sum to $total")
          }
          .reduce(_.and(_))
      }
  }

  pureTest("the per-group offsets are drawn around their own fitted spread") {
    // The offsets' prior scale is `sigmaCut`, a parameter, and that link is what lets the data say
    // how much the groups' reporting speeds differ. Replacing it with a constant leaves the model
    // looking healthy — calibration cannot see it, because a parameter whose posterior equals its
    // prior still has uniform ranks — so the dependence is asserted directly here.
    val component = OrderedLogitDriftByGroup()
    val base = GroupedDelayParams[Double](0.3, IArray(0.8), 0.0, IArray(0.4, -0.4), 0.5)
    val tighter = base.copy(sigmaCut = 0.2)
    val wider = base.copy(sigmaCut = 2.0)
    // The offsets are held fixed and only their spread moves: a tighter spread makes these offsets
    // less likely, a wider one makes them more likely, right up to where the spread's own prior
    // starts to object.
    val atBase = component.logPrior[Double](base)
    check(
      component.logPrior[Double](tighter) < atBase,
      f"a tighter spread scored ${component.logPrior[Double](tighter)}%.4f against $atBase%.4f"
    )
      .and {
        // Offsets of 1.5 are implausible under a spread of 0.2 and ordinary under one of 2.
        val far = base.copy(delta = IArray(1.5, -1.5))
        check(
          component.logPrior[Double](far.copy(sigmaCut = 2.0)) >
            component.logPrior[Double](far.copy(sigmaCut = 0.2)),
          "far offsets are no more likely under a wide spread than under a narrow one"
        )
      }
      // And the dependence is the Gaussian one, exactly: two offsets under a known spread.
      .and {
        val only = GroupedDelayParams[Double](0.0, IArray.empty, 0.0, IArray(0.4, -0.4), 0.5)
        val byHand =
          LogDensity.normal(0.0, 0.0, 2.0) +
            LogDensity.normal(0.4, 0.0, 0.5) + LogDensity.normal(-0.4, 0.0, 0.5) +
            LogDensity.normal(0.0, 0.0, 1.0) + LogDensity.halfNormal(0.5, 0.5)
        closeRel(component.logPrior[Double](only), byHand, 1e-13, "the prior, written out by hand")
      }
      .and(check(wider.sigmaCut > base.sigmaCut, "the test's own fixture is wrong"))
  }

  pureTest("every rate component is total: a month it has no innovation for gives NaN") {
    // Found by the manual rather than by reasoning: asking for a rate at a time the innovations do
    // not cover used to throw, which breaks 2.1's rule that a public function returns a value. The
    // future is reached through `project`, which is given its own times; `logRate` says `NaN` for a
    // month whose innovation was never declared, and for a group that does not exist.
    val tooLong = IArray.tabulate(months + 3)(m => m * 0.1)
    val ar1 = Ar1TrendParams[Double](IArray(1.0), IArray(0.0), 0.5, 0.1, IArray.fill(months)(0.0))
    val hier = HierarchicalAr1Params[Double](
      IArray(1.0),
      IArray(0.0),
      0.3,
      0.1,
      IArray.fill(groups)(0.0),
      IArray.fill(groups)(0.0),
      0.5,
      0.1,
      IArray.fill(months)(0.0)
    )
    val walk = LocalLevelParams[Double](IArray(1.0), 0.2, IArray.fill(months)(0.0))
    val fromAr1 = Ar1Trend().logRate[Double](ar1, 0, tooLong)
    val fromHier = Ar1TrendHierarchical(onePopulation).logRate[Double](hier, 0, tooLong)
    val fromWalk = LocalLevel().logRate[Double](walk, 0, tooLong)
    check(
      (0 until months).forall(m => fromAr1(m).isFinite),
      "the months the innovations cover are not finite"
    )
      .and(
        check(
          (months until months + 3)
            .forall(m => fromAr1(m).isNaN && fromHier(m).isNaN && fromWalk(m).isNaN),
          "a month past the innovations gave a number"
        )
      )
      // And a group nobody declared: `NaN` rather than the first group's rate, which would be a
      // plausible-looking answer to a question about a group that does not exist.
      .and(
        check(
          Ar1Trend().logRate[Double](ar1, 5, t).forall(_.isNaN) &&
            LocalLevel().logRate[Double](walk, 5, t).forall(_.isNaN) &&
            Ar1TrendHierarchical(onePopulation).logRate[Double](hier, 9, t).forall(_.isNaN),
          "a group outside the data was given a rate"
        )
      )
  }

  pureTest("every new component names what a report and a calibration should rank") {
    val hierarchical = Ar1TrendHierarchical(twoPopulations)
    val local = LocalLevel()
    val grouped = OrderedLogitDriftByGroup()
    val hp = HierarchicalAr1Params[Double](
      IArray(1.0, 2.0),
      IArray(0.1, 0.2),
      0.3,
      0.4,
      IArray.fill(groups)(0.0),
      IArray.fill(groups)(0.0),
      0.5,
      0.6,
      IArray.fill(months)(0.0)
    )
    val lp = LocalLevelParams[Double](IArray(1.0), 0.2, IArray.fill(months)(0.0))
    val gp = GroupedDelayParams[Double](0.3, IArray(0.5), 0.1, IArray.fill(groups)(0.0), 0.4)
    check(
      hierarchical.named(hp).keySet == Set("muA", "muB", "sigmaA", "sigmaB", "phi", "tau"),
      s"the hierarchical names are ${hierarchical.named(hp).keySet}"
    )
      .and(check(hierarchical.named(hp)("muA").length == 2, "one name per population"))
      .and(
        check(
          local.named(lp).keySet == Set("level0", "sigma"),
          s"the local level names are ${local.named(lp).keySet}"
        )
      )
      .and(
        check(
          grouped.named(gp).keySet == Set("cut0", "beta", "sigmaCut"),
          s"the grouped delay names are ${grouped.named(gp).keySet}"
        )
      )
  }
