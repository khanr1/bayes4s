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

/** The eight schools, centred and non-centred: the model every book on multilevel modelling
  * starts from, and the one phase 4 will diagnose.
  *
  * The data is Rubin (1981) as posteriordb carries it — public, and the only data in this
  * repository that is not invented. The two parameterisations describe the same posterior and
  * sample differently, which is the whole point of having both.
  *
  * The priors are posteriordb's: `mu ~ Normal(0, 5)` and `tau ~ Cauchy(0, 5)` restricted to the
  * positive half, which is a half Cauchy.
  */
object EightSchools:

  /** The prior scales, written once and read by both the density and the simulation. */
  val MuScale: Double = 5.0
  val TauScale: Double = 5.0

  final case class Data(y: IArray[Double], sigma: IArray[Double]):
    def schools: Int = y.length

  object Data:
    def of(y: IArray[Double], sigma: IArray[Double]): Either[BayesError, Data] =
      if y.length != sigma.length then
        Left(BayesError.DimensionMismatch("EightSchools.Data.of", y.length, sigma.length))
      else
        (0 until sigma.length)
          .collectFirst {
            case j if !(sigma(j) > 0.0) || sigma(j).isInfinite =>
              BayesError.InvalidArgument(
                "EightSchools.Data.of",
                s"sigma($j)",
                sigma(j),
                "sigma > 0"
              )
            case j if y(j).isNaN || y(j).isInfinite =>
              BayesError.InvalidArgument("EightSchools.Data.of", s"y($j)", y(j), "y is finite")
          }
          .toLeft(Data(y, sigma))

  /** `mu`, `tau` and one effect per school: the centred parameterisation. */
  final case class Centred[A](mu: A, tau: A, theta: IArray[A])

  /** `mu`, `tau` and a standard effect per school: the non-centred one. `theta` is derived. */
  final case class NonCentred[A](mu: A, tau: A, z: IArray[A])

  def centred(data: Data): Model[Centred] = new Model[Centred]:
    val params: ParamSpec[Centred] =
      (
        ParamSpec.real("mu"),
        ParamSpec.positive("tau"),
        ParamSpec.vector("theta", data.schools)
      ).mapN[Centred]([A] => (mu: A, tau: A, theta: IArray[A]) => Centred(mu, tau, theta))

    def logPrior[A: Real](p: Centred[A]): A =
      LogDensity.normal(p.mu, 0.0.lift[A], MuScale.lift[A]) +
        LogDensity.halfCauchy(p.tau, TauScale.lift[A]) +
        LogDensity.normal(p.theta, p.mu, p.tau)

    def logLikelihood[A: Real](p: Centred[A]): A =
      Real[A].normalSum(data.y, p.theta, liftedSigma[A](data))

  def nonCentred(data: Data): Model[NonCentred] = new Model[NonCentred]:
    val params: ParamSpec[NonCentred] =
      (
        ParamSpec.real("mu"),
        ParamSpec.positive("tau"),
        ParamSpec.vector("z", data.schools)
      ).mapN[NonCentred]([A] => (mu: A, tau: A, z: IArray[A]) => NonCentred(mu, tau, z))

    def logPrior[A: Real](p: NonCentred[A]): A =
      LogDensity.normal(p.mu, 0.0.lift[A], MuScale.lift[A]) +
        LogDensity.halfCauchy(p.tau, TauScale.lift[A]) +
        LogDensity.normal(p.z, 0.0.lift[A], 1.0.lift[A])

    def logLikelihood[A: Real](p: NonCentred[A]): A =
      Real[A].normalSum(data.y, effects(p), liftedSigma[A](data))

    /** `theta = mu + tau z`, the quantity the model is about and not a parameter of it. */
    override def derived(p: NonCentred[Double]): Map[String, IArray[Double]] =
      Map("theta" -> effects[Double](p))

  private def effects[A: Real](p: NonCentred[A]): IArray[A] =
    Real[A].vectorOf(p.z.length)(j => p.mu + p.tau * p.z(j))

  private def liftedSigma[A: Real](data: Data): IArray[A] =
    Real[A].vectorOf(data.schools)(j => data.sigma(j).lift[A])

  /** The same model read as a simulation.
    *
    * The three draws are written out rather than taken from the law values, because a law is built
    * through a smart constructor that returns `Either` and `samplePrior` has nowhere to put a
    * `Left`. That is a duplication, and the plan says so; `SchoolModelSuite` closes it by asserting
    * that each stream is **bit for bit** the stream of the corresponding law, for the same seed.
    */
  def generative(data: Data): Generative[Centred, Data] = new Generative[Centred, Data]:
    def samplePrior[F[_]: Monad: Rng]: F[Centred[Double]] =
      for
        mu <- Rng[F].normal.map(_ * MuScale)
        tau <- Rng[F].uniform.map(u => math.abs(TauScale * math.tan(math.Pi * (u - 0.5))))
        theta <- Monad[F].replicateA(data.schools, Rng[F].normal.map(z => mu + tau * z))
      yield Centred(mu, tau, IArray.from(theta))

    def simulateData[F[_]: Monad: Rng](p: Centred[Double]): F[Data] =
      (0 until data.schools).toList
        .traverse(j => Rng[F].normal.map(z => p.theta(j) + data.sigma(j) * z))
        .map(drawn => Data(IArray.from(drawn), data.sigma))

  /** The generative model: the data, how to read a model on other data, and the simulation. */
  def generativeModel(data: Data): GenerativeModel[Centred, Data] =
    GenerativeModel(data, centred, generative(data))
