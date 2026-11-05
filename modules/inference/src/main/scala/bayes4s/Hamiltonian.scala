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

import cats.Monad
import cats.syntax.all.*

/** A position, the log density there, and its gradient: everything a Hamiltonian step needs about
  * the target, computed once.
  *
  * The library carries the **log density** and its gradient. Stan's integrator carries the
  * potential `V = -log p` and `g = dV/dq`, which are their negatives, and the fixture states its
  * cases in Stan's variables. The conversion lives in one place — the two `+` signs below, where
  * Stan writes `-` — because a sign error there is a sampler that walks uphill and still looks
  * plausible.
  */
final case class Point(q: IArray[Double], logDensity: Double, gradient: IArray[Double]):

  /** Stan's `V`. */
  def potential: Double = -logDensity

  def dimension: Int = q.length

object Point:
  /** The target read at a position: one value and one gradient, through the tape. */
  def at(target: DiffFn, q: IArray[Double]): Point =
    val evaluated = Reverse.valueAndGradient(target, q)
    Point(q, evaluated.value, evaluated.gradient)

/** The mass matrix, as its **inverse**, which is the form every operation needs.
  *
  * `Diagonal` holds the inverse variances' reciprocals — the estimated variances themselves — and
  * `Dense` the estimated covariance together with its Cholesky factor, because sampling a momentum
  * needs the factor and nothing else does. Stan calls these `diag_e` and `dense_e`; the diagonal
  * is the default there and here.
  */
enum Metric:
  case Diagonal(inverse: IArray[Double])
  case Dense(inverse: Matrix, cholesky: Cholesky)

object Metric:

  def unit(dimension: Int): Metric = Metric.Diagonal(IArray.fill(dimension)(1.0))

  /** A dense metric from an estimated covariance, whose factor is computed once here rather than
    * trusted to agree with it.
    */
  def dense(inverse: Matrix): Either[BayesError, Metric] =
    Linalg.cholesky(inverse).map(factor => Metric.Dense(inverse, factor))

  extension (metric: Metric)

    def dimension: Int = metric match
      case Metric.Diagonal(inverse) => inverse.length
      case Metric.Dense(inverse, _) => inverse.rows

    /** `M^-1 p`, which is both the velocity and Stan's `p_sharp`. */
    def velocity(p: IArray[Double]): IArray[Double] = metric match
      case Metric.Diagonal(inverse) => IArray.tabulate(p.length)(i => inverse(i) * p(i))
      case Metric.Dense(inverse, _) =>
        IArray.tabulate(p.length) { i =>
          (0 until p.length).foldLeft(0.0)((sum, j) => sum + inverse(i, j) * p(j))
        }

    /** `0.5 p' M^-1 p`, Stan's `tau`. */
    def kinetic(p: IArray[Double]): Double =
      val v = metric.velocity(p)
      0.5 * (0 until p.length).foldLeft(0.0)((sum, i) => sum + p(i) * v(i))

    /** A momentum from `N(0, M)`.
      *
      * For the diagonal metric that is `z / sqrt(inverse)`. For the dense one, with
      * `M^-1 = L L'`, it is the solution of `L' p = z`: then `cov(p) = L'^-1 L^-1 = M`. The
      * back substitution is here rather than in `core.linalg`, which the specification limits to
      * five operations.
      */
    def sampleMomentum[F[_]: Monad: Rng]: F[IArray[Double]] =
      Monad[F].replicateA(metric.dimension, Rng[F].normal).map { drawn =>
        val z = IArray.from(drawn)
        metric match
          case Metric.Diagonal(inverse) =>
            IArray.tabulate(z.length)(i => z(i) / math.sqrt(inverse(i)))
          case Metric.Dense(_, factor) => upperSolve(factor, z)
      }

  /** Solves `L' p = z` for a lower triangular `L`, by back substitution. */
  private def upperSolve(factor: Cholesky, z: IArray[Double]): IArray[Double] =
    val n = factor.dimension
    val solved = (n - 1 to 0 by -1).foldLeft(Map.empty[Int, Double]) { (known, i) =>
      val above = (i + 1 until n).foldLeft(0.0)((sum, k) => sum + factor.matrix(k, i) * known(k))
      known + (i -> (z(i) - above) / factor.diagonal(i))
    }
    IArray.tabulate(n)(solved)

/** The step size and the metric a transition is run with. */
final case class Tuning(stepSize: Double, metric: Metric)

/** What one transition did, in CmdStan's own terms: this is what a CSV row carries. */
final case class Transition(
    point: Point,
    acceptStat: Double,
    stepSize: Double,
    treeDepth: Int,
    leapfrogSteps: Int,
    divergent: Boolean,
    energy: Double
)

/** A Markov transition: a function of the current point and the tuning, in `F`. */
trait Kernel[F[_]]:
  def transition(current: Point, tuning: Tuning): F[Transition]

/** The leapfrog integrator, as Stan's `expl_leapfrog` breaks it up.
  *
  * The three pieces are separate because Stan's own tests assert them separately, and because the
  * composition is where a step size can be halved by mistake: `evolve` is a half step of momentum,
  * a **full** step of position, and another half step of momentum.
  */
private[bayes4s] object Leapfrog:

  /** `p -= by * g`, which is `p += by * gradient`. */
  def updateMomentum(point: Point, p: IArray[Double], by: Double): IArray[Double] =
    IArray.tabulate(p.length)(i => p(i) + by * point.gradient(i))

  /** `q += by * M^-1 p`, and the target read again at the new position. */
  def updatePosition(
      target: DiffFn,
      metric: Metric,
      point: Point,
      p: IArray[Double],
      by: Double
  ): Point =
    val velocity = metric.velocity(p)
    Point.at(target, IArray.tabulate(point.q.length)(i => point.q(i) + by * velocity(i)))

  /** One leapfrog step of size `epsilon`, which may be negative to integrate backwards. */
  def evolve(
      target: DiffFn,
      metric: Metric,
      point: Point,
      p: IArray[Double],
      epsilon: Double
  ): (Point, IArray[Double]) =
    val half = updateMomentum(point, p, 0.5 * epsilon)
    val moved = updatePosition(target, metric, point, half, epsilon)
    (moved, updateMomentum(moved, half, 0.5 * epsilon))

  /** The Hamiltonian, `V(q) + tau(p)`. A `NaN` counts as `+Inf`, as Stan's `build_tree` does: a
    * point the model cannot evaluate is infinitely improbable, not an unknown.
    */
  def energy(metric: Metric, point: Point, p: IArray[Double]): Double =
    val h = point.potential + metric.kinetic(p)
    if h.isNaN then Double.PositiveInfinity else h
