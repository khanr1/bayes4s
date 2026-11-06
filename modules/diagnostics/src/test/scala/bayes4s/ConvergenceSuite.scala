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
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** The fourteen values Stan's own tests assert, and the three where ArviZ does not agree.
  *
  * Each of these is a number a user reads to decide whether to believe a fit, and each can be
  * wrong by a few per cent without anything looking odd — which is why the fixture carries Stan's
  * assertions, an independent port's sixteen digits, and ArviZ's answers all three. The last are
  * asserted as **disagreements**: following Stan rather than ArviZ is a decision, and a decision
  * no test mentions can be reversed by accident.
  */
object ConvergenceSuite extends SimpleIOSuite with Checkers with Approx:

  private def statistic(name: String, chains: IArray[IArray[Double]]): Double = name match
    case "rhat_basic_split" => Convergence.rhatBasic(chains)
    case "ess_basic" => Convergence.essBasic(chains)
    case "rhat_rank" => Convergence.rhatRank(chains)
    case "ess_bulk" => Convergence.essBulk(chains)
    case "ess_tail" => Convergence.essTail(chains)
    case "mcse_mean" => Convergence.mcseMean(chains)
    case "mcse_sd" => Convergence.mcseSd(chains)
    case _ => Double.NaN

  pureTest("the 14 values of stan/diagnostics/expected.json, at Stan's own tolerances") {
    Fixtures.diagnosticCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          Fixtures.bernoulliChains(c.column) match
            case Left(message) => failure(message)
            case Right(chains) =>
              val got = statistic(c.statistic, chains)
              check(chains.length == 4 && chains(0).length == 1000, "the fixture's shape changed")
                .and(
                  close(got, c.expected, c.tolerance, s"${c.column}.${c.statistic} (${c.source})")
                )
                // The port carries sixteen digits where Stan's assertions carry six or ten, so it
                // is the sharper of the two comparisons wherever the tolerance is loose.
                .and(
                  closeRel(got, c.port, 1e-10, s"${c.column}.${c.statistic} against the port")
                )
        }
        check(cases.length == 14, s"${cases.length} values, expected 14").and(assertions)
  }

  /** The three statistics where ArviZ 1.x computes something else.
    *
    * Asserted as differences: our value must be **far** from ArviZ's and close to Stan's. If a
    * later change quietly adopted ArviZ's convention — splitting the chains for the basic ESS,
    * using `>` for the upper tail — this is the test that would notice.
    */
  pureTest("we follow Stan where ArviZ differs, and the gap is asserted") {
    Fixtures.diagnosticCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val disagreeing = cases.filter { c =>
          c.arviz.exists(a => math.abs(a - c.expected) > 10.0 * c.tolerance)
        }
        val assertions = forEach(disagreeing) { c =>
          Fixtures.bernoulliChains(c.column) match
            case Left(message) => failure(message)
            case Right(chains) =>
              val got = statistic(c.statistic, chains)
              val arviz = c.arviz.getOrElse(Double.NaN)
              check(
                math.abs(got - c.expected) <= c.tolerance,
                s"${c.column}.${c.statistic}: $got is not Stan's ${c.expected}"
              ).and(
                check(
                  math.abs(got - arviz) > 10.0 * c.tolerance,
                  s"${c.column}.${c.statistic}: $got agrees with ArviZ's $arviz, which means the " +
                    "convention changed"
                )
              )
        }
        check(
          disagreeing.length == 5,
          s"${disagreeing.length} statistics disagree with ArviZ by more than ten tolerances, " +
            "expected 5: basic ESS and tail ESS and mcse_sd for lp__, tail ESS and mcse_sd for theta"
        ).and(assertions)
  }

  /** The two conventions the fixture cannot separate, on samples built to separate them.
    *
    * Stan's own four chains have no draw sitting exactly on the 95th percentile and no
    * autocorrelation small enough to reach the floor on tau, so two of its rules are invisible
    * there. A mutation showed it; these are the cases that pin them.
    */
  pureTest("the upper tail includes the draws equal to the 95th percentile") {
    // Four chains of the integers 0 to 19, repeated: the 95th percentile is exactly 18, which 96
    // draws reach and only 48 exceed.
    val tied = IArray.tabulate(4)(_ => IArray.tabulate(250)(i => (i % 20).toDouble))
    val split = Convergence.splitChains(tied)
    val all = IArray.tabulate(split.length * split(0).length)(k =>
      split(k / split(0).length)(k % split(0).length)
    )
    val high = Quantile.quantile(all, 0.95)
    def indicator(f: Double => Boolean): Double =
      Convergence.ess(IArray.tabulate(split.length) { c =>
        IArray.tabulate(split(c).length)(i => if f(split(c)(i)) then 1.0 else 0.0)
      })
    val inclusive = indicator(_ >= high)
    val exclusive = indicator(_ > high)
    val (_, upper) = Convergence.essTailParts(tied)
    check(high == 18.0, s"the 95th percentile is $high, expected exactly 18")
      .and(
        check(
          math.abs(inclusive - exclusive) > 100.0,
          s"the two conventions give $inclusive and $exclusive, which are too close to tell apart"
        )
      )
      .and(
        check(
          upper == inclusive,
          s"the upper tail is $upper, which is the exclusive convention's $exclusive rather than " +
            s"the inclusive $inclusive"
        )
      )
  }

  pureTest("tau has a floor, which an antithetic chain reaches") {
    // Alternating values with a little wobble: the lag-one autocorrelation is near -1, the
    // estimated integrated time falls below 1/log10(S), and the floor is the only thing stopping
    // the effective size from exceeding the number of draws threefold.
    val wobbly = IArray(
      IArray.tabulate(1000)(i => (if i % 2 == 0 then 1.0 else -1.0) + 0.01 * math.sin(i.toDouble))
    )
    val got = Convergence.ess(wobbly)
    val bound = 1000.0 * math.log10(1000.0)
    check(
      got == bound,
      s"the effective size is $got where the floor on tau puts it at exactly $bound"
    ).and(
      check(
        Convergence.ess(IArray(IArray.tabulate(1000)(i => math.sin(i * 0.05)))) < 1000.0,
        "an ordinary correlated chain must not reach the floor"
      )
    )
  }

  pureTest("the autocovariance of ar1.csv, and its length") {
    (Fixtures.ar1Series, Fixtures.autocovarianceCase) match
      case (Right(series), Right((expected, tolerance, port))) =>
        val got = Convergence.autocovariance(series)
        check(series.length == 1000, s"${series.length} values, expected 1000")
          .and(check(got.length == 1000, s"the result has ${got.length} values, expected 1000"))
          .and(forEach((0 until expected.length).toList) { i =>
            close(got(i), expected(i), tolerance, s"the autocovariance at lag $i")
          })
          .and(forEach((0 until port.length).toList) { i =>
            closeRel(got(i), port(i), 1e-12, s"lag $i against the port")
          })
      case (Left(message), _) => failure(message)
      case (_, Left(message)) => failure(message)
  }

  /** What a diagnostic says about a sample it cannot summarise. */
  pureTest("the edge cases the fixture lists: constant, infinite, and too few draws") {
    Fixtures.diagnosticEdgeCases match
      case Left(message) => failure(message)
      case Right(described) =>
        val constant = Fixtures.bernoulliChains("divergent__")
        val theta = Fixtures.bernoulliChains("theta")
        (constant, theta) match
          case (Right(zeros), Right(chains)) =>
            val infinite = IArray.tabulate(chains.length) { c =>
              IArray.tabulate(chains(c).length)(i =>
                if c == 0 && i == 0 then Double.PositiveInfinity else chains(c)(i)
              )
            }
            val short = IArray.tabulate(chains.length)(c => IArray.tabulate(3)(i => chains(c)(i)))
            def allNaN(name: String, sample: IArray[IArray[Double]], includeRhat: Boolean) =
              val checks = List(
                ("ess_basic", Convergence.essBasic(sample)),
                ("ess_bulk", Convergence.essBulk(sample)),
                ("ess_tail", Convergence.essTail(sample)),
                ("mcse_mean", Convergence.mcseMean(sample)),
                ("mcse_sd", Convergence.mcseSd(sample))
              ) ++ (if includeRhat then List(("rhat_rank", Convergence.rhatRank(sample))) else Nil)
              forEach(checks) { (statistic, value) =>
                check(value.isNaN, s"$name: $statistic gave $value, expected NaN")
              }
            check(described.length == 6, s"${described.length} edge cases described, expected 6")
              .and(allNaN("a constant column", zeros, includeRhat = true))
              .and(allNaN("one infinite value", infinite, includeRhat = true))
              // Three draws: the effective size and the errors have nothing to say; R-hat still
              // does, because it needs only a within and a between variance.
              .and(allNaN("three draws", short, includeRhat = false))
              .and(
                check(
                  Convergence.rhatBasic(zeros).isNaN,
                  s"the basic R-hat of a constant column is ${Convergence.rhatBasic(zeros)}"
                )
              )
          case (Left(message), _) => failure(message)
          case (_, Left(message)) => failure(message)
  }

  /** The rank transform's three structural properties, from the fixture's own list. */
  pureTest("the rank transform keeps the order and is antisymmetric") {
    val filled = IArray.tabulate(2)(c => IArray.tabulate(8)(i => (c * 8 + i + 1).toDouble))
    val transformed = Convergence.rankTransform(filled)
    // Column-major 8 by 2 filled 1..16: r[i][j] = -r[7-i][1-j].
    val antisymmetric = forEach(for
      c <- (0 until 2).toList
      i <- 0 until 8
    yield (c, i)) { (c, i) =>
      closeRel(
        transformed(c)(i),
        -transformed(1 - c)(7 - i),
        1e-12,
        s"the transform at ($c, $i) against its mirror"
      )
    }
    // Two chains, one constant at 1 and one at 2: every pair is a mirror.
    val twoLevels = IArray(IArray.fill(8)(1.0), IArray.fill(8)(2.0))
    val levels = Convergence.rankTransform(twoLevels)
    val mirrored = forEach((0 until 8).toList) { j =>
      closeRel(levels(0)(j), -levels(1)(j), 1e-12, s"the two levels at $j")
    }
    // The largest and smallest draws stay the largest and smallest.
    val arbitrary = IArray(IArray(0.3, -2.0, 7.5, 1.0), IArray(0.0, 4.0, -9.0, 2.0))
    val ranked = Convergence.rankTransform(arbitrary)
    val pooled = for
      c <- (0 until 2).toList
      i <- 0 until 4
    yield ((c, i), arbitrary(c)(i))
    val highest = pooled.maxBy(_._2)._1
    val lowest = pooled.minBy(_._2)._1
    val order = check(
      ranked(highest._1)(highest._2) ==
        (for
          c <- (0 until 2).toList
          i <- 0 until 4
        yield ranked(c)(i)).max,
      "the largest draw is not the largest after the transform"
    ).and(
      check(
        ranked(lowest._1)(lowest._2) ==
          (for
            c <- (0 until 2).toList
            i <- 0 until 4
          yield ranked(c)(i)).min,
        "the smallest draw is not the smallest after the transform"
      )
    )
    antisymmetric.and(mirrored).and(order)
  }

  pureTest("the split is two halves of floor(n/2), the second from floor((n+1)/2)") {
    val odd = IArray(IArray(1.0, 2.0, 3.0, 4.0, 5.0))
    val even = IArray(IArray(1.0, 2.0, 3.0, 4.0))
    val oddSplit = Convergence.splitChains(odd)
    val evenSplit = Convergence.splitChains(even)
    check(oddSplit.length == 2, s"${oddSplit.length} halves")
      .and(check(oddSplit(0).toList == List(1.0, 2.0), s"the first half is ${oddSplit(0).toList}"))
      // Five draws: the middle one is dropped, which is Stan's choice and changes the numbers.
      .and(check(oddSplit(1).toList == List(4.0, 5.0), s"the second half is ${oddSplit(1).toList}"))
      .and(check(evenSplit(0).toList == List(1.0, 2.0), "an even split's first half"))
      .and(check(evenSplit(1).toList == List(3.0, 4.0), "an even split's second half"))
  }

  /** Independent draws: the diagnostics have to say so.
    *
    * R-hat near one and an effective size near the number of draws is the only case where the
    * right answer is known in advance, so it is the only one that can be a property.
    */
  test("independent draws give R-hat near 1 and an effective size near the count") {
    // Ten seeds rather than the default fifty: each one is four thousand draws through several
    // transforms and as many fast Fourier transforms, and the property is about the answer being
    // right, not about how many seeds say so.
    forall.withConfig(checkConfig.withMinimumSuccessful(10))(Gen.choose(1L, 400L)) { seed =>
      val draws = 1000
      val chains = IArray.tabulate(4) { c =>
        val program = cats.Monad[Rand].replicateA(draws, Rng[Rand].normal)
        IArray.from(program.runA(Seed(seed).fold(c.toLong)).value)
      }
      val rhat = Convergence.rhatRank(chains)
      val basic = Convergence.essBasic(chains)
      val bulk = Convergence.essBulk(chains)
      val tail = Convergence.essTail(chains)
      val total = 4.0 * draws
      // The band is measured rather than derived, because the formula that looks right is not.
      //
      // `sqrt(2/total)` — 2.2 % here — is the asymptotic standard error of an effective size when
      // the autocorrelation structure is known. This estimator truncates by Geyer's rule on four
      // chains of a thousand, and the tail version works on quantile-indicator series with about
      // two hundred draws in each tail, so both are noisier than that. Enumerating the whole seed
      // population this property samples from — all 400 of them — gives:
      //
      //   basic  min 0.837  1st percentile 0.874  median 0.983  max 1.090
      //   bulk   min 0.845  1st percentile 0.885  median 0.987  max 1.092
      //   tail   min 0.838  1st percentile 0.866  median 0.971  max 1.048
      //
      // A band of 15 % excludes six of those seeds, so a run of ten failed about one time in
      // seven; CI found seed 134, whose tail effective size is 3397 of 4000. A band of **20 %**
      // contains all 400 for all three statistics with room to spare, and is still nowhere near
      // the factor of two that would make the check vacuous. The measurement is in
      // docs/validation.md.
      def near(name: String, value: Double) =
        check(
          value > 0.80 * total && value < 1.20 * total,
          s"$name is $value for $total independent draws"
        )
      check(rhat < 1.01, s"R-hat is $rhat for independent draws")
        .and(near("the basic effective size", basic))
        .and(near("the bulk effective size", bulk))
        .and(near("the tail effective size", tail))
        .and {
          // The Monte Carlo error of the mean of independent standard Gaussians is 1/sqrt(total).
          val mcse = Convergence.mcseMean(chains)
          closeRel(mcse, 1.0 / math.sqrt(total), 0.1, "the Monte Carlo error of the mean")
        }
    }
  }

  /** The six seeds the measurement found at the edges, pinned so they are checked every run.
    *
    * The property above samples ten seeds out of four hundred, so the extreme ones it is meant to
    * cover are visited about one run in forty. These are the six that a full enumeration found
    * outside the old band — CI found 134 the hard way — and asserting them directly turns a test
    * that failed one run in seven into one that checks the worst cases every time.
    */
  test("the extreme seeds of the independent-draws property stay inside the band") {
    val draws = 1000
    val total = 4.0 * draws
    val extremes = List(31L, 134L, 203L, 249L, 371L, 386L)
    cats.effect.IO {
      val checks = extremes.map { seed =>
        val chains = IArray.tabulate(4) { c =>
          val program = cats.Monad[Rand].replicateA(draws, Rng[Rand].normal)
          IArray.from(program.runA(Seed(seed).fold(c.toLong)).value)
        }
        val basic = Convergence.essBasic(chains)
        val bulk = Convergence.essBulk(chains)
        val tail = Convergence.essTail(chains)
        def inside(name: String, value: Double) =
          check(
            value > 0.80 * total && value < 1.20 * total,
            f"seed $seed: $name is ${value / total}%.3f of $total independent draws"
          )
        inside("the basic effective size", basic)
          .and(inside("the bulk effective size", bulk))
          .and(inside("the tail effective size", tail))
          .and(check(Convergence.rhatRank(chains) < 1.01, s"seed $seed: R-hat is too high"))
      }
      // And the one CI reported, named so that the failure it produced stays readable.
      val cisSeed = IArray.tabulate(4) { c =>
        val program = cats.Monad[Rand].replicateA(draws, Rng[Rand].normal)
        IArray.from(program.runA(Seed(134L).fold(c.toLong)).value)
      }
      checks
        .reduce(_.and(_))
        .and(
          check(
            Convergence.essTail(cisSeed) > 0.80 * total,
            f"seed 134's tail effective size is ${Convergence.essTail(cisSeed)}%.1f"
          )
        )
    }
  }

  /** A chain with a strong autocorrelation must report fewer effective samples than draws, and the
    * AR(1) series is the case where the answer is a formula.
    */
  pureTest("an AR(1) series reports the effective size its autocorrelation implies") {
    Fixtures.ar1Series match
      case Left(message) => failure(message)
      case Right(series) =>
        val chains = IArray(series)
        val basic = Convergence.essBasic(chains)
        // For an AR(1) of coefficient phi the integrated autocorrelation time is (1+phi)/(1-phi),
        // so at phi = 0.8 the effective size is about n / 9.
        val implied = series.length / 9.0
        check(
          basic > 0.4 * implied && basic < 2.5 * implied,
          s"the effective size is $basic where the AR(1) formula implies about $implied"
        ).and(
          check(
            basic < series.length,
            s"a correlated series reported $basic effective samples out of ${series.length}"
          )
        )
  }
