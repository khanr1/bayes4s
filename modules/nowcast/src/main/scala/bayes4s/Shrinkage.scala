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

/** Empirical Bayes for rates across groups: borrow strength, and say how much was borrowed.
  *
  * `theta[j] ~ Gamma(alpha, alpha)` — mean one, variance `1/alpha` — and `n[j] ~ Poisson(theta[j]
  * e[j])` for an exposure `e[j]`. Then two things are exact: the posterior of a group's rate is
  * `Gamma(alpha + n[j], alpha + e[j])`, so its mean is `(alpha + n[j])/(alpha + e[j])`, and the
  * **shrinkage weight** is `w = e/(alpha + e)` — how much of a group's own evidence survives, from
  * none at all to all of it.
  *
  * `alpha` is estimated by maximising the marginal likelihood, which is a negative binomial: one
  * number for the whole collection, found by Brent on `log alpha`. That is the empirical-Bayes
  * shortcut, and its cost is that the uncertainty about `alpha` is then ignored —
  * [[GammaPoissonHierarchical]] is the same model without the shortcut, and the two part company
  * when there are few groups.
  * **Example.** Empirical Bayes on counts per group: the dispersion by maximum likelihood, and what
  * it implies for each group:
  *
  * ```scala
  * val counts = IArray(260, 170, 130, 75)
  * // The exposure each group was watched for, scaled so that `theta` has mean one.
  * val exposures = IArray.fill(4)(counts.sum.toDouble / counts.length)
  *
  * val fitted: Either[BayesError, GammaPoissonShrinkage.Fit] =
  *   GammaPoissonShrinkage.fit(counts, exposures)
  *
  * // `e / (alpha + e)` is the share of a group's own evidence that survives the shrinkage: near one
  * // for a group with plenty of data, small for a thin one.
  * val ownEvidence: Double = GammaPoissonShrinkage.weight(alpha = 5.0, exposure = 160.0)
  * val thinGroup: Double = GammaPoissonShrinkage.weight(alpha = 5.0, exposure = 4.0)
  *
  * // `GammaPoissonHierarchical` is the same model with the dispersion sampled rather than fixed,
  * // which is what the shortcut leaves out.
  * val full = GammaPoissonHierarchical(counts, exposures)
  * ```
  */
object GammaPoissonShrinkage:

  /** What the fit found: the dispersion, and what it implies group by group. */
  final case class Fit(
      alpha: Double,
      posteriorMean: IArray[Double],
      posteriorSd: IArray[Double],
      weight: IArray[Double]
  )

  /** `w = e / (alpha + e)`: the share of a group's own evidence that survives the shrinkage. */
  def weight(alpha: Double, exposure: Double): Double =
    if alpha.isNaN || exposure.isNaN || alpha < 0.0 || exposure < 0.0 then Double.NaN
    else if alpha + exposure == 0.0 then Double.NaN
    else exposure / (alpha + exposure)

  /** The marginal log likelihood of the counts at a given dispersion.
    *
    * `theta` integrated out leaves a negative binomial with shape `alpha` and rate `alpha/e[j]`,
    * whose mean is the exposure — which is the sense in which `theta` has mean one.
    */
  def marginalLogLikelihood(
      alpha: Double,
      counts: IArray[Int],
      exposures: IArray[Double]
  ): Double =
    if counts.length != exposures.length || counts.length == 0 || !(alpha > 0.0) then Double.NaN
    else
      (0 until counts.length).foldLeft(0.0) { (sum, j) =>
        if !(exposures(j) > 0.0) then Double.NaN
        else sum + LogMass.negativeBinomial(counts(j), alpha, alpha / exposures(j))
      }

  /** `alpha` by maximum marginal likelihood, then every group's posterior.
    *
    * The search is on `log alpha` because the likelihood is far more symmetric there, and the
    * bounds are wide on purpose: a collection whose groups agree exactly wants an enormous
    * `alpha`, and one whose groups disagree wildly wants a tiny one. Hitting a bound is reported
    * rather than hidden, since it means the data wanted more than the bound allows.
    */
  def fit(
      counts: IArray[Int],
      exposures: IArray[Double],
      logAlphaBounds: (Double, Double) = (-12.0, 12.0)
  ): Either[BayesError, Fit] =
    if counts.length == 0 then
      Left(
        BayesError.InvalidArgument("GammaPoissonShrinkage.fit", "counts", 0.0, "at least one group")
      )
    else if counts.length != exposures.length then
      Left(
        BayesError.DimensionMismatch(
          "GammaPoissonShrinkage.fit",
          counts.length,
          exposures.length
        )
      )
    else
      Optimize
        .brentMinimize(
          logAlpha => -marginalLogLikelihood(math.exp(logAlpha), counts, exposures),
          logAlphaBounds._1,
          logAlphaBounds._2
        )
        .map { optimum =>
          val alpha = math.exp(optimum.argument)
          Fit(
            alpha,
            IArray.tabulate(counts.length)(j => (alpha + counts(j)) / (alpha + exposures(j))),
            // The posterior is Gamma(alpha + n, alpha + e), whose standard deviation is
            // sqrt(alpha + n)/(alpha + e).
            IArray
              .tabulate(counts.length)(j => math.sqrt(alpha + counts(j)) / (alpha + exposures(j))),
            IArray.tabulate(counts.length)(j => weight(alpha, exposures(j)))
          )
        }

/** The dispersion as a parameter rather than an estimate. */
final case class GammaPoissonHierarchicalParams[A](alpha: A)

/** The same model as [[GammaPoissonShrinkage]], fitted rather than optimised.
  *
  * `theta[j]` is integrated out analytically — the marginal is a negative binomial — so the only
  * parameter is `alpha`, and sampling it is cheap. What that buys is the uncertainty about `alpha`
  * itself, which the empirical-Bayes shortcut sets aside: with many groups `alpha` is pinned down
  * and the two agree, and with few groups it is barely identified and the full version is
  * honestly wider.
  *
  * A group's rate is then a **mixture** of `Gamma(alpha + n[j], alpha + e[j])` over the samples of
  * `alpha`, which is what [[GammaPoissonHierarchical.theta]] summarises.
  */
final case class GammaPoissonHierarchical(
    counts: IArray[Int],
    exposures: IArray[Double],
    logAlphaPrior: (Double, Double) = (0.0, 2.0),
    bounds: (Double, Double) = (1e-3, 1e6)
) extends Model[GammaPoissonHierarchicalParams]:

  /** `alpha` is **bounded**, and the bound is a numerical statement as much as a modelling one.
    *
    * As `alpha` grows the groups become identical and the marginal becomes Poisson, so the
    * likelihood flattens: nothing in the data distinguishes `alpha = 10^6` from `alpha = 10^18`.
    * With an exposure of about ten per group, anything above a few hundred is already
    * indistinguishable from infinity, so the upper bound does no inferential work.
    *
    * What it does is keep the sampler where the arithmetic is valid. Above `alpha` of about
    * `10^13` the negative binomial's **gradient** with respect to `log alpha` degenerates — the
    * value is still exactly the Poisson limit, but the derivative collapses to a constant where
    * the truth is near zero (docs/validation.md). An unbounded parameter let a chain wander
    * there, where the step-size adaptation then froze it at a step of `10^-32` and the fit
    * reported two chains in different universes. Bounded at `10^6`, seven orders of magnitude
    * below the breakdown, that cannot happen.
    */
  val params: ParamSpec[GammaPoissonHierarchicalParams] =
    ParamSpec
      .bounded("alpha", bounds._1, bounds._2)
      .map[GammaPoissonHierarchicalParams]([A] => (a: A) => GammaPoissonHierarchicalParams(a))

  /** The prior is on **log alpha**, and that is a decision rather than a preference.
    *
    * A dispersion runs over orders of magnitude: groups that agree exactly want an enormous alpha
    * and groups that disagree wildly want a tiny one, so the natural scale is logarithmic and a
    * Gaussian there is weakly informative across the whole range.
    *
    * A half-normal on alpha itself looks equivalent and is not. Its log density is `-alpha²/(2s²)`,
    * so in the unconstrained coordinate `u = log alpha` the target falls like `exp(2u)` — at
    * `u = 49` it is -1e40 with a gradient to match. Energies that large cancel to nothing when
    * differenced, the acceptance statistic comes out `NaN`, and the step-size adaptation drives the
    * step to 1e-50 and freezes the chain there instead of rejecting the point. That is what this
    * model did before the prior was changed, and it is recorded in docs/validation.md.
    *
    * On the log scale the target is a smooth quadratic plus a bounded likelihood, with gradients of
    * order one everywhere, and there is no region to run away to.
    */
  def logPrior[A: Real](p: GammaPoissonHierarchicalParams[A]): A =
    LogDensity.normal(
      Real[A].log(p.alpha),
      logAlphaPrior._1.lift[A],
      logAlphaPrior._2.lift[A]
    )

  def logLikelihood[A: Real](p: GammaPoissonHierarchicalParams[A]): A =
    val r = Real[A]
    r.sum(
      r.vectorOf(counts.length)(j =>
        LogMass.negativeBinomial(counts(j), p.alpha, p.alpha / exposures(j).lift[A])
      )
    )

object GammaPoissonHierarchical:

  /** Each group's rate: the mean and standard deviation of the mixture over `alpha`.
    *
    * The mixture's mean is the average of the conditional means; its variance is the average of the
    * conditional variances **plus** the variance of the conditional means — the second term is the
    * uncertainty about `alpha`, and it is exactly what empirical Bayes drops.
    */
  def theta(
      counts: IArray[Int],
      exposures: IArray[Double],
      alphaDraws: IArray[Double]
  ): IArray[(Double, Double)] =
    val n = alphaDraws.length
    if n == 0 || counts.length != exposures.length then
      IArray.fill(counts.length)((Double.NaN, Double.NaN))
    else
      IArray.tabulate(counts.length) { j =>
        val means =
          IArray.tabulate(n)(s => (alphaDraws(s) + counts(j)) / (alphaDraws(s) + exposures(j)))
        val variances = IArray.tabulate(n) { s =>
          val shape = alphaDraws(s) + counts(j)
          val rate = alphaDraws(s) + exposures(j)
          shape / (rate * rate)
        }
        val mean = (0 until n).foldLeft(0.0)((sum, s) => sum + means(s)) / n
        val withinVariance = (0 until n).foldLeft(0.0)((sum, s) => sum + variances(s)) / n
        val betweenVariance =
          (0 until n).foldLeft(0.0)((sum, s) => sum + math.pow(means(s) - mean, 2.0)) / n
        (mean, math.sqrt(withinVariance + betweenVariance))
      }

/** `log A ~ Gaussian(mu + kappa t + omega log1p(lag), s)`: a size that trends and depends on age.
  *
  * The amounts attached to events are usually right-skewed and often grow over time, and the ones
  * reported late are often not the same size as the ones reported at once. This says all three in
  * one line on the log scale, which is where a multiplicative story belongs.
  */
final case class LogNormalTrendRegressionParams[A](mu: A, kappa: A, omega: A, s: A)

final case class LogNormalTrendRegression(
    logAmount: IArray[Double],
    t: IArray[Double],
    lag: IArray[Double],
    scalePrior: Double = 1.0
) extends Model[LogNormalTrendRegressionParams]:

  val params: ParamSpec[LogNormalTrendRegressionParams] =
    (
      ParamSpec.real("mu"),
      ParamSpec.real("kappa"),
      ParamSpec.real("omega"),
      ParamSpec.positive("s")
    ).mapN[LogNormalTrendRegressionParams]([A] =>
      (mu: A, kappa: A, omega: A, s: A) => LogNormalTrendRegressionParams(mu, kappa, omega, s)
    )

  def logPrior[A: Real](p: LogNormalTrendRegressionParams[A]): A =
    LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
      LogDensity.normal(p.kappa, 0.0.lift[A], 1.0.lift[A]) +
      LogDensity.normal(p.omega, 0.0.lift[A], 1.0.lift[A]) +
      LogDensity.halfNormal(p.s, scalePrior.lift[A])

  def logLikelihood[A: Real](p: LogNormalTrendRegressionParams[A]): A =
    val r = Real[A]
    val means = r.vectorOf(logAmount.length) { i =>
      p.mu + p.kappa * t(i).lift[A] + p.omega * math.log1p(lag(i)).lift[A]
    }
    r.normalSum(logAmount, means, p.s)

object LogNormalTrendRegression:

  /** The expected amount on the natural scale: `exp(mu + ... + s²/2)`.
    *
    * The `s²/2` is the part that is forgotten: exponentiating the fitted mean gives the **median**
    * amount, not the mean, and the gap is a factor of `exp(s²/2)` — 13 % at `s = 0.5`. Which one a
    * forecast wants depends on whether it is adding amounts up.
    */
  def meanAmount(p: LogNormalTrendRegressionParams[Double], t: Double, lag: Double): Double =
    math.exp(p.mu + p.kappa * t + p.omega * math.log1p(lag) + p.s * p.s / 2.0)

  def medianAmount(p: LogNormalTrendRegressionParams[Double], t: Double, lag: Double): Double =
    math.exp(p.mu + p.kappa * t + p.omega * math.log1p(lag))
