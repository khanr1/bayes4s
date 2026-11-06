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
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** Borrowing strength across groups, by the shortcut and by the full fit.
  *
  * The shortcut — empirical Bayes — estimates the dispersion once by maximum marginal likelihood
  * and treats it as known. The full version samples it. With many groups the dispersion is pinned
  * down and the two agree; with few it is barely identified, and the full version is honestly
  * wider while the shortcut reports an interval it has not earned. Both halves are asserted,
  * because an implementation that always agreed would be hiding the very thing that distinguishes
  * them.
  */
object ShrinkageSuite extends SimpleIOSuite with Approx:

  test("the shrinkage weight is the one the worked examples publish") {
    Fixtures.shrinkageWeights match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(cases) =>
        cats.effect.IO {
          val checks = cases.map { c =>
            closeRel(
              GammaPoissonShrinkage.weight(c.alpha, c.exposure),
              c.weight,
              1e-15,
              f"w at alpha ${c.alpha}%.1f and exposure ${c.exposure}%.1f"
            )
          }
          check(cases.length == 8, s"${cases.length} cases in the fixture")
            .and(checks.reduce(_.and(_)))
            // The two ends: no exposure means no evidence of one's own, and an enormous exposure
            // means the group speaks entirely for itself.
            .and(check(GammaPoissonShrinkage.weight(5.0, 0.0) == 0.0, "no exposure kept weight"))
            .and(
              check(
                GammaPoissonShrinkage.weight(5.0, 1e9) > 0.999999,
                "a huge exposure still borrowed"
              )
            )
            .and(
              check(GammaPoissonShrinkage.weight(-1.0, 5.0).isNaN, "a negative alpha gave a weight")
            )
        }
  }

  pureTest("the fitted dispersion is the maximum of the marginal likelihood on a fine grid") {
    // The criterion of 8.2. Brent searches on log alpha; the grid is 40 001 points over the same
    // interval, which resolves log alpha to 6e-4 — so the two agree on the likelihood to far
    // better than the likelihood's own curvature, and on alpha itself to a part in a thousand.
    val counts = IArray(7, 11, 5, 9, 14, 3, 8, 10, 6, 12)
    val exposures = IArray(8.0, 10.0, 6.0, 9.0, 12.0, 4.0, 8.0, 11.0, 7.0, 10.0)
    GammaPoissonShrinkage.fit(counts, exposures) match
      case Left(error) => failure(error.message)
      case Right(fit) =>
        val points = 40_001
        val grid = IArray.tabulate(points)(i => -12.0 + 24.0 * i / (points - 1.0))
        val values = IArray.tabulate(points) { i =>
          GammaPoissonShrinkage.marginalLogLikelihood(math.exp(grid(i)), counts, exposures)
        }
        val best = (0 until points).foldLeft(0) { (bestSoFar, i) =>
          if values(i) > values(bestSoFar) then i else bestSoFar
        }
        val gridAlpha = math.exp(grid(best))
        val atFit = GammaPoissonShrinkage.marginalLogLikelihood(fit.alpha, counts, exposures)
        check(fit.alpha > 0.0, s"the fitted alpha is ${fit.alpha}")
          .and(closeRel(fit.alpha, gridAlpha, 2e-3, "the fitted alpha against the grid's"))
          // The real claim: Brent found a point at least as good as the grid's best.
          .and(
            check(
              atFit >= values(best) - 1e-9,
              f"the fit's likelihood is $atFit%.9f against the grid's ${values(best)}%.9f"
            )
          )
  }

  pureTest("a group's posterior is the conjugate one, and its weight says how much was borrowed") {
    val counts = IArray(2, 40)
    val exposures = IArray(2.0, 40.0)
    GammaPoissonShrinkage.fit(counts, exposures) match
      case Left(error) => failure(error.message)
      case Right(fit) =>
        val alpha = fit.alpha
        // Gamma(alpha + n, alpha + e): mean (alpha + n)/(alpha + e), sd sqrt(alpha + n)/(alpha + e).
        val checks = (0 until 2).map { j =>
          closeRel(
            fit.posteriorMean(j),
            (alpha + counts(j)) / (alpha + exposures(j)),
            1e-14,
            s"the posterior mean of group $j"
          ).and(
            closeRel(
              fit.posteriorSd(j),
              math.sqrt(alpha + counts(j)) / (alpha + exposures(j)),
              1e-14,
              s"the posterior sd of group $j"
            )
          )
        }
        checks
          .reduce(_.and(_))
          // The small group borrows more than the large one: that is the entire point.
          .and(
            check(
              fit.weight(0) < fit.weight(1),
              f"the small group kept ${fit.weight(0)}%.3f of its evidence and the large one ${fit.weight(1)}%.3f"
            )
          )
  }

  pureTest("the fit refuses what it cannot estimate") {
    check(
      GammaPoissonShrinkage.fit(IArray.empty[Int], IArray.empty[Double]).isLeft,
      "no groups fitted"
    )
      .and(
        check(
          GammaPoissonShrinkage.fit(IArray(1, 2), IArray(1.0)).isLeft,
          "mismatched lengths were accepted"
        )
      )
      .and(
        check(
          GammaPoissonShrinkage.marginalLogLikelihood(0.0, IArray(1), IArray(1.0)).isNaN,
          "a dispersion of zero has a likelihood"
        )
      )
      .and(
        check(
          GammaPoissonShrinkage.marginalLogLikelihood(1.0, IArray(1), IArray(0.0)).isNaN,
          "an exposure of zero has a likelihood"
        )
      )
  }

  pureTest("a group's rate is the mixture over the dispersion, by the law of total variance") {
    // This is the term that *is* the difference between the two methods, so it gets an exact test
    // rather than a statistical one. Over a set of dispersion values, a group's rate is a mixture
    // of Gamma(alpha + n, alpha + e): its variance is the mean of the conditional variances plus
    // the variance of the conditional means, and the second term is precisely the uncertainty
    // about the dispersion that empirical Bayes throws away.
    val counts = IArray(3, 20)
    val exposures = IArray(4.0, 25.0)
    val alphaDraws = IArray(1.0, 4.0, 10.0, 40.0)
    val summarised = GammaPoissonHierarchical.theta(counts, exposures, alphaDraws)
    val checks = (0 until 2).map { j =>
      val means = alphaDraws.toList.map(a => (a + counts(j)) / (a + exposures(j)))
      val variances = alphaDraws.toList.map { a =>
        val shape = a + counts(j)
        val rate = a + exposures(j)
        shape / (rate * rate)
      }
      val n = alphaDraws.length
      val mean = means.sum / n
      val within = variances.sum / n
      val between = means.map(m => (m - mean) * (m - mean)).sum / n
      closeRel(summarised(j)._1, mean, 1e-14, s"group $j's mean")
        .and(closeRel(summarised(j)._2, math.sqrt(within + between), 1e-14, s"group $j's sd"))
        // And the between term is not negligible here, so a version that dropped it would give a
        // visibly smaller answer rather than the same one to fourteen digits.
        .and(
          check(
            math.sqrt(within + between) > 1.02 * math.sqrt(within),
            f"group $j's between-draw term contributes only " +
              f"${math.sqrt(within + between) / math.sqrt(within)}%.4f"
          )
        )
    }
    checks
      .reduce(_.and(_))
      .and(
        check(
          GammaPoissonHierarchical.theta(counts, exposures, IArray.empty).forall(_._1.isNaN),
          "no dispersion draws still gave a rate"
        )
      )
  }

  /** The criterion of 8.2: the two agree with many groups and part company with few.
    *
    * Measured over six seeds at each of 200, 20 and 4 groups before the thresholds below were
    * written down (docs/validation.md):
    *
    * {{{
    * groups  width ratio      worst mean gap   alpha's relative spread   alphaHat
    * 200     0.999 to 1.000   0.9% to 1.6%     0.125 to 0.139            3.3 to 4.6
    * 20      1.001 to 1.004   4.2% to 9.9%     0.396 to 1.954            3.1 to 5.8
    * 4       0.852 to 1.148   12% to 58%       4.261 to 12.577           1.3 to 10.8
    * }}}
    *
    * What that says, and what this test asserts, is that with many groups the shortcut loses
    * nothing — the dispersion is pinned down, so treating it as known is harmless — and with four
    * groups it is not entitled to its own answer: the dispersion's posterior is thirty to ninety
    * times wider in relative terms, and the two methods' per-group rates differ by tens of per
    * cent.
    *
    * What this test deliberately does **not** assert is that the full fit is always the wider of
    * the two. It is not, and the reason is worth knowing: the shortcut's width is set by wherever
    * its point estimate landed, and with four groups that estimate ranged from 1.3 to 10.8 across
    * seeds — so the shortcut can come out either too narrow or too wide. "More uncertain" is a
    * statement about the dispersion, not a guarantee about every group's interval.
    */
  test("the full fit agrees with the shortcut at two hundred groups and parts from it at four") {
    val settings = RunSettings(chains = 4, warmup = 500, samples = 1000)

    /** Counts simulated from the model itself: theta[j] ~ Gamma(alpha, alpha), n[j] ~ Poisson. */
    def simulate(groups: Int, alpha: Double, seed: Long): (IArray[Int], IArray[Double]) =
      val program = (0 until groups).toList.traverse { j =>
        val exposure = 10.0 + (j % 7).toDouble
        for
          theta <- Sampling.gamma[Rand](alpha).map(_ / alpha)
          count <- Sampling.poisson[Rand](theta * exposure)
        yield (count, exposure)
      }
      val drawn = program.runA(Seed(seed)).value
      (IArray.from(drawn.map(_._1)), IArray.from(drawn.map(_._2)))

    final case class Comparison(
        widthRatio: Double,
        worstMeanGap: Double,
        alphaRelativeSpread: Double,
        alphaHat: Double
    )

    def compare(groups: Int, seed: Long): Either[BayesError, Comparison] =
      val (counts, exposures) = simulate(groups, 4.0, 100L * seed)
      for
        shortcut <- GammaPoissonShrinkage.fit(counts, exposures)
        model = GammaPoissonHierarchical(counts, exposures)
        chains <- Mcmc.runNuts(model, settings).runA(Seed(7L * seed)).value
      yield
        val alphaDraws =
          IArray.from(chains.flatMap(_.draws.map(q => Model.constrain(model, q).alpha)))
        val n = alphaDraws.length
        val alphaMean = (0 until n).foldLeft(0.0)((s, i) => s + alphaDraws(i)) / n
        val alphaSd = math.sqrt(
          (0 until n).foldLeft(0.0)((s, i) => s + math.pow(alphaDraws(i) - alphaMean, 2.0)) /
            (n - 1.0)
        )
        val full = GammaPoissonHierarchical.theta(counts, exposures, alphaDraws)
        val ratios = (0 until groups).map(j => full(j)._2 / shortcut.posteriorSd(j))
        val gaps = (0 until groups)
          .map(j => math.abs(full(j)._1 - shortcut.posteriorMean(j)) / shortcut.posteriorMean(j))
        Comparison(ratios.sum / groups, gaps.max, alphaSd / alphaMean, shortcut.alpha)

    cats.effect.IO {
      (compare(200, 1L), compare(4, 1L)) match
        case (Left(error), _) => failure(s"the two hundred group fit: ${error.message}")
        case (_, Left(error)) => failure(s"the four group fit: ${error.message}")
        case (Right(many), Right(few)) =>
          check(
            many.widthRatio > 0.95 && many.widthRatio < 1.05,
            f"at 200 groups the full fit is ${many.widthRatio}%.4f times as wide as the shortcut"
          )
            .and(
              check(
                many.worstMeanGap < 0.05,
                f"at 200 groups the worst group's mean differs by ${many.worstMeanGap}%.4f"
              )
            )
            .and(
              check(
                many.alphaRelativeSpread < 0.25,
                f"at 200 groups the dispersion's relative spread is ${many.alphaRelativeSpread}%.3f"
              )
            )
            // Four groups: the dispersion is barely identified, and the shortcut's confidence in
            // it is not earned.
            .and(
              check(
                few.alphaRelativeSpread > 2.0,
                f"at four groups the dispersion's relative spread is only " +
                  f"${few.alphaRelativeSpread}%.3f"
              )
            )
            .and(
              check(
                few.alphaRelativeSpread > 10.0 * many.alphaRelativeSpread,
                f"the dispersion's relative spread is ${few.alphaRelativeSpread}%.3f at four " +
                  f"groups against ${many.alphaRelativeSpread}%.3f at two hundred"
              )
            )
            .and(
              check(
                few.worstMeanGap > 0.10,
                f"at four groups the two methods' worst group differs by only " +
                  f"${few.worstMeanGap}%.4f"
              )
            )
    }
  }
