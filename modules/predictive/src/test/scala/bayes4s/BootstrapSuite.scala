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
import cats.Monad
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The Bayesian bootstrap, and the pools a simulated future draws its events from.
  *
  * The weights are Dirichlet(1, ..., 1) over the pool: that is the posterior of the pool's own
  * composition under the uninformative limit, so drawing them is drawing one opinion about which
  * of the observed events are representative. Each simulated future gets **one** such opinion and
  * keeps it for the whole horizon.
  *
  * That last part is the claim worth testing, because it is the one that is easy to get wrong and
  * invisible when wrong: redrawing the weights every period would average their uncertainty away
  * inside each future and give intervals that are too narrow. The suite measures that directly
  * rather than asserting it in a comment.
  */
object BootstrapSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val replications = 20_000

  pureTest("the weights are positive, sum to one, and give every item the same expectation") {
    val size = 5
    val drawn = IArray.tabulate(replications) { i =>
      BayesianBootstrap.weights[Rand](size).runA(Seed(20261106L).fold(i.toLong)).value
    }
    val sums =
      IArray.tabulate(replications)(i => (0 until size).foldLeft(0.0)((s, k) => s + drawn(i)(k)))
    val allPositive =
      (0 until replications).forall(i => (0 until size).forall(k => drawn(i)(k) > 0.0))
    val worstSum = (0 until replications).map(i => math.abs(sums(i) - 1.0)).max
    // Every component of a Dirichlet(1, ..., 1) has mean 1/n and variance (1/n)(1 - 1/n)/(n + 1).
    val expectedMean = 1.0 / size
    val expectedVariance = expectedMean * (1.0 - expectedMean) / (size + 1.0)
    val perItem = (0 until size).toList.map { k =>
      val column = IArray.tabulate(replications)(i => drawn(i)(k))
      val mean = (0 until replications).foldLeft(0.0)((s, i) => s + column(i)) / replications
      val variance =
        (0 until replications).foldLeft(0.0)((s, i) => s + math.pow(column(i) - mean, 2.0)) /
          (replications - 1.0)
      withinStandardErrors(
        mean,
        expectedMean,
        math.sqrt(expectedVariance / replications),
        4.0,
        s"the mean weight of item $k"
      ).and(
        withinStandardErrors(
          variance,
          expectedVariance,
          expectedVariance * math.sqrt(2.0 / (replications - 1.0)),
          4.0,
          s"the variance of the weight of item $k"
        )
      )
    }
    check(allPositive, "a weight came out at zero or below")
      .and(check(worstSum <= 1e-12, s"the weights sum to one only within $worstSum"))
      .and(perItem.reduce(_.and(_)))
      .and(
        check(
          BayesianBootstrap.weights[Rand](0).runA(Seed(1L)).value.isEmpty,
          "an empty pool gave weights"
        )
      )
  }

  pureTest("a pool draws its items in proportion to its weights") {
    val items = IArray("a", "b", "c")
    val weights = IArray(0.6, 0.3, 0.1)
    TemplatePool.of(items, weights) match
      case Left(error) => failure(error.message)
      case Right(pool) =>
        val counts = (0 until replications).foldLeft(Map.empty[String, Int]) { (collected, i) =>
          val item = pool.draw[Rand].runA(Seed(5L).fold(i.toLong)).value
          collected.updatedWith(item)(c => Some(c.getOrElse(0) + 1))
        }
        val checks = (0 until 3).toList.map { k =>
          val share = counts.getOrElse(items(k), 0).toDouble / replications
          val p = weights(k)
          withinStandardErrors(
            share,
            p,
            math.sqrt(p * (1.0 - p) / replications),
            4.0,
            s"the share of ${items(k)}"
          )
        }
        checks.reduce(_.and(_)).and(check(pool.size == 3, s"the pool holds ${pool.size}"))
  }

  pureTest("a pool refuses what it cannot draw from") {
    check(
      TemplatePool.of(IArray.empty[String], IArray.empty[Double]).isLeft,
      "an empty pool was built"
    )
      .and(
        check(
          TemplatePool.of(IArray("a"), IArray(1.0, 2.0)).isLeft,
          "mismatched lengths were accepted"
        )
      )
      .and(
        check(
          TemplatePool.of(IArray("a", "b"), IArray(1.0, -1.0)).isLeft,
          "a negative weight was accepted"
        )
      )
      .and(
        check(
          TemplatePool.of(IArray("a", "b"), IArray(0.0, 0.0)).isLeft,
          "weights summing to zero were accepted"
        )
      )
      .and(
        check(TemplatePool.of(IArray("a"), IArray(Double.NaN)).isLeft, "a NaN weight was accepted")
      )
  }

  /** The claim of 5.8: weights fixed over the horizon, and why it matters. */
  pureTest("weights fixed over the horizon give wider futures than weights redrawn each period") {
    // A pool whose items differ a lot: the composition opinion then matters. Each future draws
    // twelve periods' worth of events and we look at the spread of the futures' own averages.
    val items = IArray(1.0, 1.0, 1.0, 1.0, 20.0)
    val periods = 12
    val eventsPerPeriod = 20
    val futures = 2000

    def futureAverage(fixed: Boolean, i: Int): Double =
      val program =
        if fixed then
          // One opinion, held for the whole horizon: this is what the library does.
          for
            weights <- BayesianBootstrap.weights[Rand](items.length)
            pool = TemplatePool.of(items, weights).toOption.get
            drawn <- Monad[Rand].replicateA(periods * eventsPerPeriod, pool.draw[Rand])
          yield drawn.sum / drawn.length
        else
          // A fresh opinion every period: the mistake.
          (0 until periods).toList
            .traverse { _ =>
              for
                weights <- BayesianBootstrap.weights[Rand](items.length)
                pool = TemplatePool.of(items, weights).toOption.get
                drawn <- Monad[Rand].replicateA(eventsPerPeriod, pool.draw[Rand])
              yield drawn.sum
            }
            .map(sums => sums.sum / (periods * eventsPerPeriod).toDouble)
      program.runA(Seed(4242L).fold(i.toLong)).value

    def spread(fixed: Boolean): Double =
      val values = IArray.tabulate(futures)(i => futureAverage(fixed, i))
      val mean = (0 until futures).foldLeft(0.0)((s, i) => s + values(i)) / futures
      math.sqrt(
        (0 until futures).foldLeft(0.0)((s, i) =>
          s + math.pow(values(i) - mean, 2.0)
        ) / (futures - 1.0)
      )

    val held = spread(fixed = true)
    val redrawn = spread(fixed = false)
    // Both are unbiased for the pool's mean; what differs is the spread, and holding the weights
    // has to give the larger one. The factor is not asserted precisely — it depends on the pool —
    // but it is large enough here that no amount of Monte Carlo noise explains it.
    check(
      held > 1.5 * redrawn,
      f"holding the weights gave a spread of $held%.4f against $redrawn%.4f when redrawn, " +
        "which is not the difference the specification says this choice makes"
    )
  }

  pureTest("a stratum too small to speak for itself falls back on the whole pool") {
    // Two strata: a large one whose items are all 1, and a small one whose items are all 100. The
    // values say where a draw came from, so the mixing can be measured rather than inspected.
    val big = IArray.fill(40)(1.0)
    val small = IArray.fill(2)(100.0)
    val items = big ++ small
    val stratumOf: Double => String = value => if value > 50.0 then "small" else "big"
    val pools = StratifiedPools
      .of(items, stratumOf, minimumSize = 5, mixingPrior = 10.0)
      .toOption
      .get
    // The small stratum has two items, under the minimum of five, so it has no pool of its own:
    // every draw for it comes from the whole pool, where one item in twenty-one is a 100.
    val fromSmall = IArray.tabulate(replications) { i =>
      pools.draw[Rand]("small").runA(Seed(8L).fold(i.toLong)).value
    }
    val shareOfHundreds =
      (0 until replications).count(i => fromSmall(i) > 50.0).toDouble / replications
    val globalShare = 2.0 / 42.0
    // The big stratum is above the minimum, so it mixes its own pool with the global one at
    // w = n/(n + k0) = 40/50 = 0.8: four draws in five from its own items, one in five from
    // everything. Its own items are never 100, so the share of hundreds is 0.2 * 2/42.
    val fromBig = IArray.tabulate(replications) { i =>
      pools.draw[Rand]("big").runA(Seed(9L).fold(i.toLong)).value
    }
    val bigShare = (0 until replications).count(i => fromBig(i) > 50.0).toDouble / replications
    val expectedBig = 0.2 * globalShare
    check(
      math.abs(pools.mixingWeight("big") - 0.8) < 1e-12,
      s"the big stratum mixes at ${pools.mixingWeight("big")}"
    )
      .and(
        check(
          pools.mixingWeight("small") == 0.0,
          s"a stratum below the minimum mixes at ${pools.mixingWeight("small")}"
        )
      )
      .and(
        check(
          pools.mixingWeight("absent") == 0.0,
          "a stratum nobody has heard of does not fall back on the whole pool"
        )
      )
      .and(
        withinStandardErrors(
          shareOfHundreds,
          globalShare,
          math.sqrt(globalShare * (1.0 - globalShare) / replications),
          4.0,
          "the share of the rare item when the stratum falls back"
        )
      )
      .and(
        withinStandardErrors(
          bigShare,
          expectedBig,
          math.sqrt(expectedBig * (1.0 - expectedBig) / replications),
          4.0,
          "the share of the rare item for a stratum that mixes"
        )
      )
  }

  pureTest("the mixing weight is the group's size against the prior, and nothing else") {
    closeRel(BayesianBootstrap.mixingWeight(40, 10.0), 0.8, 1e-15, "forty against ten")
      .and(closeRel(BayesianBootstrap.mixingWeight(10, 10.0), 0.5, 1e-15, "ten against ten"))
      // No data means no opinion of one's own; no prior means no borrowing.
      .and(check(BayesianBootstrap.mixingWeight(0, 10.0) == 0.0, "an empty group kept a weight"))
      .and(
        check(BayesianBootstrap.mixingWeight(7, 0.0) == 1.0, "a group with no prior still borrowed")
      )
      .and(check(BayesianBootstrap.mixingWeight(-1, 10.0) == 0.0, "a negative size gave a weight"))
      .and(check(BayesianBootstrap.mixingWeight(5, -1.0).isNaN, "a negative prior gave a weight"))
  }
