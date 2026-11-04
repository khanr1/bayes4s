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

import org.apache.commons.numbers.gamma.Erfc
import org.apache.commons.numbers.gamma.RegularizedGamma

import bayes4s.testkit.Approx
import bayes4s.testkit.MonteCarlo
import cats.Monad
import weaver.SimpleIOSuite

/** The samplers, judged by their moments and by a distribution test, each with its error budget
  * written into the failure message.
  *
  * The cumulative distributions used as oracles come from Apache Commons Numbers, called here in
  * the test. The samplers share no code with them — they draw uniforms and transform them — so a
  * Kolmogorov-Smirnov statistic against those functions is an independent check and not a
  * tautology. Thresholds: 4 standard errors for a moment, which fails by chance about 6 times in
  * 100 000, and 1.95 / sqrt(n) for the Kolmogorov statistic, which is its 0.1 % point.
  */
object RngSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val n = 100_000
  private val seed = Seed(20261104L)

  private def draws[A](program: Rand[A], count: Int = n, from: Seed = seed): List[A] =
    Monad[Rand].replicateA(count, program).runA(from).value

  private def mean(xs: List[Double]): Double = xs.sum / xs.length

  private def variance(xs: List[Double]): Double =
    val m = mean(xs)
    xs.map(x => (x - m) * (x - m)).sum / (xs.length - 1)

  /** The Kolmogorov-Smirnov statistic of a sample against a cumulative distribution. */
  private def ks(xs: List[Double], cdf: Double => Double): Double =
    val sorted = xs.sorted
    val size = sorted.length.toDouble
    sorted.zipWithIndex.map { (x, i) =>
      val f = cdf(x)
      math.max(math.abs(f - i / size), math.abs(f - (i + 1) / size))
    }.max

  private def normalCdf(x: Double): Double = 0.5 * Erfc.value(-x / math.sqrt(2.0))

  pureTest("the same seed gives the same stream and a different seed does not") {
    val a = draws(Rng[Rand].uniform, 100)
    val b = draws(Rng[Rand].uniform, 100)
    val c = draws(Rng[Rand].uniform, 100, Seed(1L))
    check(a == b, "the same seed drifted").and(check(a != c, "two seeds agreed"))
  }

  // Named by the mutation table of the test strategy: an interpreter that returned the seed it
  // was given instead of the next one would still produce numbers in range, and this is the
  // assertion that says so out loud.
  pureTest("two successive draws differ, because the seed advanced") {
    val two = draws(Rng[Rand].uniform, 2)
    val three = draws(Rng[Rand].nextLong, 3)
    check(two(0) != two(1), s"two consecutive uniforms were both ${two(0)}")
      .and(check(three.distinct.length == 3, s"three consecutive longs were $three"))
  }

  pureTest("uniform stays strictly inside (0, 1), so that log(u) is always finite") {
    val us = draws(Rng[Rand].uniform)
    val lowest = us.min
    val highest = us.max
    check(lowest > 0.0, s"the smallest draw was $lowest, which would make log(u) -Inf")
      .and(check(highest < 1.0, s"the largest draw was $highest"))
      .and(check(us.forall(u => !u.isNaN), "a draw was NaN"))
      .and(withinStandardErrors(mean(us), 0.5, math.sqrt(1.0 / 12.0 / n), 4.0, "the uniform mean"))
      .and(
        withinStandardErrors(
          variance(us),
          1.0 / 12.0,
          math.sqrt(4.0 / 45.0 / n) / math.sqrt(5.0),
          4.0,
          "the uniform variance"
        )
      )
      .and(
        check(
          ks(us, u => u) <= 1.95 / math.sqrt(n),
          s"KS = ${ks(us, u => u)}, limit ${1.95 / math.sqrt(n)}"
        )
      )
  }

  pureTest("the normal sampler matches the standard normal") {
    val xs = draws(Rng[Rand].normal)
    val statistic = ks(xs, normalCdf)
    withinStandardErrors(mean(xs), 0.0, 1.0 / math.sqrt(n), 4.0, "the normal mean")
      .and(withinStandardErrors(variance(xs), 1.0, math.sqrt(2.0 / n), 4.0, "the normal variance"))
      .and(
        check(
          statistic <= 1.95 / math.sqrt(n),
          s"Kolmogorov-Smirnov = $statistic against the 0.1 % limit ${1.95 / math.sqrt(n)}"
        )
      )
  }

  pureTest("the gamma sampler matches, on both sides of the shape = 1 boost") {
    forEach(List(0.3, 1.0, 2.5, 50.0)) { shape =>
      val xs = draws(Sampling.gamma[Rand](shape), n, seed.fold(shape.toLong))
      val statistic = ks(xs, x => RegularizedGamma.P.value(shape, x))
      withinStandardErrors(
        mean(xs),
        shape,
        math.sqrt(shape / n),
        4.0,
        s"the mean of gamma($shape)"
      ).and(
        withinStandardErrors(
          variance(xs),
          shape,
          shape * math.sqrt(2.0 / n) * math.sqrt(1.0 + 3.0 / shape),
          4.0,
          s"the variance of gamma($shape)"
        )
      ).and(
        check(
          statistic <= 1.95 / math.sqrt(n),
          s"gamma($shape): Kolmogorov-Smirnov = $statistic against ${1.95 / math.sqrt(n)}"
        )
      )
    }
  }

  pureTest("the Poisson sampler matches, on both sides of the inversion and PTRS boundary") {
    forEach(List(0.1, 1.0, 9.5, 10.5, 100.0, 1e4)) { lambda =>
      val xs = draws(Sampling.poisson[Rand](lambda), n, seed.fold(lambda.toLong)).map(_.toDouble)
      val smallest = xs.min
      withinStandardErrors(
        mean(xs),
        lambda,
        math.sqrt(lambda / n),
        4.0,
        s"the mean of poisson($lambda)"
      ).and(
        withinStandardErrors(
          variance(xs),
          lambda,
          lambda * math.sqrt(2.0 / n) * math.sqrt(1.0 + 1.0 / lambda),
          4.0,
          s"the variance of poisson($lambda)"
        )
      ).and(check(smallest >= 0.0, s"poisson($lambda) produced $smallest"))
    }
  }

  // Chi-squared on the first bins, against a mass function computed here by its own recurrence.
  // The critical value is the 0.1 % point of chi-squared with 9 degrees of freedom, 27.877,
  // from any standard table.
  pureTest("the Poisson counts fall in the right bins") {
    val lambda = 4.0
    val xs = draws(Sampling.poisson[Rand](lambda), n, seed.fold(99L))
    val pmf = List.unfold((0, math.exp(-lambda))) { (k, p) =>
      if k > 9 then None else Some((p, (k + 1, p * lambda / (k + 1))))
    }
    val expected = pmf.map(_ * n)
    val observed = (0 to 9).map(k => xs.count(_ == k).toDouble).toList
    val statistic = observed.zip(expected).map((o, e) => (o - e) * (o - e) / e).sum
    check(
      statistic <= 27.877,
      s"chi-squared = $statistic on 9 degrees of freedom, 0.1 % point 27.877"
    )
  }

  pureTest("the Dirichlet sampler stays on the simplex and has the right component means") {
    val alpha = IArray(0.5, 2.0, 3.5, 1.0)
    val total = alpha.toList.sum
    val samples = draws(Sampling.dirichlet[Rand](alpha), 20_000)
    val offSimplex = samples.map(_.toList.sum).map(s => math.abs(s - 1.0)).max
    val components = forEach(alpha.toList.zipWithIndex) { (a, i) =>
      val xs = samples.map(_(i))
      val expected = a / total
      val sd = math.sqrt(expected * (1.0 - expected) / (total + 1.0))
      withinStandardErrors(
        mean(xs),
        expected,
        sd / math.sqrt(20_000),
        4.0,
        s"the mean of component $i"
      )
    }
    check(offSimplex <= 1e-12, s"the largest departure from the simplex was $offSimplex")
      .and(check(samples.forall(_.forall(_ > 0.0)), "a component was not strictly positive"))
      .and(components)
  }

  pureTest("the categorical sampler follows its probabilities") {
    val probabilities = IArray(0.1, 0.2, 0.05, 0.4, 0.25)
    val cumulative = IArray.from(probabilities.toList.scanLeft(0.0)(_ + _).tail)
    val xs = draws(Sampling.categorical[Rand](cumulative), 50_000)
    val statistic = probabilities.toList.zipWithIndex.map { (p, k) =>
      val observed = xs.count(_ == k).toDouble
      val expected = p * 50_000
      (observed - expected) * (observed - expected) / expected
    }.sum
    check(xs.forall(k => k >= 0 && k < 5), "an index was outside the range")
      .and(
        check(
          statistic <= 18.467,
          s"chi-squared = $statistic on 4 degrees of freedom, 0.1 % point 18.467"
        )
      )
  }

  pureTest("two split streams are uncorrelated") {
    val (left, right) = seed.split
    val count = 50_000
    val a = draws(Rng[Rand].uniform, count, left)
    val b = draws(Rng[Rand].uniform, count, right)
    val ma = mean(a)
    val mb = mean(b)
    val covariance = a.zip(b).map((x, y) => (x - ma) * (y - mb)).sum / count
    val r = covariance / math.sqrt(variance(a) * variance(b))
    val limit = 4.0 / math.sqrt(count)
    check(math.abs(r) <= limit, s"the sample correlation was $r against the limit $limit")
  }
