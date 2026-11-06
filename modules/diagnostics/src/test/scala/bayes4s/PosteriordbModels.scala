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
import bayes4s.testkit.Fixtures
import io.circe.Json

/** The nine posteriordb reference posteriors, ported by hand from their Stan source.
  *
  * Each one is a line-by-line translation of the `model.stan` beside its data, and the translation
  * is the thing being tested: the reference summaries were produced by Stan itself, so a
  * disagreement is either a bug in this library or a mistranslation here, and both are worth
  * finding.
  *
  * Two translations are not line by line and say so where they are written: `garch11` has a bound
  * that depends on another parameter, which `Constraint` cannot express, and `gp_pois_regr` needs
  * the Cholesky factor of a parameter-dependent matrix, which the specification deliberately
  * leaves out of `Real[A]`. Both are handled in the model rather than in the library.
  *
  * Priors that Stan writes as `~ normal(0, s)` on a positively constrained parameter are written
  * here as `halfNormal`, and `~ cauchy(0, s)` as `halfCauchy`. Stan does not normalise the
  * truncation, so its target differs from these by a constant per statement — which moves `lp__`
  * and leaves every posterior untouched, and the reference summaries carry no `lp__`.
  */
object PosteriordbModels:

  private def data(posterior: String): Either[String, Json] =
    Fixtures.posteriordbData(posterior)

  private def doubles(json: Json, field: String): Either[String, IArray[Double]] =
    json.hcursor.get[List[Double]](field).left.map(_.getMessage).map(IArray.from)

  private def ints(json: Json, field: String): Either[String, IArray[Int]] =
    json.hcursor.get[List[Int]](field).left.map(_.getMessage).map(IArray.from)

  private def int(json: Json, field: String): Either[String, Int] =
    json.hcursor.get[Int](field).left.map(_.getMessage)

  private def double(json: Json, field: String): Either[String, Double] =
    json.hcursor.get[Double](field).left.map(_.getMessage)

  // --- arK: an autoregression of order K ------------------------------------------------------

  final case class ArK[A](alpha: A, beta: IArray[A], sigma: A)

  /** `y[t] ~ normal(alpha + sum_k beta[k] y[t-k], sigma)` for `t > K`. */
  def arK: Either[String, Model[ArK]] =
    for
      json <- data("arK-arK")
      k <- int(json, "K")
      t <- int(json, "T")
      y <- doubles(json, "y")
    yield
      val rows = (k until t).toList
      val observed = IArray.from(rows.map(i => y(i)))
      val lagged = IArray.from(rows.map(i => IArray.tabulate(k)(j => y(i - 1 - j))))
      new Model[ArK]:
        val params: ParamSpec[ArK] =
          (ParamSpec.real("alpha"), ParamSpec.vector("beta", k), ParamSpec.positive("sigma"))
            .mapN[ArK]([A] => (a: A, b: IArray[A], s: A) => ArK(a, b, s))

        def logPrior[A: Real](p: ArK[A]): A =
          LogDensity.normal(p.alpha, 0.0.lift[A], 10.0.lift[A]) +
            LogDensity.normal(p.beta, 0.0.lift[A], 10.0.lift[A]) +
            LogDensity.halfCauchy(p.sigma, 2.5.lift[A])

        def logLikelihood[A: Real](p: ArK[A]): A =
          val r = Real[A]
          val means = r.vectorOf(observed.length) { i =>
            (0 until k).foldLeft(p.alpha)((sum, j) => sum + p.beta(j) * lagged(i)(j))
          }
          r.normalSum(observed, means, p.sigma)

  // --- arma11: the error recursion ------------------------------------------------------------

  final case class Arma[A](mu: A, phi: A, theta: A, sigma: A)

  /** `nu[t] = mu + phi y[t-1] + theta err[t-1]`, `err[t] = y[t] - nu[t]`, `err ~ normal(0, sigma)`. */
  def arma11: Either[String, Model[Arma]] =
    for
      json <- data("arma-arma11")
      t <- int(json, "T")
      y <- doubles(json, "y")
    yield new Model[Arma]:
      val params: ParamSpec[Arma] =
        (
          ParamSpec.real("mu"),
          ParamSpec.real("phi"),
          ParamSpec.real("theta"),
          ParamSpec.positive("sigma")
        ).mapN[Arma]([A] => (m: A, p: A, th: A, s: A) => Arma(m, p, th, s))

      def logPrior[A: Real](p: Arma[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
          LogDensity.normal(p.phi, 0.0.lift[A], 2.0.lift[A]) +
          LogDensity.normal(p.theta, 0.0.lift[A], 2.0.lift[A]) +
          LogDensity.halfCauchy(p.sigma, 2.5.lift[A])

      def logLikelihood[A: Real](p: Arma[A]): A =
        val r = Real[A]
        // The recursion, exactly as the Stan program writes it: err[0] is taken as zero.
        val errors = (1 until t).foldLeft(Vector(y(0).lift[A] - (p.mu + p.phi * p.mu))) {
          (acc, i) =>
            val nu = p.mu + p.phi * y(i - 1).lift[A] + p.theta * acc(i - 1)
            acc :+ (y(i).lift[A] - nu)
        }
        r.normalSum(r.vectorOf(t)(i => errors(i)), 0.0.lift[A], p.sigma)

  // --- garch11: a bound that depends on another parameter -------------------------------------

  final case class Garch[A](mu: A, alpha0: A, alpha1: A, beta1Raw: A)

  /** `sigma[t] = sqrt(alpha0 + alpha1 (y[t-1] - mu)^2 + beta1 sigma[t-1]^2)`.
    *
    * Stan declares `real<lower=0, upper=(1 - alpha1)> beta1`, a bound that depends on another
    * parameter, and `Constraint` has no case for that. The translation declares `beta1Raw` on the
    * unit interval and sets `beta1 = (1 - alpha1) beta1Raw`, which is **exactly** Stan's own
    * transform for an upper bound of `1 - alpha1`; what Stan's transform adds to the target and
    * `UnitInterval` does not is the `log(1 - alpha1)` term, which is added here by hand. The
    * posterior is therefore the same one, and docs/validation.md records the gap.
    */
  def garch11: Either[String, Model[Garch]] =
    for
      json <- data("garch-garch11")
      t <- int(json, "T")
      y <- doubles(json, "y")
      sigma1 <- double(json, "sigma1")
    yield new Model[Garch]:
      val params: ParamSpec[Garch] =
        (
          ParamSpec.real("mu"),
          ParamSpec.positive("alpha0"),
          ParamSpec.unitInterval("alpha1"),
          ParamSpec.unitInterval("beta1Raw")
        ).mapN[Garch]([A] => (m: A, a0: A, a1: A, b: A) => Garch(m, a0, a1, b))

      /** The Jacobian of the dependent bound, which `UnitInterval` cannot know about. */
      def logPrior[A: Real](p: Garch[A]): A =
        log(1.0.lift[A] - p.alpha1)

      /** `beta1` is what the reference summary reports, and it is not a parameter here. */
      override def derived(p: Garch[Double]): Map[String, IArray[Double]] =
        Map("beta1" -> IArray((1.0 - p.alpha1) * p.beta1Raw))

      def logLikelihood[A: Real](p: Garch[A]): A =
        val r = Real[A]
        val beta1 = (1.0.lift[A] - p.alpha1) * p.beta1Raw
        val scales = (1 until t).foldLeft(Vector(sigma1.lift[A])) { (acc, i) =>
          val shock = y(i - 1).lift[A] - p.mu
          acc :+ sqrt(p.alpha0 + p.alpha1 * shock * shock + beta1 * acc(i - 1) * acc(i - 1))
        }
        r.normalSum(y, r.filled(t)(p.mu), r.vectorOf(t)(i => scales(i)))

  // --- two plain regressions ------------------------------------------------------------------

  final case class Regression[A](beta: IArray[A], sigma: A)

  private def regression(
      posterior: String,
      response: Json => Either[String, IArray[Double]],
      predictor: String,
      prior: Boolean
  ): Either[String, Model[Regression]] =
    for
      json <- data(posterior)
      y <- response(json)
      x <- doubles(json, predictor)
    yield new Model[Regression]:
      val params: ParamSpec[Regression] =
        (ParamSpec.vector("beta", 2), ParamSpec.positive("sigma"))
          .mapN[Regression]([A] => (b: IArray[A], s: A) => Regression(b, s))

      def logPrior[A: Real](p: Regression[A]): A =
        if prior then LogDensity.halfCauchy(p.sigma, 2.5.lift[A]) else Real[A].const(0.0)

      def logLikelihood[A: Real](p: Regression[A]): A =
        val r = Real[A]
        val means = r.vectorOf(y.length)(i => p.beta(0) + p.beta(1) * x(i).lift[A])
        r.normalSum(y, means, p.sigma)

  /** `kid_score ~ normal(beta[1] + beta[2] mom_iq, sigma)`, `sigma ~ cauchy(0, 2.5)`. */
  def kidiq: Either[String, Model[Regression]] =
    regression("kidiq-kidscore_momiq", doubles(_, "kid_score"), "mom_iq", prior = true)

  /** `log(earn) ~ normal(beta[1] + beta[2] height, sigma)`, with no prior at all. */
  def earnings: Either[String, Model[Regression]] =
    regression(
      "earnings-logearn_height",
      json => doubles(json, "earn").map(e => IArray.tabulate(e.length)(i => math.log(e(i)))),
      "height",
      prior = false
    )

  // --- dogs: a logistic regression on counts of past outcomes ---------------------------------

  final case class Dogs[A](beta: IArray[A])

  /** `y[j, t] ~ bernoulli_logit(beta[1] + beta[2] n_avoid + beta[3] n_shock)`.
    *
    * Stan calls the two counts transformed parameters; they depend only on the data, so here they
    * are data, computed once.
    */
  def dogs: Either[String, Model[Dogs]] =
    for
      json <- data("dogs-dogs")
      nDogs <- int(json, "n_dogs")
      nTrials <- int(json, "n_trials")
      rows <- json.hcursor.get[List[List[Int]]]("y").left.map(_.getMessage)
    yield
      val cells = for
        j <- (0 until nDogs).toList
        t <- 0 until nTrials
      yield (j, t)
      val outcomes = IArray.from(cells.map((j, t) => rows(j)(t)))
      val avoided =
        IArray.from(cells.map((j, t) => (0 until t).count(s => rows(j)(s) == 0).toDouble))
      val shocked =
        IArray.from(cells.map((j, t) => (0 until t).count(s => rows(j)(s) == 1).toDouble))
      new Model[Dogs]:
        val params: ParamSpec[Dogs] =
          ParamSpec.vector("beta", 3).map[Dogs]([A] => (b: IArray[A]) => Dogs(b))

        def logPrior[A: Real](p: Dogs[A]): A =
          LogDensity.normal(p.beta, 0.0.lift[A], 100.0.lift[A])

        def logLikelihood[A: Real](p: Dogs[A]): A =
          val r = Real[A]
          val logits = r.vectorOf(outcomes.length) { i =>
            p.beta(0) + p.beta(1) * avoided(i).lift[A] + p.beta(2) * shocked(i).lift[A]
          }
          r.bernoulliLogitSum(outcomes, logits)

  // --- low_dim_gauss_mix: a two-component mixture with ordered means --------------------------

  final case class Mixture[A](mu: IArray[A], sigma: IArray[A], theta: A)

  /** `target += log_mix(theta, normal(y | mu[1], sigma[1]), normal(y | mu[2], sigma[2]))`. */
  def gaussianMixture: Either[String, Model[Mixture]] =
    for
      json <- data("low_dim_gauss_mix-low_dim_gauss_mix")
      y <- doubles(json, "y")
    yield new Model[Mixture]:
      val params: ParamSpec[Mixture] =
        (
          ParamSpec.vector("mu", 2, Constraint.Ordered),
          ParamSpec.vector("sigma", 2, Constraint.Positive),
          ParamSpec.unitInterval("theta")
        ).mapN[Mixture]([A] => (m: IArray[A], s: IArray[A], t: A) => Mixture(m, s, t))

      def logPrior[A: Real](p: Mixture[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 2.0.lift[A]) +
          LogDensity.halfNormal(p.sigma(0), 2.0.lift[A]) +
          LogDensity.halfNormal(p.sigma(1), 2.0.lift[A]) +
          LogDensity.beta(p.theta, 5.0.lift[A], 5.0.lift[A])

      def logLikelihood[A: Real](p: Mixture[A]): A =
        val r = Real[A]
        // `log_mix` is a log-sum-exp of two weighted densities, which is what keeps a component
        // of probability 1e-300 from underflowing to nothing.
        val terms = r.vectorOf(y.length) { i =>
          r.logSumExp(
            r.vector(
              logInvLogitOf(p.theta) + LogDensity.normal(y(i).lift[A], p.mu(0), p.sigma(0)),
              log1mOf(p.theta) + LogDensity.normal(y(i).lift[A], p.mu(1), p.sigma(1))
            )
          )
        }
        r.sum(terms)

  private def logInvLogitOf[A: Real](theta: A): A = log(theta)
  private def log1mOf[A: Real](theta: A): A = log1p(-theta)

  // --- gp_pois_regr: a latent Gaussian process ------------------------------------------------

  final case class GaussianProcess[A](rho: A, alpha: A, fTilde: IArray[A])

  /** `f = cholesky(gp_exp_quad_cov(x, alpha, rho) + 1e-10 I) f_tilde`, `k ~ poisson_log(f)`.
    *
    * The factor is of a matrix whose entries are parameters, so it has to be differentiated
    * through. The specification leaves a differentiable Cholesky out of `Real[A]` on purpose —
    * models are expected to parameterise a factor directly — so the decomposition is written here,
    * out of the scalar operations, and the tape differentiates the composition. At eleven
    * dimensions that is a few hundred nodes; it would be the wrong way to fit a hundred.
    */
  def gaussianProcess: Either[String, Model[GaussianProcess]] =
    for
      json <- data("gp_pois_regr-gp_pois_regr")
      n <- int(json, "N")
      x <- doubles(json, "x")
      k <- ints(json, "k")
    yield new Model[GaussianProcess]:
      val params: ParamSpec[GaussianProcess] =
        (
          ParamSpec.positive("rho"),
          ParamSpec.positive("alpha"),
          ParamSpec.vector("f_tilde", n)
        ).mapN[GaussianProcess]([A] => (r: A, a: A, f: IArray[A]) => GaussianProcess(r, a, f))

      def logPrior[A: Real](p: GaussianProcess[A]): A =
        LogDensity.gamma(p.rho, 25.0.lift[A], 4.0.lift[A]) +
          LogDensity.halfNormal(p.alpha, 2.0.lift[A]) +
          LogDensity.normal(p.fTilde, 0.0.lift[A], 1.0.lift[A])

      /** `f`, the latent field, is what the reference summary reports; `f_tilde` is not in it. */
      override def derived(p: GaussianProcess[Double]): Map[String, IArray[Double]] =
        Map("f" -> latentField[Double](p, x, n))

      def logLikelihood[A: Real](p: GaussianProcess[A]): A =
        Real[A].poissonLogSum(k, latentField(p, x, n))

  /** `cholesky(cov) f_tilde`: the latent field, written once for the likelihood and the report. */
  private def latentField[A: Real](
      p: GaussianProcess[A],
      x: IArray[Double],
      n: Int
  ): IArray[A] =
    Real[A].matVec(lowerFactor(squaredExponential(x, p.alpha, p.rho, n), n), p.fTilde)

  /** `alpha^2 exp(-(x_i - x_j)^2 / (2 rho^2))`, with Stan's jitter on the diagonal. */
  private def squaredExponential[A: Real](
      x: IArray[Double],
      alpha: A,
      rho: A,
      n: Int
  ): IArray[IArray[A]] =
    val r = Real[A]
    val variance = alpha * alpha
    val rows = Vector.tabulate(n) { i =>
      r.vectorOf(n) { j =>
        val gap = x(i) - x(j)
        val entry = variance * exp(-(gap * gap).lift[A] / (rho * rho * 2.0))
        if i == j then entry + 1e-10 else entry
      }
    }
    IArray.from(rows)

  /** The Cholesky factor, by the textbook recursion, written out of the scalar operations. */
  private def lowerFactor[A: Real](s: IArray[IArray[A]], n: Int): IArray[IArray[A]] =
    val r = Real[A]
    val rows = (0 until n).foldLeft(Vector.empty[Vector[A]]) { (built, i) =>
      val row = (0 to i).foldLeft(Vector.empty[A]) { (entries, j) =>
        // For the diagonal entry the inner products are of the row with itself, and that row is
        // the one being built: `built(i)` does not exist yet.
        val other: Int => A = if j == i then entries else built(j)
        val known = (0 until j).foldLeft(r.const(0.0))((sum, p) => sum + entries(p) * other(p))
        if i == j then entries :+ sqrt(s(i)(i) - known)
        else entries :+ ((s(i)(j) - known) / built(j)(j))
      }
      built :+ (row ++ Vector.fill(n - i - 1)(r.const(0.0)))
    }
    r.matrixOf(n, n)((i, j) => rows(i)(j))

  /** A ported posterior, in the form the comparison needs: a target to sample, how many
    * unconstrained reals it takes, and how to name a point's values.
    *
    * The naming includes the **derived** quantities, because three of the references report a
    * quantity the model does not carry as a parameter: `theta` of the non-centred eight schools,
    * `beta1` of the GARCH, and `f` of the Gaussian process.
    */
  final case class Ported(
      target: DiffFn,
      dimension: Int,
      names: List[String],
      valuesAt: IArray[Double] => Map[String, IArray[Double]]
  )

  private def ported[T[_]](model: Model[T]): Ported =
    Ported(
      Model.target(model),
      model.params.unconstrainedSize,
      model.params.names,
      q => Model.values(model, q) ++ model.derived(Model.constrain(model, q))
    )

  /** The nine reference posteriors, by their posteriordb name. */
  def all: List[(String, Either[String, Ported])] =
    List(
      "eight_schools-eight_schools_noncentered" -> eightSchoolsNonCentred.map(ported),
      "arK-arK" -> arK.map(ported),
      "arma-arma11" -> arma11.map(ported),
      "garch-garch11" -> garch11.map(ported),
      "kidiq-kidscore_momiq" -> kidiq.map(ported),
      "earnings-logearn_height" -> earnings.map(ported),
      "dogs-dogs" -> dogs.map(ported),
      "low_dim_gauss_mix-low_dim_gauss_mix" -> gaussianMixture.map(ported),
      "gp_pois_regr-gp_pois_regr" -> gaussianProcess.map(ported)
    )

  /** The ninth: the one phase 3 already wrote, read from its own data file. */
  def eightSchoolsNonCentred: Either[String, Model[EightSchools.NonCentred]] =
    for
      json <- data("eight_schools-eight_schools_noncentered")
      y <- doubles(json, "y")
      sigma <- doubles(json, "sigma")
      built <- EightSchools.Data.of(y, sigma).left.map(_.message)
    yield EightSchools.nonCentred(built)
