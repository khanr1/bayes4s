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

/** The two interchangeable pieces a count model is assembled from, tested on their own.
  *
  * `Ar1Trend` says how the rate of creation moves: a level, a trend, and an AR(1) wobble whose
  * first value is drawn from the stationary distribution rather than from zero — otherwise the
  * first month of every simulation is artificially certain.
  *
  * `OrderedLogitDrift` says how long reporting takes: cut points on a logistic scale, drifting
  * with time, which is the ordered-categorical model of the delay. Its probabilities must sum to
  * one exactly, at any drift, which is the one property everything downstream depends on — a
  * thinning by probabilities that sum to 0.999 loses a thousandth of every count.
  */
object ComponentSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val months = 8
  private val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
  private val maxLag = 4

  // --- the rate component -----------------------------------------------------------------------

  pureTest("the rate is the level, the trend and the wobble, and the wobble starts stationary") {
    val rate = Ar1Trend()
    val p = Ar1TrendParams[Double](
      a = IArray(1.5),
      b = IArray(0.5),
      phi = 0.6,
      tau = 0.2,
      eps = IArray.tabulate(months)(m => if m == 0 then 1.0 else 0.0)
    )
    val logRate = rate.logRate[Double](p, 0, t)
    // With every innovation but the first at zero, the wobble is the first one decaying: the
    // stationary scale tau/sqrt(1 - phi^2) at month 0, then multiplied by phi each month.
    val stationary = 0.2 / math.sqrt(1.0 - 0.36)
    val expected =
      IArray.tabulate(months)(m => 1.5 + 0.5 * t(m) + stationary * math.pow(0.6, m.toDouble))
    check(logRate.length == months, s"${logRate.length} months")
      .and(
        (0 until months)
          .map(m => closeRel(logRate(m), expected(m), 1e-14, s"the log rate in month $m"))
          .reduce(_.and(_))
      )
      // No wobble at all leaves the straight line, exactly.
      .and {
        val flat = rate.logRate[Double](p.copy(tau = 0.0, eps = IArray.fill(months)(3.0)), 0, t)
        (0 until months)
          .map(m => closeRel(flat(m), 1.5 + 0.5 * t(m), 1e-14, s"a flat wobble in month $m"))
          .reduce(_.and(_))
      }
  }

  pureTest("the wobble's stationary variance is what the recursion implies") {
    // Drawn from the prior, the first value has variance tau^2/(1 - phi^2) and so does every
    // later one: that is what "starts stationary" means, and it is checked by simulation because
    // the alternative — starting at zero — is the mistake that makes month one too certain.
    val rate = Ar1Trend()
    val replications = 20_000
    val drawn = IArray.tabulate(replications) { i =>
      val p = rate.samplePrior[Rand](1, months).runA(Seed(20261106L).fold(i.toLong)).value
      val wobble = Ar1Trend.wobble(p.phi, p.tau, p.eps)
      (wobble(0), wobble(months - 1), p.phi, p.tau)
    }
    def variance(f: ((Double, Double, Double, Double)) => Double): Double =
      val values = IArray.tabulate(replications)(i => f(drawn(i)))
      val mean = (0 until replications).foldLeft(0.0)((s, i) => s + values(i)) / replications
      (0 until replications).foldLeft(0.0)((s, i) => s + math.pow(values(i) - mean, 2.0)) /
        (replications - 1.0)
    val first = variance(_._1)
    val last = variance(_._2)
    // The two have the same distribution, so their variances agree; the ratio is what matters and
    // the threshold is four standard errors of a variance ratio at this size, about 4 %.
    check(
      math.abs(first / last - 1.0) < 0.06,
      f"the first month's variance is $first%.4f and the last's is $last%.4f"
    )
  }

  pureTest("a projection continues the same recursion, into the time it is given") {
    val rate = Ar1Trend()
    val p = Ar1TrendParams[Double](IArray(2.0), IArray(1.0), 0.5, 0.0, IArray.fill(months)(0.0))
    val futureT = IArray(0.5, 0.6, 0.7)
    val projected = rate.project(p, 0, futureT, Seed(1L))
    // With no innovation scale the wobble is zero everywhere, so the projection is the trend line
    // evaluated at the future times — which is why `project` takes times and not a horizon: a
    // trend cannot be projected without knowing when.
    check(projected.length == 3, s"${projected.length} future months")
      .and(
        (0 until 3)
          .map(h => closeRel(projected(h), 2.0 + 1.0 * futureT(h), 1e-14, s"the projection at $h"))
          .reduce(_.and(_))
      )
      .and(
        check(rate.project(p, 0, IArray.empty[Double], Seed(1L)).isEmpty, "no future, no values")
      )
  }

  pureTest("a projection carries the last wobble forward rather than restarting it") {
    // The wobble at the end of the data is where the future starts: a projection that restarted at
    // zero would throw away what the fit learned about the present, which is the most informative
    // thing it knows.
    val rate = Ar1Trend()
    val high = Ar1TrendParams[Double](
      IArray(0.0),
      IArray(0.0),
      0.9,
      0.1,
      IArray.tabulate(months)(m => if m == months - 1 then 8.0 else 0.0)
    )
    val lastWobble = Ar1Trend.wobble(high.phi, high.tau, high.eps)(months - 1)
    val projected = rate.project(high, 0, IArray(0.0, 0.0, 0.0), Seed(5L))
    check(lastWobble > 0.5, s"the test's last wobble is only $lastWobble")
      // phi = 0.9 and a tiny innovation scale: the first projected month is close to phi times the
      // last wobble, well away from zero.
      .and(
        check(
          projected(0) > 0.5 * lastWobble,
          f"the projection starts at ${projected(0)}%.4f with a last wobble of $lastWobble%.4f"
        )
      )
      .and(check(projected(2) < projected(0), "the wobble is not decaying towards the level"))
  }

  // --- the delay component ----------------------------------------------------------------------

  pureTest("the delay probabilities sum to one, at any drift and any time") {
    val delay = OrderedLogitDrift()
    val p =
      OrderedLogitDriftParams[Double](cut0 = 0.2, increments = IArray(0.8, 0.6, 0.5), beta = 1.5)
    val times = List(-2.0, -0.5, 0.0, 0.3, 4.0)
    val checks = times.map { time =>
      val logPmf = delay.logPmf[Double](p, 0, time)
      val total = (0 until logPmf.length).foldLeft(0.0)((s, k) => s + math.exp(logPmf(k)))
      check(logPmf.length == maxLag + 1, s"${logPmf.length} delays for a maximum lag of $maxLag")
        .and(closeRel(total, 1.0, 1e-14, f"the probabilities at time $time%.1f sum to $total"))
        .and(
          check((0 until logPmf.length).forall(k => logPmf(k) <= 0.0), "a probability above one")
        )
    }
    checks.reduce(_.and(_))
  }

  pureTest("the delay probabilities are the differences of the cumulative logistic") {
    // Written out by hand from the specification: F(k) = invLogit(cut[k] - beta t), p(0) = F(0),
    // p(k) = F(k) - F(k-1), p(K) = 1 - F(K-1). The implementation goes through the ordered
    // logistic instead, which is the same thing computed where it keeps its digits.
    val delay = OrderedLogitDrift()
    val p = OrderedLogitDriftParams[Double](0.2, IArray(0.8, 0.6, 0.5), 1.5)
    val time = 0.3
    val cuts = OrderedLogitDrift.cutPoints[Double](p)
    val f = IArray.tabulate(cuts.length)(k => LogExp.invLogit(cuts(k) - p.beta * time))
    val byHand = IArray.tabulate(cuts.length + 1) { k =>
      if k == 0 then f(0)
      else if k == cuts.length then 1.0 - f(cuts.length - 1)
      else f(k) - f(k - 1)
    }
    val logPmf = delay.logPmf[Double](p, 0, time)
    val expectedCuts = List(0.2, 1.0, 1.6, 2.1)
    check(cuts.length == 4, s"${cuts.length} cut points")
      .and(
        (0 until 4)
          .map(k => closeRel(cuts(k), expectedCuts(k), 1e-14, s"cut point $k"))
          .reduce(_.and(_))
      )
      .and(
        (0 until byHand.length)
          .map(k => closeRel(math.exp(logPmf(k)), byHand(k), 1e-13, s"the probability of delay $k"))
          .reduce(_.and(_))
      )
  }

  pureTest("a positive drift makes reporting slower as time goes on") {
    // The drift is the whole point of the component: reporting that is getting slower is what a
    // nowcast has to notice, and the sign convention has to be stated or it will be wrong.
    val delay = OrderedLogitDrift()
    val p = OrderedLogitDriftParams[Double](0.2, IArray(0.8, 0.6, 0.5), 1.5)
    val early = math.exp(delay.logPmf[Double](p, 0, -1.0).head)
    val late = math.exp(delay.logPmf[Double](p, 0, 1.0).head)
    val noDrift = p.copy(beta = 0.0)
    check(
      late < early,
      f"with a positive drift, same-month reporting went from $early%.4f to $late%.4f"
    )
      .and {
        val a = math.exp(delay.logPmf[Double](noDrift, 0, -1.0).head)
        val b = math.exp(delay.logPmf[Double](noDrift, 0, 1.0).head)
        closeRel(a, b, 1e-15, "without drift, time changes nothing")
      }
      // And the cut points must increase: a decreasing set is not an ordering and gives NaN.
      .and(
        check(
          delay.logPmf[Double](p.copy(increments = IArray(-1.0, 0.6, 0.5)), 0, 0.0).forall(_.isNaN),
          "decreasing cut points gave probabilities"
        )
      )
  }

  pureTest("both components declare the parameters they use, and name them as Stan would") {
    val rate = Ar1Trend().params(1, months)
    val delay = OrderedLogitDrift().params(1, maxLag)
    check(
      // A block of one is named without an index, which is the library's convention everywhere
      // (`Model.values`, the CSV, calibration): with one group, `a` rather than `a.1`.
      rate.names == List("a", "b", "phi", "tau") ++ List.tabulate(months)(m => s"eps.${m + 1}"),
      s"the rate's names are ${rate.names}"
    )
      .and(
        check(
          delay.names == List("cut0") ++ List.tabulate(maxLag - 1)(k => s"inc.${k + 1}") ++ List(
            "beta"
          ),
          s"the delay's names are ${delay.names}"
        )
      )
      .and(check(rate.unconstrainedSize == 4 + months, s"${rate.unconstrainedSize} rate reals"))
      .and(check(delay.unconstrainedSize == maxLag + 1, s"${delay.unconstrainedSize} delay reals"))
  }
