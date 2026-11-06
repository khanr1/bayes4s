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
import cats.Monad
import cats.syntax.all.*

/** The multilevel test models of phase 6b: varying intercepts both ways, varying intercepts and
  * slopes with a correlation between them, and a multilevel logistic regression.
  *
  * Each one carries its log likelihood **per observation** as well as in total, because that is
  * what cross-validation needs and because the two have to agree — the obligatory test.
  *
  * The data is invented: a deterministic function of the row number, or simulated from the
  * model's own generative part (2.8). The one exception is the correlated model, whose data comes
  * from the reference posterior's own file so that the comparison is against the same numbers.
  */
object MultilevelModels:

  /** Observations in groups, with a predictor. */
  final case class Grouped(group: IArray[Int], x: IArray[Double], y: IArray[Double], groups: Int):
    def rows: Int = y.length

  // --- varying intercepts, centred and non-centred --------------------------------------------

  final case class Centred[A](mu: A, tau: A, sigma: A, alpha: IArray[A])
  final case class NonCentred[A](mu: A, tau: A, sigma: A, z: IArray[A])

  private def levelsOf[A: Real](mu: A, tau: A, z: IArray[A]): IArray[A] =
    Real[A].vectorOf(z.length)(j => mu + tau * z(j))

  /** `y ~ Gaussian(alpha[group], sigma)`, `alpha ~ Gaussian(mu, tau)`: the effects themselves are
    * the parameters.
    */
  def varyingInterceptsCentred(
      data: Grouped
  ): Model[Centred] & PointwiseLikelihood[Centred] =
    new Model[Centred] with PointwiseLikelihood[Centred]:
      val params: ParamSpec[Centred] =
        (
          ParamSpec.real("mu"),
          ParamSpec.positive("tau"),
          ParamSpec.positive("sigma"),
          ParamSpec.vector("alpha", data.groups)
        ).mapN[Centred]([A] => (m: A, t: A, s: A, a: IArray[A]) => Centred(m, t, s, a))

      def logPrior[A: Real](p: Centred[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
          LogDensity.halfNormal(p.tau, 1.0.lift[A]) +
          LogDensity.halfNormal(p.sigma, 1.0.lift[A]) +
          LogDensity.normal(p.alpha, p.mu, p.tau)

      def logLikelihood[A: Real](p: Centred[A]): A =
        Real[A].normalSum(data.y, Real[A].gather(p.alpha, data.group), p.sigma)

      def pointwise(p: Centred[Double]): IArray[Double] =
        IArray.tabulate(data.rows)(i =>
          LogDensity.normal(data.y(i), p.alpha(data.group(i)), p.sigma)
        )

  /** The same model, with the standardised effects as parameters. */
  def varyingInterceptsNonCentred(
      data: Grouped
  ): Model[NonCentred] & PointwiseLikelihood[NonCentred] =
    new Model[NonCentred] with PointwiseLikelihood[NonCentred]:
      val params: ParamSpec[NonCentred] =
        (
          ParamSpec.real("mu"),
          ParamSpec.positive("tau"),
          ParamSpec.positive("sigma"),
          ParamSpec.vector("z", data.groups)
        ).mapN[NonCentred]([A] => (m: A, t: A, s: A, z: IArray[A]) => NonCentred(m, t, s, z))

      def logPrior[A: Real](p: NonCentred[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
          LogDensity.halfNormal(p.tau, 1.0.lift[A]) +
          LogDensity.halfNormal(p.sigma, 1.0.lift[A]) +
          LogDensity.normal(p.z, 0.0.lift[A], 1.0.lift[A])

      def logLikelihood[A: Real](p: NonCentred[A]): A =
        val levels = levelsOf(p.mu, p.tau, p.z)
        Real[A].normalSum(data.y, Real[A].gather(levels, data.group), p.sigma)

      override def derived(p: NonCentred[Double]): Map[String, IArray[Double]] =
        Map("alpha" -> levelsOf[Double](p.mu, p.tau, p.z))

      def pointwise(p: NonCentred[Double]): IArray[Double] =
        val levels = levelsOf[Double](p.mu, p.tau, p.z)
        IArray.tabulate(data.rows)(i =>
          LogDensity.normal(data.y(i), levels(data.group(i)), p.sigma)
        )

  // --- the two extremes, for the comparison LOO is meant to settle -----------------------------

  final case class Pooled[A](mu: A, sigma: A)

  /** Complete pooling: one intercept for every group.
    *
    * Its prior on `mu` is an argument because power scaling is about the prior: the same data read
    * once under a wide prior and once under a narrow one placed away from it is the contrast the
    * diagnostic exists to tell apart.
    */
  def completelyPooled(
      data: Grouped,
      priorMean: Double = 0.0,
      priorScale: Double = 5.0
  ): Model[Pooled] & PointwiseLikelihood[Pooled] =
    new Model[Pooled] with PointwiseLikelihood[Pooled]:
      val params: ParamSpec[Pooled] =
        (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
          .mapN[Pooled]([A] => (m: A, s: A) => Pooled(m, s))

      def logPrior[A: Real](p: Pooled[A]): A =
        LogDensity.normal(p.mu, priorMean.lift[A], priorScale.lift[A]) +
          LogDensity.halfNormal(p.sigma, 1.0.lift[A])

      def logLikelihood[A: Real](p: Pooled[A]): A =
        Real[A].normalSum(data.y, Real[A].filled(data.rows)(p.mu), p.sigma)

      def pointwise(p: Pooled[Double]): IArray[Double] =
        IArray.tabulate(data.rows)(i => LogDensity.normal(data.y(i), p.mu, p.sigma))

  final case class Separate[A](alpha: IArray[A], sigma: A)

  /** No pooling: an intercept per group, with no level above them. */
  def notPooled(data: Grouped): Model[Separate] & PointwiseLikelihood[Separate] =
    new Model[Separate] with PointwiseLikelihood[Separate]:
      val params: ParamSpec[Separate] =
        (ParamSpec.vector("alpha", data.groups), ParamSpec.positive("sigma"))
          .mapN[Separate]([A] => (a: IArray[A], s: A) => Separate(a, s))

      def logPrior[A: Real](p: Separate[A]): A =
        LogDensity.normal(p.alpha, 0.0.lift[A], 5.0.lift[A]) +
          LogDensity.halfNormal(p.sigma, 1.0.lift[A])

      def logLikelihood[A: Real](p: Separate[A]): A =
        Real[A].normalSum(data.y, Real[A].gather(p.alpha, data.group), p.sigma)

      def pointwise(p: Separate[Double]): IArray[Double] =
        IArray.tabulate(data.rows)(i =>
          LogDensity.normal(data.y(i), p.alpha(data.group(i)), p.sigma)
        )

  // --- varying intercepts and slopes, correlated ----------------------------------------------

  final case class Correlated[A](
      mu: IArray[A],
      tau: IArray[A],
      factor: IArray[IArray[A]],
      sigma: A,
      z: IArray[IArray[A]]
  )

  /** The fictional reference posterior's own model: `eff[j] = mu + tau ⊙ (L z_j)`.
    *
    * The one model in the library that uses every piece of the multilevel machinery at once — a
    * Cholesky factor of correlation, a vector of scales, a matrix of standardised effects — and
    * the only reference posterior with correlated effects, because posteriordb has none.
    */
  def correlatedEffects(data: Grouped): Model[Correlated] & PointwiseLikelihood[Correlated] =
    new Model[Correlated] with PointwiseLikelihood[Correlated]:
      val params: ParamSpec[Correlated] =
        (
          ParamSpec.vector("mu", 2),
          ParamSpec.vector("tau", 2, Constraint.Positive),
          ParamSpec.choleskyCorr("L_Omega", 2),
          ParamSpec.positive("sigma"),
          ParamSpec.matrix("z", data.groups, 2)
        ).mapN[Correlated]([A] =>
          (
              m: IArray[A],
              t: IArray[A],
              l: IArray[IArray[A]],
              s: A,
              z: IArray[IArray[A]]
          ) => Correlated(m, t, l, s, z)
        )

      def logPrior[A: Real](p: Correlated[A]): A =
        val r = Real[A]
        LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
          LogDensity.halfNormal(p.tau(0), 1.0.lift[A]) +
          LogDensity.halfNormal(p.tau(1), 1.0.lift[A]) +
          LogDensity.lkjCorrCholesky(p.factor, 2.0.lift[A]) +
          LogDensity.halfNormal(p.sigma, 1.0.lift[A]) +
          r.sum(r.vectorOf(data.groups * 2) { k =>
            LogDensity.normal(p.z(k / 2)(k % 2), 0.0.lift[A], 1.0.lift[A])
          })

      def logLikelihood[A: Real](p: Correlated[A]): A =
        val r = Real[A]
        val effects = groupEffects(p, data.groups)
        val means = r.vectorOf(data.rows) { i =>
          val j = data.group(i)
          effects(j)(0) + effects(j)(1) * data.x(i).lift[A]
        }
        r.normalSum(data.y, means, p.sigma)

      /** `rho` is the correlation, which the reference reports as a generated quantity. */
      override def derived(p: Correlated[Double]): Map[String, IArray[Double]] =
        Map("rho" -> IArray(p.factor(1)(0)))

      def pointwise(p: Correlated[Double]): IArray[Double] =
        val effects = groupEffects[Double](p, data.groups)
        IArray.tabulate(data.rows) { i =>
          val j = data.group(i)
          LogDensity.normal(data.y(i), effects(j)(0) + effects(j)(1) * data.x(i), p.sigma)
        }

  /** `mu + tau ⊙ (L z_j)`, one row per group. */
  private def groupEffects[A: Real](p: Correlated[A], groups: Int): IArray[IArray[A]] =
    val r = Real[A]
    r.rowsOf(groups) { j =>
      val standardised = r.matVec(p.factor, r.vector(p.z(j)(0), p.z(j)(1)))
      r.vectorOf(2)(k => p.mu(k) + p.tau(k) * standardised(k))
    }

  // --- a multilevel logistic regression --------------------------------------------------------

  final case class Logistic[A](mu: A, tau: A, beta: A, z: IArray[A])

  final case class Binary(group: IArray[Int], x: IArray[Double], y: IArray[Int], groups: Int)

  /** `y ~ Bernoulli(logit = alpha[group] + beta x)`, `alpha ~ Gaussian(mu, tau)`. */
  def multilevelLogistic(data: Binary): Model[Logistic] & PointwiseLikelihood[Logistic] =
    new Model[Logistic] with PointwiseLikelihood[Logistic]:
      val params: ParamSpec[Logistic] =
        (
          ParamSpec.real("mu"),
          ParamSpec.positive("tau"),
          ParamSpec.real("beta"),
          ParamSpec.vector("z", data.groups)
        ).mapN[Logistic]([A] => (m: A, t: A, b: A, z: IArray[A]) => Logistic(m, t, b, z))

      def logPrior[A: Real](p: Logistic[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 2.0.lift[A]) +
          LogDensity.halfNormal(p.tau, 1.0.lift[A]) +
          LogDensity.normal(p.beta, 0.0.lift[A], 2.0.lift[A]) +
          LogDensity.normal(p.z, 0.0.lift[A], 1.0.lift[A])

      def logLikelihood[A: Real](p: Logistic[A]): A =
        Real[A].bernoulliLogitSum(data.y, logits(p, data))

      override def derived(p: Logistic[Double]): Map[String, IArray[Double]] =
        Map("alpha" -> levelsOf[Double](p.mu, p.tau, p.z))

      def pointwise(p: Logistic[Double]): IArray[Double] =
        val eta = logits[Double](p, data)
        IArray.tabulate(data.y.length)(i => LogMass.bernoulliLogit(data.y(i), eta(i)))

  private def logits[A: Real](p: Logistic[A], data: Binary): IArray[A] =
    val r = Real[A]
    val levels = levelsOf(p.mu, p.tau, p.z)
    r.vectorOf(data.y.length)(i => levels(data.group(i)) + p.beta * data.x(i).lift[A])

  // --- the generative parts, for calibration ---------------------------------------------------

  /** The correlated model, reduced to six groups, as a simulation. */
  def correlatedGenerative(groups: Int, perGroup: Int): GenerativeModel[Correlated, Grouped] =
    val index = IArray.tabulate(groups * perGroup)(i => i % groups)
    val predictor = IArray.tabulate(groups * perGroup)(i => math.sin(i * 0.9))
    val blank = Grouped(index, predictor, IArray.fill(groups * perGroup)(0.0), groups)
    val generative = new Generative[Correlated, Grouped]:
      def samplePrior[F[_]: Monad: Rng]: F[Correlated[Double]] =
        for
          mu <- Monad[F].replicateA(2, Rng[F].normal.map(_ * 5.0))
          tau <- Monad[F].replicateA(2, Rng[F].normal.map(math.abs))
          // A correlation from the LKJ(2) prior in two dimensions: its density is proportional to
          // `1 - r^2`, which is the Beta(2, 2) on (-1, 1) that the onion method draws.
          beta <- Sampling.beta[F](2.0, 2.0)
          sigma <- Rng[F].normal.map(math.abs)
          z <- Monad[F].replicateA(groups * 2, Rng[F].normal)
        yield
          val r = 2.0 * beta - 1.0
          val factor = IArray(IArray(1.0, 0.0), IArray(r, math.sqrt(1.0 - r * r)))
          Correlated(
            IArray.from(mu),
            IArray.from(tau),
            factor,
            sigma,
            IArray.tabulate(groups)(j => IArray(z(2 * j), z(2 * j + 1)))
          )

      def simulateData[F[_]: Monad: Rng](p: Correlated[Double]): F[Grouped] =
        val effects = groupEffects[Double](p, groups)
        (0 until index.length).toList
          .traverse { i =>
            val j = index(i)
            Rng[F].normal.map(e => effects(j)(0) + effects(j)(1) * predictor(i) + p.sigma * e)
          }
          .map(drawn => blank.copy(y = IArray.from(drawn)))

      def named(p: Correlated[Double]): Map[String, IArray[Double]] =
        Map(
          "mu" -> p.mu,
          "tau" -> p.tau,
          "sigma" -> IArray(p.sigma),
          "rho" -> IArray(p.factor(1)(0))
        )
    GenerativeModel(blank, correlatedEffects, generative)

  /** The multilevel logistic regression, as a simulation. */
  def logisticGenerative(groups: Int, perGroup: Int): GenerativeModel[Logistic, Binary] =
    val index = IArray.tabulate(groups * perGroup)(i => i % groups)
    val predictor = IArray.tabulate(groups * perGroup)(i => math.cos(i * 0.7))
    val blank = Binary(index, predictor, IArray.fill(groups * perGroup)(0), groups)
    val generative = new Generative[Logistic, Binary]:
      def samplePrior[F[_]: Monad: Rng]: F[Logistic[Double]] =
        for
          mu <- Rng[F].normal.map(_ * 2.0)
          tau <- Rng[F].normal.map(math.abs)
          beta <- Rng[F].normal.map(_ * 2.0)
          z <- Monad[F].replicateA(groups, Rng[F].normal)
        yield Logistic(mu, tau, beta, IArray.from(z))

      def simulateData[F[_]: Monad: Rng](p: Logistic[Double]): F[Binary] =
        val eta = logits[Double](p, blank)
        (0 until index.length).toList
          .traverse(i => Rng[F].uniform.map(u => if u < LogExp.invLogit(eta(i)) then 1 else 0))
          .map(drawn => blank.copy(y = IArray.from(drawn)))

      def named(p: Logistic[Double]): Map[String, IArray[Double]] =
        Map("mu" -> IArray(p.mu), "tau" -> IArray(p.tau), "beta" -> IArray(p.beta))
    GenerativeModel(blank, multilevelLogistic, generative)
