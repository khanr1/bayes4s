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

/** Log densities of realistic shape, written once against `Real[A]` and read by the suites, by the
  * oracle and by the benchmarks.
  *
  * They are plain `DiffFn`s rather than models: a model needs named parameters and constraint
  * transforms, which arrive in phase 3. What is needed now is something with the shape of real
  * work — a latent series, hundreds of masked cells, ten thousand observations in groups — to
  * compare the reverse mode against the oracle on, and to measure.
  *
  * Each model's data is fixed when the model is built rather than rebuilt at every evaluation: a
  * gradient is asked for thousands of times, and the mask does not depend on the parameters. The
  * data itself is a deterministic function of the row number, which is how it stays fictional
  * (2.8).
  */
object TestModels:

  /** A latent AR(1) rate, an ordered-logit reporting delay, and Poisson counts on the visible
    * cells of a reporting triangle.
    *
    * The parameter vector is `[a, b, phi, tau, eps(0..months-1), c0, increments(0..lags-3),
    * drift]`, of length `months + lags + 4`. The delay cut points are `c0` followed by positive
    * increments, so they increase by construction; `drift` moves the whole delay distribution with
    * calendar time, which is what makes the cut points a function of the parameters rather than a
    * constant.
    *
    * This is the shape of the nowcasting models and the reason the vectorised primitives exist:
    * the mass of a couple of hundred cells is one node of the tape, not a couple of hundred.
    */
  def ar1PoissonOrdinal(months: Int = 36, lags: Int = 12): DiffFn =
    val times = IArray.tabulate(months)(m => m.toDouble / months.toDouble)
    val visible = for
      m <- (0 until months).toList
      lag <- 0 until lags
      if m + lag <= months - 1
    yield (m, lag)
    val cells = IArray.from(visible)
    val counts = IArray.from(visible.map((m, lag) => (3 + m + 2 * lag) % 7))
    new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        val a = x(0)
        val b = x(1)
        val phi = x(2)
        val tau = x(3)
        val eps = r.vectorOf(months)(i => x(4 + i))
        val c0 = x(4 + months)
        val increments = r.vectorOf(lags - 2)(i => x(5 + months + i))
        val drift = x(months + lags + 3)

        // The non-centred AR(1) path: a stationary first state, then phi z + tau eps.
        val stationary = tau / sqrt(1.0.lift[A] - phi * phi)
        val path = (1 until months).foldLeft(Vector(eps(0) * stationary)) { (acc, m) =>
          acc :+ (phi * acc(m - 1) + tau * eps(m))
        }

        // The cut points of the delay distribution, increasing by construction.
        val cuts = r.cumulativeSum(
          r.vectorOf(lags - 1)(i => if i == 0 then c0 else exp(increments(i - 1)))
        )
        val logRate = r.vectorOf(months)(m => a + b * times(m) + path(m))
        // The delay distribution of a month, shared by all of that month's cells.
        val delay = Vector.tabulate(months) { m =>
          val eta = drift * times(m)
          r.vectorOf(lags)(lag => LogDensity.orderedLogistic(lag + 1, eta, cuts))
        }
        val logCellRate = r.vectorOf(cells.length) { i =>
          val (m, lag) = cells(i)
          logRate(m) + delay(m)(lag)
        }

        val likelihood = r.poissonLogSum(counts, logCellRate)
        val priors = LogDensity.normal(a, 0.0.lift[A], 5.0.lift[A]) +
          LogDensity.normal(b, 0.0.lift[A], 1.0.lift[A]) +
          LogDensity.beta(phi, 2.0.lift[A], 2.0.lift[A]) +
          LogDensity.halfNormal(tau, 1.0.lift[A]) +
          r.normalSum(eps, 0.0.lift[A], 1.0.lift[A]) +
          LogDensity.normal(c0, 0.0.lift[A], 2.0.lift[A]) +
          r.normalSum(increments, 0.0.lift[A], 1.0.lift[A]) +
          LogDensity.normal(drift, 0.0.lift[A], 1.0.lift[A])
        likelihood + priors

  /** A parameter vector in the interior of the support of [[ar1PoissonOrdinal]]. */
  def ar1At(months: Int = 36, lags: Int = 12): IArray[Double] =
    IArray.from(
      List(0.5, 0.2, 0.6, 0.4) ++
        List.tabulate(months)(i => math.sin(i.toDouble) * 0.5) ++
        List(-1.0) ++
        List.tabulate(lags - 2)(i => -0.5 + 0.1 * i) ++
        List(0.3)
    )

  /** Observations in groups, each group with its own level around a common mean: the shape of a
    * multilevel model, and the one whose tape must not grow with the data.
    *
    * `gather` gives every observation its group's level by index aliasing, which costs no node at
    * all, and `normalSum` turns ten thousand terms into one. The parameter vector is
    * `[alpha(0..groups-1), mu, sigmaGroup, sigma]`.
    */
  def varyingIntercepts(observations: Int, groups: Int): DiffFn =
    val group = index(observations, groups)
    val ys = observed(observations)
    new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        val alpha = r.vectorOf(groups)(g => x(g))
        r.normalSum(ys, r.gather(alpha, group), x(groups + 2)) +
          r.normalSum(alpha, x(groups), x(groups + 1)) +
          LogDensity.normal(x(groups), 0.0.lift[A], 5.0.lift[A]) +
          LogDensity.halfNormal(x(groups + 1), 1.0.lift[A]) +
          LogDensity.halfNormal(x(groups + 2), 1.0.lift[A])

  /** The same model with one continuous predictor, written out per observation.
    *
    * It exists to be measured, not to be used: `slope * predictor(i)` is a scalar multiplication
    * and a scalar addition for every row, so this tape grows with the data where
    * [[varyingIntercepts]]'s does not. `LogDensityGradientSuite` records both counts and
    * docs/validation.md records what the difference costs.
    */
  def varyingInterceptsWithSlope(observations: Int, groups: Int): DiffFn =
    val group = index(observations, groups)
    val ys = observed(observations)
    val predictor = covariate(observations)
    new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        val alpha = r.vectorOf(groups)(g => x(g))
        val slope = x(groups + 3)
        val intercepts = r.gather(alpha, group)
        val means = r.vectorOf(observations)(i => intercepts(i) + slope * predictor(i))
        r.normalSum(ys, means, x(groups + 2)) +
          r.normalSum(alpha, x(groups), x(groups + 1)) +
          LogDensity.normal(x(groups), 0.0.lift[A], 5.0.lift[A]) +
          LogDensity.halfNormal(x(groups + 1), 1.0.lift[A]) +
          LogDensity.halfNormal(x(groups + 2), 1.0.lift[A]) +
          LogDensity.normal(slope, 0.0.lift[A], 1.0.lift[A])

  /** `[alpha(0..groups-1), mu, sigmaGroup, sigma]`, and a slope after it for the variant. */
  def varyingAt(groups: Int, withSlope: Boolean = false): IArray[Double] =
    IArray.from(
      List.tabulate(groups)(g => 0.1 * g - 0.5) ++ List(0.2, 0.8, 1.1) ++
        (if withSlope then List(0.4) else Nil)
    )

  private def index(observations: Int, groups: Int): IArray[Int] =
    IArray.tabulate(observations)(i => i % groups)

  private def observed(observations: Int): IArray[Double] =
    IArray.tabulate(observations)(i => 0.5 * math.cos(i.toDouble))

  private def covariate(observations: Int): IArray[Double] =
    IArray.tabulate(observations)(i => math.sin(i.toDouble))
