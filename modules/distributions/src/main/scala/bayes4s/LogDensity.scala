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

import scala.annotation.targetName

import bayes4s.syntax.*

/** Log densities written against `Real[A]`: evaluated as values, differentiated by the tape,
  * checked against dual numbers, from one definition.
  *
  * Normalising constants are included, as in Stan's `*_lpdf`. The out-of-domain contract is the
  * one the laws already follow, read on this layer:
  *
  *   - a variate outside the support gives `-Inf`, which is what lets a sampler reject a proposal
  *     instead of crashing on it;
  *   - an **invalid parameter gives `NaN`** rather than an exception or a plausible number. The
  *     sampler treats a `NaN` log density as a rejected, divergent proposal, which is what makes a
  *     badly specified model visible instead of silently wandering. The same argument gives
  *     `Left(InvalidArgument)` from the smart constructor of the corresponding law, and the 361
  *     invalid cases of Stan Math test both layers.
  *
  * "Invalid" is the same rule throughout the library and slightly stricter than Stan's, which
  * rejects an infinite scale for some laws and not for others: here every parameter must be
  * finite, and a scale must in addition be strictly positive.
  *
  * Each function is written as the formula it is, through the operators of `bayes4s.syntax`, and
  * it is the same formula the law layer evaluates in `Double` — which is what carries the Stan
  * Math and mpmath validation of phases 1 and 1b over to the layer the models are written
  * against.
  * **Example.**
  * Every density takes its parameters in Stan's order and returns a log density. Inside a model the
  * scalar is `A`, so constants are lifted:
  *
  * ```scala
  * //{
  * import bayes4s.syntax.*
  * //}
  * // At `Double`, for a quick check at the REPL.
  * val atDouble: Double = LogDensity.normal(1.5, 0.0, 1.0)
  *
  * /** The same calls inside a model's prior, where the scalar is whatever the interpreter wants. */
  * def prior[A: Real](mu: A, sigma: A): A =
  *   LogDensity.normal(mu, 0.0.lift[A], 5.0.lift[A]) +
  *     LogDensity.halfNormal(sigma, 1.0.lift[A])
  *
  * // Outside the support it is -Inf rather than an exception, which is what lets a sampler reject a
  * // proposal instead of crashing on it.
  * val impossible: Double = LogDensity.halfNormal(-1.0, 1.0)
  * ```
  */
object LogDensity:

  // --- the continuous families ----------------------------------------------------------------

  def normal[A: Real](x: A, mu: A, sigma: A): A =
    if !finite(mu) || !scale(sigma) then nan
    else
      val z = (x - mu) / sigma
      -(z * z * 0.5) - log(sigma) - Laws.HalfLog2Pi

  def halfNormal[A: Real](x: A, sigma: A): A =
    if !scale(sigma) then nan
    else if below(x, 0.0) then outside(x)
    else
      val z = x / sigma
      Laws.HalfLog2OverPi.lift[A] - log(sigma) - z * z * 0.5

  def logNormal[A: Real](x: A, mu: A, sigma: A): A =
    if !finite(mu) || !scale(sigma) then nan
    else if !above(x, 0.0) then outside(x)
    else
      val logX = log(x)
      val z = (logX - mu) / sigma
      -(z * z * 0.5) - log(sigma) - Laws.HalfLog2Pi - logX

  /** Shape and **rate**, as in Stan. */
  def gamma[A: Real](x: A, shape: A, rate: A): A =
    if !positive(shape) || !positive(rate) then nan
    else if below(x, 0.0) then outside(x)
    else if value(x) == 0.0 then
      // The density at the boundary is not a limit to be guessed: it diverges below shape 1, is
      // the rate itself at shape 1, and vanishes above.
      if value(shape) < 1.0 then infinite
      else if value(shape) == 1.0 then log(rate)
      else negativeInfinite
    else shape * log(rate) - lgamma(shape) + (shape - 1.0) * log(x) - rate * x

  def beta[A: Real](x: A, alpha: A, beta: A): A =
    if !positive(alpha) || !positive(beta) then nan
    else if below(x, 0.0) || above(x, 1.0) then outside(x)
    else if value(x) == 0.0 then boundary(alpha, alpha, beta)
    else if value(x) == 1.0 then boundary(beta, alpha, beta)
    else (alpha - 1.0) * log(x) + (beta - 1.0) * log1p(-x) - lbeta(alpha, beta)

  private def boundary[A: Real](exponent: A, alpha: A, beta: A): A =
    if value(exponent) < 1.0 then infinite
    else if value(exponent) == 1.0 then -lbeta(alpha, beta)
    else negativeInfinite

  def logistic[A: Real](x: A, mu: A, sigma: A): A =
    if !finite(mu) || !scale(sigma) then nan
    else
      val z = (x - mu) / sigma
      -z - log(sigma) - log1pExp(-z) * 2.0

  def studentT[A: Real](x: A, nu: A, mu: A, sigma: A): A =
    if !positive(nu) || !finite(mu) || !scale(sigma) then nan
    else studentTAt(x, nu, mu, sigma)

  /** The Student-t without its parameter checks, so that the half law is the full law plus `log 2`
    * and not a second copy of the formula.
    */
  private def studentTAt[A: Real](x: A, nu: A, mu: A, sigma: A): A =
    val z = (x - mu) / sigma
    val half = (nu + 1.0) * 0.5
    lgamma(half) - lgamma(nu * 0.5) - log(nu) * 0.5 - Laws.HalfLogPi - log(sigma) -
      half * log1p(z * z / nu)

  def cauchy[A: Real](x: A, mu: A, sigma: A): A =
    if !finite(mu) || !scale(sigma) then nan
    else
      val z = (x - mu) / sigma
      -Laws.LogPi.lift[A] - log(sigma) - log1p(z * z)

  def halfStudentT[A: Real](x: A, nu: A, sigma: A): A =
    if !positive(nu) || !scale(sigma) then nan
    else if below(x, 0.0) then outside(x)
    else Laws.Log2.plusLifted(studentTAt(x, nu, 0.0.lift[A], sigma))

  def halfCauchy[A: Real](x: A, sigma: A): A =
    if !scale(sigma) then nan
    else if below(x, 0.0) then outside(x)
    else
      val z = x / sigma
      Laws.Log2.lift[A] - Laws.LogPi - log(sigma) - log1p(z * z)

  /** Rate parameterisation, as in Stan, where the argument is called `beta`. */
  def exponential[A: Real](x: A, rate: A): A =
    if !positive(rate) then nan
    else if below(x, 0.0) then outside(x)
    else log(rate) - rate * x

  /** Categories from 1 to `cuts.length + 1`, as in Stan and in the book.
    *
    * A middle category is the difference of two logistic functions, and the form that difference
    * is written in is the whole of its numerical content. Subtracting the two probabilities — the
    * way the definition reads — loses 1.3e-5 relative for the second of four categories at a log
    * odds of 30, where both probabilities are within 1e-13 of one. The identity used instead is
    * Stan's `log_inv_logit_diff`, with `a = eta - cuts(k-2)` and `b = eta - cuts(k-1)`:
    *
    * {{{log(invLogit(a) - invLogit(b)) = logInvLogit(a) + log(1 - exp(b - a)) + log1mInvLogit(b)}}}
    *
    * Every term is computed where it keeps its digits, the gap `a - b` is the difference of the two
    * cut points and never of two numbers that nearly agree, and nothing in it can overflow: the
    * mirror identity through `expm1(a - b)` is equally exact and `expm1` overflows above 710, which
    * is how `LogDensityTotalitySuite` found it.
    *
    * Equal cut points are allowed, and leave their category exactly no mass: that is the correct
    * limit and keeps the function total, where Stan refuses them. Cut points that **decrease** are
    * not an ordering at all and give `NaN`, as does a category outside `1..K` getting `-Inf`.
    */
  def orderedLogistic[A: Real](k: Int, eta: A, cuts: IArray[A]): A =
    val categories = cuts.length + 1
    if !finite(eta) || !increasing(cuts) then nan
    else if k < 1 || k > categories then negativeInfinite
    else if k == 1 then log1mInvLogit(eta - cuts(0))
    else if k == categories then logInvLogit(eta - cuts(categories - 2))
    else
      logInvLogit(eta - cuts(k - 2)) + log1mExpNegative(cuts(k - 1) - cuts(k - 2)) +
        log1mInvLogit(eta - cuts(k - 1))

  /** `log(1 - exp(-gap))` for a non-negative gap, by the branch that keeps its digits on each side:
    * `log(-expm1(-gap))` while the gap is small enough for `1 - exp(-gap)` to cancel, `log1p` of
    * a tiny `exp(-gap)` once it is not. A gap of zero gives `-Inf`, an infinite gap gives zero, and
    * nothing in between overflows.
    */
  private def log1mExpNegative[A: Real](gap: A): A =
    if value(gap) < Laws.Log2 then log(-expm1(-gap)) else log1p(-exp(-gap))

  // --- the multivariate families --------------------------------------------------------------

  /** The multivariate Gaussian given the Cholesky factor of its covariance.
    *
    * `l` is a plain lower triangular matrix here rather than the `Cholesky` of `core.linalg`,
    * because in a model its entries are parameters: the invariant cannot be carried by a type that
    * was built once. It is checked instead, and a matrix that is not a factor — an entry above the
    * diagonal, a pivot that is not positive — gives `NaN`.
    */
  def multiNormalCholesky[A: Real](y: IArray[A], mu: IArray[A], l: IArray[IArray[A]]): A =
    val n = mu.length
    if y.length != n || !isFactor(l, n) || !mu.toList.forall(finite) then nan
    else
      val r = Real[A]
      val deviation = r.vectorOf(n)(i => y(i) - mu(i))
      val z = r.lowerSolve(l, deviation)
      val logDeterminant = r.sum(r.vectorOf(n)(i => log(l(i)(i))))
      -(0.5 * n * Laws.Log2Pi).lift[A] - logDeterminant - r.dot(z, z) * 0.5

  /** The LKJ prior on the Cholesky factor of a correlation matrix.
    *
    * The density is the constant plus a weighted sum of the logs of the diagonal: the weight
    * `2(eta - 1) + K - i - 1` is the exponent of the determinant and the Jacobian of `Sigma = L L'`
    * together. The off-diagonal entries do not appear at all, and their gradient is exactly zero.
    */
  def lkjCorrCholesky[A: Real](l: IArray[IArray[A]], eta: A): A =
    val n = l.length
    if n < 2 || !isFactor(l, n) || !positive(eta) then nan
    else
      val r = Real[A]
      val weighted = r.vectorOf(n)(i => log(l(i)(i)) * ((eta - 1.0) * 2.0 + (n - i - 1).toDouble))
      lkjLogConstant(n, eta) + r.sum(weighted)

  /** The log normalising constant, by the product form of Theorem 5 of Lewandowski, Kurowicka and
    * Joe (2009), which needs no separate branch at `eta = 1`.
    */
  private def lkjLogConstant[A: Real](dimension: Int, eta: A): A =
    val r = Real[A]
    val terms = r.vectorOf(dimension - 1) { i =>
      val k = i + 1
      val shape = eta + (dimension - k - 1) / 2.0
      val weight = (dimension - k).toDouble
      ((eta * 2.0 - 2.0 + (dimension - k).toDouble) * weight) * Laws.Log2 +
        lbeta(shape, shape) * weight
    }
    -r.sum(terms)

  // --- the vectorised overloads ---------------------------------------------------------------

  /** One mean per observation, as Stan's vectorised signatures: the form a group effect takes.
    *
    * These delegate to the primitives of `Real[A]`, which a user is not expected to call: the
    * whole vector is one node of the tape, so ten thousand observations cost what one costs.
    */
  def normal[A: Real](ys: IArray[Double], mus: IArray[A], sigma: A): A =
    if ys.length != mus.length || !scale(sigma) then nan
    else Real[A].normalSum(ys, mus, sigma)

  /** A scale of its own for every observation. */
  @targetName("normalVaryingScale")
  def normal[A: Real](ys: IArray[Double], mus: IArray[A], sigmas: IArray[A]): A =
    if ys.length != mus.length || ys.length != sigmas.length then nan
    else if !sigmas.toList.forall(scale) then nan
    else Real[A].normalSum(ys, mus, sigmas)

  /** Several variates around one mean: the non-centred prior of a varying effect. */
  @targetName("normalCommonMean")
  def normal[A: Real](xs: IArray[A], mu: A, sigma: A): A =
    if !finite(mu) || !scale(sigma) then nan else Real[A].normalSum(xs, mu, sigma)

  // --- the domain rules, written once ---------------------------------------------------------

  private def value[A: Real](a: A): Double = Real[A].value(a)
  private def nan[A: Real]: A = Real[A].const(Double.NaN)
  private def infinite[A: Real]: A = Real[A].const(Double.PositiveInfinity)
  private def negativeInfinite[A: Real]: A = Real[A].const(Double.NegativeInfinity)

  /** A variate the law cannot produce has no density; a variate that is not a number is not a
    * variate, and IEEE arithmetic says so rather than this function.
    */
  private def outside[A: Real](x: A): A =
    if value(x).isNaN then nan else negativeInfinite

  private def finite[A: Real](a: A): Boolean =
    val v = value(a)
    !v.isNaN && !v.isInfinite

  private def positive[A: Real](a: A): Boolean = finite(a) && value(a) > 0.0

  /** A scale: positive and finite, uniformly, which is stricter than Stan is. */
  private def scale[A: Real](a: A): Boolean = positive(a)

  private def below[A: Real](x: A, bound: Double): Boolean = value(x) < bound
  private def above[A: Real](x: A, bound: Double): Boolean = value(x) > bound

  private def increasing[A: Real](cuts: IArray[A]): Boolean =
    cuts.length >= 1 && cuts.toList.forall(finite) &&
      (1 until cuts.length).forall(i => value(cuts(i - 1)) <= value(cuts(i)))

  private def isFactor[A: Real](l: IArray[IArray[A]], n: Int): Boolean =
    l.length == n && (0 until n).forall { i =>
      l(i).length == n && (0 until n).forall { j =>
        if j > i then value(l(i)(j)) == 0.0
        else if j == i then positive(l(i)(j))
        else finite(l(i)(j))
      }
    }

/** Log masses written against `Real[A]`, under the same out-of-domain contract as [[LogDensity]].
  *
  * A count is an `Int` and never a parameter: nothing is differentiated with respect to it, and
  * the combinatorial factors it appears in are therefore ordinary constants, computed once in
  * `Double` by the same well-conditioned routines the law layer uses.
  * **Example.**
  * The counts' half of the same idea. Prefer the `…Log` forms when what you have is a linear
  * predictor: they never form a rate or a probability, so nothing is lost to rounding:
  *
  * ```scala
  * //{
  * import bayes4s.syntax.*
  * //}
  * /** A log rate from a linear predictor, read straight by `poissonLog`. */
  * def poissonLikelihood[A: Real](y: IArray[Int], logRate: IArray[A]): A =
  *   Real[A].poissonLogSum(y, logRate)
  *
  * /** The same counts under a gamma-Poisson, which adds a dispersion. */
  * def overDispersed[A: Real](y: IArray[Int], mean: A, dispersion: A): A =
  *   Real[A].sum(
  *     Real[A].vectorOf(y.length)(i => LogMass.negativeBinomial2(y(i), mean, dispersion))
  *   )
  *
  * // A negative count is -Inf, not an error.
  * val impossible: Double = LogMass.poissonLog(-1, 0.0)
  * ```
  */
object LogMass:

  def poisson[A: Real](k: Int, rate: A): A =
    if !Domain.nonNegative(rate) then Domain.nan
    else if k < 0 then Domain.negativeInfinite
    else if Domain.value(rate) == 0.0 then
      // All the mass at zero, which is what a Poisson thinned by a probability of zero means.
      if k == 0 then 0.0.lift[A] else Domain.negativeInfinite
    else if k == 0 then
      // The term `k log(rate)` is not formed rather than multiplied by zero: at a rate of 1e-300
      // it is a large number whose product with zero is zero, and at a rate of zero its
      // derivative would be an infinity times zero. Not forming it gives the exact gradient, -1.
      -rate
    else k.toDouble.timesLifted(log(rate)) - rate - Special.lgamma(k + 1.0)

  def poissonLog[A: Real](k: Int, logRate: A): A =
    if !Domain.finite(logRate) then Domain.nan
    else if k < 0 then Domain.negativeInfinite
    else k.toDouble.timesLifted(logRate) - exp(logRate) - Special.lgamma(k + 1.0)

  /** Mean and dispersion, Stan's `neg_binomial_2`. */
  def negativeBinomial2[A: Real](k: Int, mu: A, phi: A): A =
    if !Domain.positive(mu) || !Domain.positive(phi) then Domain.nan
    else if k < 0 then Domain.negativeInfinite
    else
      // Each ratio becomes a log1p of the *other* side's ratio, which never cancels: at a
      // dispersion of 1e8 the pair (log mu - log(mu + phi)) agrees to six digits and keeps none.
      risingCoefficient(k, phi) - k.toDouble.timesLifted(log1p(phi / mu)) -
        phi * log1p(mu / phi)

  /** Shape and inverse scale, Stan's `neg_binomial`: the Gamma-Poisson predictive. */
  def negativeBinomial[A: Real](k: Int, alpha: A, beta: A): A =
    if !Domain.positive(alpha) || !Domain.positive(beta) then Domain.nan
    else if k < 0 then Domain.negativeInfinite
    else
      risingCoefficient(k, alpha) - alpha * log1p(1.0.lift[A] / beta) -
        k.toDouble.timesLifted(log1p(beta))

  /** log of the rising factorial coefficient C(k + a - 1, k), through `lbeta`.
    *
    * `lgamma(k + a) - lgamma(a) - lgamma(k + 1)` is the same number and loses it: at a = 1e8 the
    * first two terms are near 1.7e9 and their difference is near 18.
    */
  private def risingCoefficient[A: Real](k: Int, a: A): A =
    if k == 0 then 0.0.lift[A]
    else -log(k.toDouble.plusLifted(a)) - lbeta((k + 1).toDouble.lift[A], a)

  def binomial[A: Real](k: Int, n: Int, p: A): A =
    if n < 0 || !Domain.unitInterval(p) then Domain.nan
    else if k < 0 || k > n then Domain.negativeInfinite
    else if Domain.value(p) == 0.0 then if k == 0 then 0.0.lift[A] else Domain.negativeInfinite
    else if Domain.value(p) == 1.0 then if k == n then 0.0.lift[A] else Domain.negativeInfinite
    else
      Laws
        .logBinomialCoefficient(n.toDouble, k.toDouble)
        .plusLifted(
          k.toDouble.timesLifted(log(p))
        ) + (n - k).toDouble.timesLifted(log1p(-p))

  /** Never through the probability: exact at a log odds of 25, where `log(invLogit(25))` has
    * nothing left and SciPy is wrong.
    */
  def bernoulliLogit[A: Real](k: Int, eta: A): A =
    if !Domain.finite(eta) then Domain.nan
    else
      k match
        case 1 => logInvLogit(eta)
        case 0 => log1mInvLogit(eta)
        case _ => Domain.negativeInfinite

  def binomialLogit[A: Real](k: Int, n: Int, eta: A): A =
    if n < 0 || !Domain.finite(eta) then Domain.nan
    else if k < 0 || k > n then Domain.negativeInfinite
    else
      Laws
        .logBinomialCoefficient(n.toDouble, k.toDouble)
        .plusLifted(
          k.toDouble.timesLifted(logInvLogit(eta))
        ) + (n - k).toDouble.timesLifted(log1mInvLogit(eta))

  /** A binomial whose probability is itself Beta: the over-dispersed count. */
  def betaBinomial[A: Real](k: Int, n: Int, alpha: A, beta: A): A =
    if n < 0 || !Domain.positive(alpha) || !Domain.positive(beta) then Domain.nan
    else if k < 0 || k > n then Domain.negativeInfinite
    else
      Laws
        .logBinomialCoefficient(n.toDouble, k.toDouble)
        .plusLifted(
          lbeta(k.toDouble.plusLifted(alpha), (n - k).toDouble.plusLifted(beta))
        ) - lbeta(alpha, beta)

  // --- the vectorised overloads ---------------------------------------------------------------

  def poisson[A: Real](ks: IArray[Int], rates: IArray[A]): A =
    if ks.length != rates.length then Domain.nan else Real[A].poissonSum(ks, rates)

  def poissonLog[A: Real](ks: IArray[Int], logRates: IArray[A]): A =
    if ks.length != logRates.length then Domain.nan else Real[A].poissonLogSum(ks, logRates)

  def bernoulliLogit[A: Real](ks: IArray[Int], etas: IArray[A]): A =
    if ks.length != etas.length then Domain.nan
    else if ks.toList.exists(k => k != 0 && k != 1) then Domain.negativeInfinite
    else Real[A].bernoulliLogitSum(ks, etas)

  def binomialLogit[A: Real](ks: IArray[Int], ns: IArray[Int], etas: IArray[A]): A =
    if ks.length != etas.length || ks.length != ns.length then Domain.nan
    else if (0 until ks.length).exists(i => ns(i) < 0) then Domain.nan
    else if (0 until ks.length).exists(i => ks(i) < 0 || ks(i) > ns(i)) then Domain.negativeInfinite
    else Real[A].binomialLogitSum(ks, ns, etas)

/** The domain rules of the mass functions, which are the density ones plus the two a count needs.
  *
  * They live in their own object because `LogDensity`'s copies are private to it, and a rule
  * written twice is a rule that can disagree with itself.
  */
private[bayes4s] object Domain:
  def value[A: Real](a: A): Double = Real[A].value(a)
  def nan[A: Real]: A = Real[A].const(Double.NaN)
  def negativeInfinite[A: Real]: A = Real[A].const(Double.NegativeInfinity)

  def finite[A: Real](a: A): Boolean =
    val v = value(a)
    !v.isNaN && !v.isInfinite

  def positive[A: Real](a: A): Boolean = finite(a) && value(a) > 0.0
  def nonNegative[A: Real](a: A): Boolean = finite(a) && value(a) >= 0.0
  def unitInterval[A: Real](a: A): Boolean =
    val v = value(a)
    !v.isNaN && v >= 0.0 && v <= 1.0
