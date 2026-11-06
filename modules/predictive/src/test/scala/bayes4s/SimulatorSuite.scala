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
import java.time.YearMonth
import weaver.SimpleIOSuite

/** One simulated future, and the run of them that makes an ensemble.
  *
  * The library knows only `Contribution`: a key, an offset from the period an event closes in, and
  * an amount. What an event is and what it contributes belong to the application, which is why the
  * rule is a trait and the payload a type parameter.
  *
  * Two claims are worth the suite. A rule that spreads an event over later periods must put each
  * contribution at **close plus offset** and drop what falls off the end of the run — asserted on
  * cases built by hand, because an off-by-one here is invisible in aggregate. And an amount and the
  * components it decomposes into must come from the **same payload in the same future**, or their
  * correlation is lost and every interval downstream is too narrow.
  */
object SimulatorSuite extends SimpleIOSuite with Approx:

  private val run = Periods.monthly(YearMonth.of(2026, 1), 4).toOption.get

  /** An event's payload: a size, and the shares it is spread over, each with its own delay. */
  final private case class Template(size: Double, parts: List[(String, Int, Double)])

  /** The rule under test: the whole size at the close, and each part at its own delay. */
  private val rule: ContributionRule[Template, String] =
    (payload, scaled) =>
      Contribution("size", 0, scaled) ::
        payload.parts.map((name, delay, share) => Contribution(name, delay, scaled * share))

  pureTest("a rule with an offset places each contribution at close plus offset") {
    val template = Template(100.0, List(("x", 1, 0.25), ("y", 3, 0.5)))
    // Closing in period 1 of a run of 4: the size lands in 1, x in 2, y in 4 — which is outside
    // the run and must be dropped rather than wrapped, clamped or counted in the last period.
    val future = SimulatedFuture
      .empty[String](run)
      .add(1, rule.contributions(template, 100.0))
    val totals = future.totals
    check(totals.keySet == Set("size", "x", "y"), s"the keys are ${totals.keySet}")
      .and(closeRel(totals("size")(1), 100.0, 1e-15, "the size in its own period"))
      .and(check(totals("size")(0) == 0.0 && totals("size")(2) == 0.0, "the size leaked"))
      .and(closeRel(totals("x")(2), 25.0, 1e-15, "x at close plus one"))
      .and(check((0 until 4).filter(_ != 2).forall(p => totals("x")(p) == 0.0), "x leaked"))
      // y would land at offset 4 in a run of 4: nothing of it is anywhere.
      .and(
        check(
          (0 until 4).forall(p => totals("y")(p) == 0.0),
          s"y fell outside the run and still appears: ${totals("y").mkString(", ")}"
        )
      )
  }

  pureTest("two events in one future add, period by period") {
    val first = Template(10.0, List(("x", 1, 1.0)))
    val second = Template(4.0, List(("x", 0, 1.0)))
    val future = SimulatedFuture
      .empty[String](run)
      .add(0, rule.contributions(first, 10.0))
      .add(1, rule.contributions(second, 4.0))
    val x = future.totals("x")
    // The first event's x is delayed into period 1; the second closes in period 1 with no delay.
    check(x.length == 4, s"${x.length} periods")
      .and(closeRel(x(1), 10.0 + 4.0, 1e-15, "the two events' x in the period they share"))
      .and(check(x(0) == 0.0 && x(2) == 0.0 && x(3) == 0.0, s"x leaked: ${x.mkString(", ")}"))
      .and(closeRel(future.totals("size")(0), 10.0, 1e-15, "the first size"))
      .and(closeRel(future.totals("size")(1), 4.0, 1e-15, "the second size"))
  }

  pureTest("a contribution before the close, or far after it, is dropped and not wrapped") {
    val backwards = List(Contribution("k", -1, 5.0), Contribution("k", 0, 1.0))
    val future = SimulatedFuture.empty[String](run).add(0, backwards)
    val k = future.totals("k")
    closeRel(k(0), 1.0, 1e-15, "the contribution at the close itself")
      .and(
        check(
          (1 until 4).forall(p => k(p) == 0.0) && k(0) == 1.0,
          s"a negative offset from period 0 landed somewhere: ${k.mkString(", ")}"
        )
      )
      // A close outside the run contributes nothing at all, rather than being clamped to an end.
      .and(
        check(
          SimulatedFuture
            .empty[String](run)
            .add(9, backwards)
            .totals
            .values
            .forall(_.forall(_ == 0.0)),
          "an event closing outside the run contributed something"
        )
      )
  }

  /** The simulator: one future per sample from the posterior, each with its own derived seed. */
  final private case class Parameters(rate: Double)

  /** Two events per future, their sizes drawn from the sample's own rate: the size and the parts
    * come from one draw, which is what the correlation test needs.
    */
  private val simulator: Simulator[Parameters, String] =
    (parameters, seed) =>
      val program = for
        first <- Rng[Rand].uniform.map(u => parameters.rate * (0.5 + u))
        second <- Rng[Rand].uniform.map(u => parameters.rate * (0.5 + u))
      yield (first, second)
      val (a, b) = program.runA(seed).value
      SimulatedFuture
        .empty[String](run)
        .add(0, rule.contributions(Template(a, List(("x", 1, 0.25), ("y", 2, 0.75))), a))
        .add(1, rule.contributions(Template(b, List(("x", 1, 0.25), ("y", 2, 0.75))), b))
        .ensemble

  pureTest("the amount and its parts come from the same template in the same future") {
    val samples = IArray.tabulate(64)(s => Parameters(10.0 + s.toDouble))
    val ensemble = Simulator.run(simulator, samples, Seed(20261106L))
    // In every future, the parts sum to three quarters of the size — exactly, because they are
    // shares of the same drawn number. Had the simulator drawn the size in one future and the
    // parts in another, this identity would fail while every mean stayed plausible.
    val size = ensemble.values("size")
    val x = ensemble.values("x")
    val y = ensemble.values("y")
    def rowSum(m: IArray[IArray[Double]], s: Int): Double =
      (0 until m(s).length).foldLeft(0.0)((acc, p) => acc + m(s)(p))
    val worst = (0 until ensemble.draws).map { s =>
      val parts = rowSum(x, s) + rowSum(y, s)
      math.abs(parts - rowSum(size, s))
    }.max
    check(ensemble.draws == 64, s"${ensemble.draws} futures")
      .and(check(ensemble.periods == run, "the ensemble is over the wrong run"))
      .and(
        check(
          worst <= 1e-12 * 100.0,
          s"the parts and the size disagree by $worst in some future, so they were not drawn together"
        )
      )
      // And the futures differ from each other: a simulator that ignored its seed would pass the
      // identity above and be useless.
      .and(
        check(
          (0 until ensemble.draws).map(s => rowSum(size, s)).distinct.length == ensemble.draws,
          "two futures came out identical"
        )
      )
  }

  pureTest("a run of futures is the sum of its futures, each in its own row") {
    // The specification says `run` sums by monoid. Stacking each future into its own row and
    // summing with the monoid is the same thing, and that is what this asserts: the efficient
    // version and the literal one agree exactly.
    val samples = IArray.tabulate(8)(s => Parameters(5.0 + s.toDouble))
    val stacked = Simulator.run(simulator, samples, Seed(99L))
    val monoid = Ensemble.commutativeMonoid[String]
    val byHand = (0 until 8).toList
      .map { s =>
        val one = simulator.simulate(samples(s), Seed(99L).fold(s.toLong))
        val placed = one.values.view
          .mapValues(m =>
            IArray.tabulate(8)(row => if row == s then m(0) else IArray.fill(run.count)(0.0))
          )
          .toMap
        Ensemble.of(8, run, placed).toOption.get
      }
      .foldLeft(monoid.empty)(monoid.combine)
    check(Ensemble.eq[String].eqv(stacked, byHand), "stacking and summing by monoid disagree")
  }

  pureTest("the same seed gives the same futures, and a different one does not") {
    val samples = IArray.tabulate(4)(s => Parameters(7.0 + s.toDouble))
    val once = Simulator.run(simulator, samples, Seed(5L))
    val again = Simulator.run(simulator, samples, Seed(5L))
    val other = Simulator.run(simulator, samples, Seed(6L))
    check(Ensemble.eq[String].eqv(once, again), "the same seed gave different futures")
      .and(check(!Ensemble.eq[String].eqv(once, other), "a different seed gave the same futures"))
      // Future i depends on its own derived seed only, so the result does not depend on the order
      // the futures are computed in — which is what makes the simulation parallelisable.
      .and(
        check(
          (0 until 4).forall { s =>
            val alone = simulator.simulate(samples(s), Seed(5L).fold(s.toLong))
            (0 until run.count).forall(p => alone.values("size")(0)(p) == once.values("size")(s)(p))
          },
          "a future computed alone differs from the same future computed in a run"
        )
      )
  }
