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

package bayes4s.rethinking

import bayes4s.*

/** What a quadratic approximation found: the mode, and the curvature around it. */
final case class Quap(
    names: List[String],
    mode: IArray[Double],
    covariance: Matrix,
    logPosterior: Double
):
  /** The marginal standard deviation of each parameter: the root of the diagonal. */
  def standardDeviation: IArray[Double] =
    IArray.tabulate(names.length)(i => math.sqrt(covariance(i, i)))

  /** The mode and spread by name, which is what `precis` prints. */
  def byName: Map[String, (Double, Double)] =
    names.zipWithIndex.map((name, i) => name -> (mode(i), standardDeviation(i))).toMap

/** The parts of a WAIC, on the deviance scale the book uses. */
final case class Waic(
    waic: Double,
    lppd: Double,
    penalty: Double,
    standardError: Double,
    pointwise: IArray[Double]
)

/** A façade for readers of *Statistical Rethinking*, with the names McElreath's R package uses.
  *
  * It contains **no statistics of its own**: every function here calls the modules below it, and
  * returns the core's own types. What it provides is the vocabulary — `PI`, `HPDI`, `WAIC`,
  * `precis`, `quap` — and the book's defaults, so that a reader can follow the text without
  * translating as they go.
  *
  * **On the R package.** The names and the documented parameterisations are reproduced here; the
  * package's source code is not, has not been read, and must not be. It is GPL, and translating it
  * would make this module a derived work incompatible with the licence this library is published
  * under. Everything below was written from the published documentation and from the papers it
  * cites, and the reference values were computed independently with NumPy and SciPy
  * (docs/design.md). This module is not connected with the package or its author.
  *
  * **The 89% default** is the book's, and lives in this module only: the core always takes the
  * mass explicitly, because a default that differs between two parts of one library is a trap.
  *
  * **Example.** The helpers, with the book's names:
  *
  * ```scala
  * //{
  * import bayes4s.*
  * //}
  * val x = IArray(5.4, 5.6, 4.3, 9.6, 4.9)
  *
  * // Centred and scaled, which is what the book does to every predictor.
  * val z: IArray[Double] = Rethinking.standardize(x)
  *
  * val p: Double = Rethinking.invLogit(0.3)
  * val back: Double = Rethinking.logit(p)
  * val total: Double = Rethinking.logSumExp(IArray(1.0, 2.0, 3.0))
  * ```
  *
  * **Example.** Intervals of a posterior, at the book's default of 89%:
  *
  * ```scala
  * //{
  * import bayes4s.*
  * //}
  * val samples = IArray.tabulate(1000)(i => math.log((i + 0.5) / 1000.0) * -2.0)
  *
  * // The percentile interval, and the narrowest one holding the same mass.
  * val percentile: (Double, Double) = Rethinking.PI(samples)
  * val densest: (Double, Double) = Rethinking.HPDI(samples)
  *
  * // Either can be asked for another mass.
  * val wider: (Double, Double) = Rethinking.PI(samples, prob = 0.97)
  * ```
  */
object Rethinking:

  /** The book's default interval mass, in this module only. */
  val defaultProb: Double = 0.89

  // --- helpers ---------------------------------------------------------------------------------

  /** Centred and scaled by the **sample** standard deviation, as the package documents. */
  def standardize(x: IArray[Double]): IArray[Double] =
    val n = x.length
    if n < 2 then IArray.fill(n)(Double.NaN)
    else
      val mean = x.sum / n
      val variance = x.map(v => (v - mean) * (v - mean)).sum / (n - 1)
      val sd = math.sqrt(variance)
      if !(sd > 0.0) then IArray.fill(n)(Double.NaN)
      else IArray.tabulate(n)(i => (x(i) - mean) / sd)

  def invLogit(x: Double): Double = LogExp.invLogit(x)

  /** The inverse of [[invLogit]]: `log(p / (1 - p))`. */
  def logit(p: Double): Double =
    if p.isNaN || p < 0.0 || p > 1.0 then Double.NaN else math.log(p) - math.log1p(-p)

  def logSumExp(x: IArray[Double]): Double = LogExp.logSumExp(x)

  // --- intervals -------------------------------------------------------------------------------

  /** The percentile interval: `(1 - prob)/2` from each end, at R's type-7 quantile. */
  def PI(samples: IArray[Double], prob: Double = defaultProb): (Double, Double) =
    Intervals.equalTailed(samples, prob)

  /** The narrowest interval holding `prob` of the samples, as `coda::HPDinterval` computes it. */
  def HPDI(samples: IArray[Double], prob: Double = defaultProb): (Double, Double) =
    Intervals.hpd(samples, prob)

  // --- information criteria --------------------------------------------------------------------

  /** The widely applicable information criterion, on the **deviance** scale.
    *
    * `logLik(i)(s)` is the log likelihood of observation `i` under sample `s` from the posterior.
    *
    * {{{
    * lppd    = sum_i log mean_s exp(logLik(i)(s))
    * penalty = sum_i var_s logLik(i)(s)          (the sample variance, n - 1)
    * WAIC    = -2 (lppd - penalty)
    * }}}
    *
    * Deviance means **smaller is better**, which is the opposite of the `elpd` that [[PSIS]] and
    * `Loo` report, and the sign is the mistake worth guarding against. The standard error is
    * `sqrt(n var(pointwise))` over the per-observation terms, as the book computes it.
    */
  def WAIC(logLik: IArray[IArray[Double]]): Waic =
    val observations = logLik.length
    if observations == 0 then Waic(Double.NaN, Double.NaN, Double.NaN, Double.NaN, IArray.empty)
    else
      val draws = logLik(0).length
      val lppdTerms = IArray.tabulate(observations) { i =>
        LogExp.logSumExp(logLik(i)) - math.log(draws.toDouble)
      }
      val penalties = IArray.tabulate(observations) { i =>
        val row = logLik(i)
        val mean = row.sum / draws
        row.map(v => (v - mean) * (v - mean)).sum / (draws - 1)
      }
      val lppd = lppdTerms.sum
      val penalty = penalties.sum
      val pointwise = IArray.tabulate(observations)(i => -2.0 * (lppdTerms(i) - penalties(i)))
      val waic = -2.0 * (lppd - penalty)
      val mean = pointwise.sum / observations
      val variance = pointwise.map(v => (v - mean) * (v - mean)).sum / (observations - 1)
      Waic(waic, lppd, penalty, math.sqrt(observations * variance), pointwise)

  /** Pareto-smoothed importance sampling cross-validation, on the deviance scale: `-2 elpd`. */
  def PSIS(logLik: IArray[IArray[Double]], relativeEss: Double = 1.0): (Double, LooResult) =
    val result = Loo.psis(logLik, relativeEss)
    (-2.0 * result.elpd, result)

  // --- the quadratic approximation ---------------------------------------------------------------

  /** The book's `quap`: a Laplace approximation on the parameters' **own** scale.
    *
    * The mode is found by [[bayes4s.Optimize.lbfgs]] and the covariance is the inverse of the
    * negative Hessian there, from [[bayes4s.Optimize.hessian]]. Both work on the scale the model
    * is written in rather than on an unconstrained one, which is what the book does and what makes
    * the Gamma kernel's answer `(a-1)/b` rather than the mode of its logarithm.
    *
    * The gradient is central differences of `logPosterior`, because a function given as a `Double
    * => Double` carries no derivative. A model written against `Model` has one, and `ulam` is the
    * path for anything large enough for that to matter.
    *
    * `Left` when the mode cannot be found, when the curvature there is not negative definite — a
    * flat or runaway posterior has no quadratic approximation — or when the names do not match the
    * parameters.
    */
  def quap(
      logPosterior: IArray[Double] => Double,
      start: IArray[Double],
      names: List[String]
  ): Either[BayesError, Quap] =
    if names.length != start.length then
      Left(BayesError.DimensionMismatch("Rethinking.quap", start.length, names.length))
    else
      val objective = (x: IArray[Double]) =>
        val value = logPosterior(x)
        if value.isNaN then Double.NaN else -value
      val gradient = (x: IArray[Double]) =>
        IArray.tabulate(x.length) { j =>
          val h = 1e-6 * math.max(1.0, math.abs(x(j)))
          val up = objective(IArray.tabulate(x.length)(i => if i == j then x(i) + h else x(i)))
          val down = objective(IArray.tabulate(x.length)(i => if i == j then x(i) - h else x(i)))
          (up - down) / (2.0 * h)
        }
      for
        found <- Optimize.lbfgs(objective, gradient, start)
        _ <-
          if found.value.isFinite then Right(())
          else Left(BayesError.NumericalOverflow("Rethinking.quap: the posterior has no mode"))
        curvature <- Optimize.hessian(gradient, found.argument)
        covariance <- invertPositiveDefinite(curvature)
      yield Quap(names, found.argument, covariance, -found.value)

  /** The inverse of a positive definite matrix, by Cholesky.
    *
    * The matrix handed in is the Hessian of the **negative** log posterior, so it is positive
    * definite at a maximum and the factorisation failing is the diagnosis: a flat or runaway
    * posterior has no quadratic approximation, and saying so is better than returning one.
    */
  private def invertPositiveDefinite(h: Matrix): Either[BayesError, Matrix] =
    val n = h.rows
    Linalg.cholesky(h).flatMap { factor =>
      val columns = (0 until n).toList.traverseEither { j =>
        val unit = IArray.tabulate(n)(i => if i == j then 1.0 else 0.0)
        Linalg.lowerSolve(factor, unit).flatMap(y => upperSolve(factor, y))
      }
      columns.flatMap(cols => Matrix.of(n, n, IArray.tabulate(n * n)(k => cols(k % n)(k / n))))
    }

  /** The back substitution `L' x = y`, which `Linalg` does not expose on its own. */
  private def upperSolve(l: Cholesky, y: IArray[Double]): Either[BayesError, IArray[Double]] =
    val n = l.dimension
    val solved = (0 until n).reverse.foldLeft(Vector.fill(n)(0.0)) { (x, i) =>
      val inner = (i + 1 until n).foldLeft(0.0)((sum, k) => sum + l.matrix(k, i) * x(k))
      x.updated(i, (y(i) - inner) / l.diagonal(i))
    }
    if solved.exists(v => v.isNaN || v.isInfinite) then
      Left(BayesError.NumericalOverflow("Rethinking.quap: the curvature could not be inverted"))
    else Right(IArray.from(solved))

  extension [A](xs: List[A])
    /** `traverse` over `Either` without pulling cats into a façade that needs nothing else. */
    private def traverseEither[B](f: A => Either[BayesError, B]): Either[BayesError, List[B]] =
      xs.foldRight[Either[BayesError, List[B]]](Right(Nil)) { (a, acc) =>
        for
          rest <- acc
          b <- f(a)
        yield b :: rest
      }

  // --- the book's densities, in its own parameterisations ----------------------------------------

  /** `dgampois(x, mu, scale)`: the gamma-Poisson by mean and **scale**, not by dispersion.
    *
    * The package documents it by `size` and `prob`, with `size = mu / scale` and
    * `prob = 1 / (1 + scale)`. Stan's `neg_binomial_2(mu, phi)` has `phi = size`, so this is
    * `negativeBinomial2` at `phi = mu / scale` — and the variance is then `mu (1 + scale)`, which
    * is the sense in which `scale` is an over-dispersion rather than a dispersion.
    */
  def dgampois(x: Int, mu: Double, scale: Double): Double =
    if scale.isNaN || !(scale > 0.0) then Double.NaN
    else LogMass.negativeBinomial2(x, mu, mu / scale)

  /** `dbetabinom(x, size, prob, theta)`: `a = prob * theta`, `b = (1 - prob) * theta`. */
  def dbetabinom(x: Int, size: Int, prob: Double, theta: Double): Double =
    if prob.isNaN || theta.isNaN || prob < 0.0 || prob > 1.0 || !(theta > 0.0) then Double.NaN
    else LogMass.betaBinomial(x, size, prob * theta, (1.0 - prob) * theta)

  /** `dordlogit(x, phi, a)`: the ordered categorical, with categories counted from one. */
  def dordlogit(x: Int, phi: Double, cuts: IArray[Double]): Double =
    LogDensity.orderedLogistic(x, phi, cuts)

  /** `dzipois(x, p, lambda)`: Poisson with an excess of zeros. */
  def dzipois(x: Int, p: Double, lambda: Double): Double =
    LogMass.zeroInflatedPoisson(x, p, lambda)

  /** `dstudent(x, nu, mu, sigma)`. */
  def dstudent(x: Double, nu: Double, mu: Double, sigma: Double): Double =
    LogDensity.studentT(x, nu, mu, sigma)

  /** `dmvnorm2(x, Mu, sigma, Rho)`: a Gaussian given scales and a correlation matrix.
    *
    * The covariance is `diag(sigma) Rho diag(sigma)`, and the density goes through the Cholesky
    * factor of it rather than through an inverse.
    */
  def dmvnorm2(
      x: IArray[Double],
      mu: IArray[Double],
      sigma: IArray[Double],
      rho: IArray[IArray[Double]]
  ): Double =
    val n = x.length
    if mu.length != n || sigma.length != n || rho.length != n then Double.NaN
    else if sigma.exists(s => s.isNaN || !(s > 0.0)) then Double.NaN
    else
      // Built from the upper triangle and mirrored, because `sigma(i) * rho(i)(j) * sigma(j)` and
      // `sigma(j) * rho(j)(i) * sigma(i)` differ in the last bit — the multiplications happen in a
      // different order — and `Linalg.cholesky` rightly refuses a matrix that is not symmetric.
      val covariance = IArray.tabulate(n) { i =>
        IArray.tabulate(n) { j =>
          val (a, b) = if i <= j then (i, j) else (j, i)
          sigma(a) * rho(a)(b) * sigma(b)
        }
      }
      Matrix
        .ofRows(covariance)
        .flatMap(Linalg.cholesky)
        .fold(
          _ => Double.NaN,
          factor =>
            val lower = IArray.tabulate(n)(i =>
              IArray.tabulate(n)(j => if j <= i then factor.matrix(i, j) else 0.0)
            )
            LogDensity.multiNormalCholesky(x, mu, lower)
        )

  /** `dlkjcorr(x, eta)`: the LKJ density on a correlation matrix. */
  def dlkjcorr(x: IArray[IArray[Double]], eta: Double): Double = LogDensity.lkjCorr(x, eta)
