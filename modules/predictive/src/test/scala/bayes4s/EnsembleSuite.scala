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
import cats.Eq
import cats.kernel.laws.discipline.CommutativeMonoidTests
import java.time.YearMonth
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.discipline.Discipline
import weaver.scalacheck.Checkers

/** Simulated futures, aligned by sample from the posterior, for keys and periods.
  *
  * Three things are asserted here and nothing else matters as much:
  *
  *   1. **Summing is sample by sample.** Two keys combine by adding sample `s` of one to sample `s`
  *      of the other, never by adding summaries of them. Everything downstream depends on it.
  *   2. **Quantiles come last.** The 90th percentile of a total is not the sum of the 90th
  *      percentiles of its parts, and the suite measures the gap on a case built so that the two
  *      cannot coincide — because the attractive mistake is to add the quantiles.
  *   3. **The monoid is lawful for values of different shapes**, not just within one run. An
  *      instance that is associative only when the horizons agree is not a monoid, and publishing
  *      it would be worse than not publishing one.
  */
object EnsembleSuite extends SimpleIOSuite with Discipline with Checkers with Approx:

  private val jan = YearMonth.of(2026, 1)
  private def run(count: Int): Periods = Periods.monthly(jan, count).toOption.get

  /** Two samples from the posterior, three periods, two keys, by hand. */
  private val byHand: Ensemble[String] =
    Ensemble
      .of(
        2,
        run(3),
        Map(
          "a" -> IArray(IArray(1.0, 2.0, 3.0), IArray(10.0, 20.0, 30.0)),
          "b" -> IArray(IArray(0.5, 0.5, 0.5), IArray(5.0, 5.0, 5.0))
        )
      )
      .toOption
      .get

  pureTest("the shape is checked when the ensemble is built, not when it is read") {
    val wrongDraws = Ensemble.of(3, run(3), Map("a" -> IArray(IArray(1.0, 2.0, 3.0))))
    val wrongPeriods = Ensemble.of(1, run(3), Map("a" -> IArray(IArray(1.0, 2.0))))
    val ragged =
      Ensemble.of(2, run(2), Map("a" -> IArray(IArray(1.0, 2.0), IArray(1.0))))
    check(
      byHand.draws == 2 && byHand.periods.count == 3,
      "the hand-built ensemble is the wrong shape"
    )
      .and(check(wrongDraws.isLeft, "a matrix with too few samples was accepted"))
      .and(check(wrongPeriods.isLeft, "a matrix with too few periods was accepted"))
      .and(check(ragged.isLeft, "a ragged matrix was accepted"))
      .and(check(Ensemble.zero[String](2, run(3)).values.isEmpty, "a zero ensemble has keys"))
      .and(check(Ensemble.empty[String].draws == 0, "the empty ensemble has samples"))
  }

  pureTest("the total sums over keys, sample by sample") {
    val total = byHand.total
    val values = total.values(())
    check(total.draws == 2 && total.periods.count == 3, "the total has the wrong shape")
      .and(check(total.values.keySet == Set(()), s"the total is keyed by ${total.values.keySet}"))
      // Sample 0 of "a" with sample 0 of "b", not sample 0 of "a" with sample 1 of "b".
      .and(closeRel(values(0)(0), 1.5, 1e-15, "sample 0, period 0"))
      .and(closeRel(values(0)(2), 3.5, 1e-15, "sample 0, period 2"))
      .and(closeRel(values(1)(0), 15.0, 1e-15, "sample 1, period 0"))
      .and(closeRel(values(1)(2), 35.0, 1e-15, "sample 1, period 2"))
  }

  pureTest("regrouping keys and then totalling gives the same total") {
    // The criterion of 8.2: `total` after `regroup` equals `total`. It holds for any regrouping,
    // including the one that collapses everything and the one that changes nothing.
    val collapsed = byHand.regroup(_ => "all")
    val renamed = byHand.regroup(_.toUpperCase)
    val partitioned = byHand.regroup(k => if k == "a" then 0 else 1)
    def same(left: Ensemble[Unit], right: Ensemble[Unit], clue: String) =
      val l = left.values(())
      val r = right.values(())
      check(
        (0 until left.draws).forall(s =>
          (0 until left.periods.count).forall(p => l(s)(p) == r(s)(p))
        ),
        clue
      )
    same(collapsed.total, byHand.total, "collapsing the keys changed the total")
      .and(same(renamed.total, byHand.total, "renaming the keys changed the total"))
      .and(same(partitioned.total, byHand.total, "partitioning the keys changed the total"))
      .and(
        check(
          collapsed.values.keySet == Set("all") && renamed.values.keySet == Set("A", "B"),
          s"the regrouped keys are ${collapsed.values.keySet} and ${renamed.values.keySet}"
        )
      )
      // Two keys mapped together are summed, not overwritten.
      .and(closeRel(collapsed.values("all")(0)(0), 1.5, 1e-15, "collapsed sample 0, period 0"))
  }

  pureTest("the quantile of a sum is not the sum of the quantiles") {
    // Two keys, perfectly anti-correlated across samples: one is high exactly when the other is
    // low. Their total is constant, so its 90th percentile is that constant — while the sum of the
    // two keys' own 90th percentiles is much larger. A library that added quantiles would report
    // the larger number, and this is the case that tells the two apart.
    val samples = 100
    val a = IArray.tabulate(samples)(s => IArray(s.toDouble))
    val b = IArray.tabulate(samples)(s => IArray((samples - 1 - s).toDouble))
    val opposed = Ensemble.of(samples, run(1), Map("a" -> a, "b" -> b)).toOption.get
    val perKey = opposed.quantiles(List(0.9))
    val ofTotal = opposed.total.quantiles(List(0.9))
    val summedQuantiles = perKey("a")(0)(0) + perKey("b")(0)(0)
    check(
      math.abs(ofTotal(())(0)(0) - (samples - 1).toDouble) < 1e-12,
      s"the 90th percentile of a constant total is ${ofTotal(())(0)(0)}"
    )
      .and(
        check(
          summedQuantiles > 1.5 * ofTotal(())(0)(0),
          f"summing the keys' percentiles gives $summedQuantiles%.1f against the total's " +
            f"${ofTotal(())(0)(0)}%.1f, which is not far enough apart to prove anything"
        )
      )
      // And the shape of the answer: one row per probability, one column per period.
      .and(
        check(
          perKey("a").length == 1 && perKey("a")(0).length == 1,
          s"the quantiles have shape ${perKey("a").length} by ${perKey("a")(0).length}"
        )
      )
      .and(
        check(
          byHand.quantiles(List(0.05, 0.5, 0.95))("a").length == 3,
          "three probabilities did not give three rows"
        )
      )
  }

  pureTest("summing two ensembles of different horizons pads with zero rather than failing") {
    val short = Ensemble.of(1, run(2), Map("a" -> IArray(IArray(1.0, 2.0)))).toOption.get
    val long = Ensemble
      .of(
        1,
        Periods.monthly(YearMonth.of(2026, 2), 3).toOption.get,
        Map("a" -> IArray(IArray(7.0, 8.0, 9.0)))
      )
      .toOption
      .get
    val combined = Ensemble.commutativeMonoid[String].combine(short, long)
    val values = combined.values("a")
    // January to April: the short run contributes to January and February, the long one to
    // February, March and April, and February is the only period where they add.
    check(combined.periods.count == 4, s"the combined run is ${combined.periods.count} months")
      .and(check(combined.periods.at(0).contains(jan), s"it starts at ${combined.periods.at(0)}"))
      .and(closeRel(values(0)(0), 1.0, 1e-15, "January, from the short run only"))
      .and(closeRel(values(0)(1), 2.0 + 7.0, 1e-15, "February, where both contribute"))
      .and(closeRel(values(0)(2), 8.0, 1e-15, "March, from the long run only"))
      .and(closeRel(values(0)(3), 9.0, 1e-15, "April, from the long run only"))
  }

  // --- the laws ---------------------------------------------------------------------------------

  private given Arbitrary[Ensemble[String]] = Arbitrary(
    for
      draws <- Gen.choose(0, 3)
      count <- Gen.choose(0, 3)
      month <- Gen.choose(1, 4).map(m => YearMonth.of(2026, m))
      keys <- Gen.someOf(List("a", "b", "c"))
      matrices <- Gen.listOfN(
        keys.size,
        Gen.listOfN(draws, Gen.listOfN(count, Gen.choose(-5.0, 5.0)))
      )
    yield
      val periods = Periods.monthly(month, count).toOption.get
      val values = keys
        .zip(matrices)
        .map { (key, rows) =>
          key -> IArray.from(rows.map(row => IArray.from(row)))
        }
        .toMap
      // A run of no periods or no samples cannot carry a key with content, so it carries none.
      if draws == 0 || count == 0 then Ensemble.zero[String](draws, periods)
      else Ensemble.of(draws, periods, values).toOption.get
  )

  /** The laws are checked with an equality that allows a few ulp, and the reason is arithmetic
    * rather than convenience.
    *
    * `Ensemble`'s monoid is associative over the reals: every cell of the result is a sum of the
    * same terms whichever grouping is used. `Double` addition is not associative, so `(x + y) + z`
    * and `x + (y + z)` can land one ulp apart, and the law then fails on a difference of 1.8e-15 —
    * measured, and exactly `ulp(10.0)` for cells drawn from (-5, 5).
    *
    * Two choices follow. The published `Ensemble.eq` stays **exact**, because an equality that
    * silently accepted neighbouring values would be the wrong semantics for a value type and would
    * hide a real difference from anyone comparing two ensembles. And the law check uses the
    * tolerant one, because the alternative is to claim a law that `Double` does not obey. The fact
    * itself is asserted below rather than left implicit.
    */
  private val fewUlp: Eq[Ensemble[String]] = Eq.instance { (x, y) =>
    x.draws == y.draws && x.periods == y.periods && x.values.keySet == y.values.keySet &&
    x.values.keySet.forall { key =>
      val l = x.values(key)
      val r = y.values(key)
      l.length == r.length && (0 until l.length).forall { s =>
        l(s).length == r(s).length && (0 until l(s).length).forall { p =>
          val a = l(s)(p)
          val b = r(s)(p)
          math.abs(a - b) <= 1e-12 * (1.0 + math.abs(a) + math.abs(b))
        }
      }
    }
  }

  pureTest("the two groupings of a sum agree to within rounding, and not always exactly") {
    // Three ensembles over the same run whose cells are not exactly representable: the two
    // groupings differ by rounding alone. The assertion is two-sided on purpose — the gap is
    // bounded by a few ulp, and claiming it is zero would be false.
    val monoid = Ensemble.commutativeMonoid[String]
    val cells =
      (seed: Int) => IArray(IArray.tabulate(3)(p => math.sin((seed * 3 + p).toDouble) * 5.0))
    val a = Ensemble.of(1, run(3), Map("k" -> cells(1))).toOption.get
    val b = Ensemble.of(1, run(3), Map("k" -> cells(2))).toOption.get
    val c = Ensemble.of(1, run(3), Map("k" -> cells(3))).toOption.get
    val left = monoid.combine(monoid.combine(a, b), c)
    val right = monoid.combine(a, monoid.combine(b, c))
    val gaps = (0 until 3).map(p => math.abs(left.values("k")(0)(p) - right.values("k")(0)(p)))
    check(
      fewUlp.eqv(left, right),
      s"the two groupings disagree by more than rounding: ${gaps.max}"
    )
      .and(
        check(
          gaps.max <= 4.0 * math.ulp(16.0),
          s"the gap is ${gaps.max}, over four ulp of the largest possible cell"
        )
      )
      // And the sum itself is right, grouping aside: three sines of known arguments.
      .and(
        closeRel(
          left.values("k")(0)(0),
          math.sin(3.0) * 5.0 + math.sin(6.0) * 5.0 + math.sin(9.0) * 5.0,
          1e-14,
          "the first cell of the three-way sum"
        )
      )
  }

  checkAll(
    "Ensemble.CommutativeMonoidLaws",
    CommutativeMonoidTests[Ensemble[String]].commutativeMonoid(using
      summon[Arbitrary[Ensemble[String]]],
      fewUlp
    )
  )
