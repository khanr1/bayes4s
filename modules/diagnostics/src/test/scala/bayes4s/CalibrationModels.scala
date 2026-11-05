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
import cats.Id
import cats.Monad
import cats.syntax.all.*

/** Small generative models for simulation-based calibration.
  *
  * Small on purpose: calibration needs hundreds of fits, so each one has to be a few milliseconds,
  * and what is being tested is the agreement between a model's two readings rather than the
  * difficulty of its posterior. Each one is written twice — as a density and as a simulation — and
  * that duplication is exactly what the ranks measure.
  *
  * The data is invented and is a function of nothing but the parameters drawn (2.8).
  */
object CalibrationModels:

  // --- a Gaussian location and scale ----------------------------------------------------------

  final case class LocationScale[A](mu: A, sigma: A)

  final case class Observations(y: IArray[Double])

  /** `mu ~ Gaussian(0, 2)`, `sigma ~ half-Gaussian(1)`, `y ~ Gaussian(mu, sigma)`. */
  def locationScale(n: Int): GenerativeModel[LocationScale, Observations] =
    def model(data: Observations): Model[LocationScale] = new Model[LocationScale]:
      val params: ParamSpec[LocationScale] =
        (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
          .mapN[LocationScale]([A] => (m: A, s: A) => LocationScale(m, s))

      def logPrior[A: Real](p: LocationScale[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 2.0.lift[A]) +
          LogDensity.halfNormal(p.sigma, 1.0.lift[A])

      def logLikelihood[A: Real](p: LocationScale[A]): A =
        Real[A].normalSum(data.y, Real[A].filled(data.y.length)(p.mu), p.sigma)

    val generative = new Generative[LocationScale, Observations]:
      def samplePrior[F[_]: Monad: Rng]: F[LocationScale[Double]] =
        for
          mu <- Rng[F].normal.map(_ * 2.0)
          sigma <- Rng[F].normal.map(z => math.abs(z))
        yield LocationScale(mu, sigma)

      def simulateData[F[_]: Monad: Rng](p: LocationScale[Double]): F[Observations] =
        Monad[F]
          .replicateA(n, Rng[F].normal.map(z => p.mu + p.sigma * z))
          .map(drawn => Observations(IArray.from(drawn)))

      def named(p: LocationScale[Double]): Map[String, IArray[Double]] =
        Map("mu" -> IArray(p.mu), "sigma" -> IArray(p.sigma))

    GenerativeModel(Observations(IArray.fill(n)(0.0)), model, generative)

  // --- a Poisson rate with a Gamma prior ------------------------------------------------------

  final case class Counts(k: IArray[Int])

  /** `rate ~ Gamma(3, 1)`, `k ~ Poisson(rate)`: the conjugate pair, read as a simulation. */
  def gammaPoisson(n: Int): GenerativeModel[Id, Counts] =
    def model(data: Counts): Model[Id] = new Model[Id]:
      val params: ParamSpec[Id] = ParamSpec.positive("rate")

      def logPrior[A: Real](rate: A): A =
        LogDensity.gamma(rate, 3.0.lift[A], 1.0.lift[A])

      def logLikelihood[A: Real](rate: A): A =
        Real[A].poissonSum(data.k, Real[A].filled(data.k.length)(rate))

    val generative = new Generative[Id, Counts]:
      def samplePrior[F[_]: Monad: Rng]: F[Double] = Sampling.gamma[F](3.0)

      def simulateData[F[_]: Monad: Rng](rate: Double): F[Counts] =
        Monad[F].replicateA(n, Sampling.poisson[F](rate)).map(drawn => Counts(IArray.from(drawn)))

      def named(rate: Double): Map[String, IArray[Double]] = Map("rate" -> IArray(rate))

    GenerativeModel(Counts(IArray.fill(n)(0)), model, generative)

  // --- a multilevel model, non-centred --------------------------------------------------------

  final case class Levels[A](mu: A, tau: A, z: IArray[A])

  final case class Groups(y: IArray[Double], sigma: IArray[Double])

  /** Four groups with known measurement error: the shape of the eight schools, small enough to
    * fit two hundred times, and written non-centred so that the sampler can actually do it.
    */
  def varyingLevels(groups: Int): GenerativeModel[Levels, Groups] =
    val errors = IArray.tabulate(groups)(j => 1.0 + 0.5 * j)

    def effects[A: Real](p: Levels[A]): IArray[A] =
      Real[A].vectorOf(p.z.length)(j => p.mu + p.tau * p.z(j))

    def model(data: Groups): Model[Levels] = new Model[Levels]:
      val params: ParamSpec[Levels] =
        (ParamSpec.real("mu"), ParamSpec.positive("tau"), ParamSpec.vector("z", groups))
          .mapN[Levels]([A] => (m: A, t: A, z: IArray[A]) => Levels(m, t, z))

      def logPrior[A: Real](p: Levels[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 3.0.lift[A]) +
          LogDensity.halfNormal(p.tau, 2.0.lift[A]) +
          LogDensity.normal(p.z, 0.0.lift[A], 1.0.lift[A])

      def logLikelihood[A: Real](p: Levels[A]): A =
        Real[A].normalSum(data.y, effects(p), Real[A].vectorOf(groups)(j => data.sigma(j).lift[A]))

      override def derived(p: Levels[Double]): Map[String, IArray[Double]] =
        Map("theta" -> effects[Double](p))

    val generative = new Generative[Levels, Groups]:
      def samplePrior[F[_]: Monad: Rng]: F[Levels[Double]] =
        for
          mu <- Rng[F].normal.map(_ * 3.0)
          tau <- Rng[F].normal.map(z => math.abs(2.0 * z))
          z <- Monad[F].replicateA(groups, Rng[F].normal)
        yield Levels(mu, tau, IArray.from(z))

      def simulateData[F[_]: Monad: Rng](p: Levels[Double]): F[Groups] =
        (0 until groups).toList
          .traverse(j => Rng[F].normal.map(e => p.mu + p.tau * p.z(j) + errors(j) * e))
          .map(drawn => Groups(IArray.from(drawn), errors))

      def named(p: Levels[Double]): Map[String, IArray[Double]] =
        Map("mu" -> IArray(p.mu), "tau" -> IArray(p.tau), "z" -> p.z)

    GenerativeModel(Groups(IArray.fill(groups)(0.0), errors), model, generative)
