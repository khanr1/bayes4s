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

/** Which shape of mass matrix the warmup estimates.
  *
  * Diagonal is the default, as in Stan: it costs one number per parameter and handles the scales.
  * Dense costs a number per pair and is worth it when the posterior is strongly correlated —
  * group effects against their own hyperparameters, most often — where a diagonal metric can only
  * rescale the axes of a ridge it cannot see.
  */
enum MetricShape:
  case Diagonal
  case Dense

final case class AdaptationSettings(
    targetAccept: Double = 0.8,
    gamma: Double = 0.05,
    t0: Double = 10.0,
    kappa: Double = 0.75,
    initBuffer: Int = 75,
    baseWindow: Int = 25,
    termBuffer: Int = 50,
    metric: MetricShape = MetricShape.Diagonal
)

/** Nesterov dual averaging on the log step size, as `stepsize_adaptation.hpp` runs it.
  *
  * An immutable state: `learn` returns the next one. `stepSize` is what the next transition uses
  * and `completed` is what the sampling phase uses — `exp(xBar)`, the averaged iterate, which is
  * the whole point of the method and is not the same number as the last `exp(x)`.
  */
final private[bayes4s] case class DualAveraging(
    settings: AdaptationSettings,
    mu: Double,
    counter: Int,
    sBar: Double,
    xBar: Double,
    logStepSize: Double
):

  def stepSize: Double = math.exp(logStepSize)

  def completed: Double = if counter == 0 then math.exp(logStepSize) else math.exp(xBar)

  /** One acceptance statistic. Above one it counts as one, which is what Stan does and what keeps
    * a trajectory that gained energy from pulling the step size up.
    */
  def learn(acceptStat: Double): DualAveraging =
    val capped = if acceptStat.isNaN then 0.0 else math.min(acceptStat, 1.0)
    val next = counter + 1
    val eta = 1.0 / (next + settings.t0)
    val nextSBar = (1.0 - eta) * sBar + eta * (settings.targetAccept - capped)
    val x = mu - nextSBar * math.sqrt(next.toDouble) / settings.gamma
    val weight = math.pow(next.toDouble, -settings.kappa)
    val nextXBar = (1.0 - weight) * xBar + weight * x
    copy(counter = next, sBar = nextSBar, xBar = nextXBar, logStepSize = x)

  /** A restart, as Stan does at every window end: the counter and both averages go back to zero
    * and `mu` is set from the step size the heuristic just found.
    */
  def restarted(stepSize: Double): DualAveraging =
    DualAveraging(settings, math.log(10.0 * stepSize), 0, 0.0, 0.0, math.log(stepSize))

private[bayes4s] object DualAveraging:
  def of(settings: AdaptationSettings, stepSize: Double): DualAveraging =
    DualAveraging(settings, math.log(10.0 * stepSize), 0, 0.0, 0.0, math.log(stepSize))

/** The windows `windowed_adaptation.hpp` divides the warmup into, as a value.
  *
  * An initial buffer where only the step size is learnt, then windows that double in length and
  * each end with a re-estimate of the metric, then a terminal buffer where only the step size is
  * learnt again. Below twenty iterations there is no metric adaptation at all, and when the three
  * buffers do not fit they are rescaled to 15 % / 75 % / 10 %.
  *
  * `ends` is computed once, which is what lets the nine schedules of the fixture be asserted as
  * values rather than simulated.
  */
final private[bayes4s] case class WindowSchedule(
    warmup: Int,
    adapt: Boolean,
    initBuffer: Int,
    baseWindow: Int,
    termBuffer: Int,
    ends: List[Int]
):
  /** Whether the draw of iteration `i` (0-based) is accumulated into the metric estimate. */
  def inWindow(i: Int): Boolean = adapt && i >= initBuffer && i < warmup - termBuffer
  def endsWindow(i: Int): Boolean = adapt && ends.contains(i)

private[bayes4s] object WindowSchedule:

  def of(settings: AdaptationSettings, warmup: Int): WindowSchedule =
    if warmup < 20 then WindowSchedule(warmup, false, 0, 0, 0, Nil)
    else
      val fits = settings.initBuffer + settings.baseWindow + settings.termBuffer <= warmup
      val initBuffer = if fits then settings.initBuffer else (0.15 * warmup).toInt
      val termBuffer = if fits then settings.termBuffer else (0.10 * warmup).toInt
      val baseWindow =
        if fits then settings.baseWindow else warmup - (initBuffer + termBuffer)
      val last = warmup - termBuffer - 1
      // The walk of `windowed_adaptation`: each window doubles, and the one that would leave less
      // than two more windows before the terminal buffer is stretched to reach it instead.
      val walked =
        (0 until warmup).foldLeft((initBuffer + baseWindow - 1, baseWindow, Vector.empty[Int])) {
          case ((next, size, collected), counter) =>
            if counter != next then (next, size, collected)
            else
              val found = collected :+ counter
              if next == last then (next, size, found)
              else
                val doubled = size * 2
                val proposed = counter + doubled
                val stretched =
                  if proposed != last && proposed + 2 * doubled >= warmup - termBuffer then last
                  else proposed
                (stretched, doubled, found)
        }
      WindowSchedule(warmup, true, initBuffer, baseWindow, termBuffer, walked._3.toList)

/** Welford's accumulator for the variances, as an immutable value. */
final private[bayes4s] case class DiagonalMoments(
    count: Int,
    mean: IArray[Double],
    m2: IArray[Double]
):
  def add(x: IArray[Double]): DiagonalMoments =
    val next = count + 1
    val nextMean = IArray.tabulate(x.length)(i => mean(i) + (x(i) - mean(i)) / next)
    val nextM2 = IArray.tabulate(x.length)(i => m2(i) + (x(i) - mean(i)) * (x(i) - nextMean(i)))
    DiagonalMoments(next, nextMean, nextM2)

  /** Stan's regulariser: `n/(n+5) * sample_var + 1e-3 * 5/(n+5)`.
    *
    * The second term is what makes the estimate usable from a handful of draws, and what keeps a
    * coordinate that has not moved from giving a variance of zero and a step of infinity.
    */
  def regularised: IArray[Double] =
    val n = count.toDouble
    IArray.tabulate(mean.length) { i =>
      val sample = if count > 1 then m2(i) / (n - 1.0) else 0.0
      n / (n + 5.0) * sample + 1e-3 * 5.0 / (n + 5.0)
    }

private[bayes4s] object DiagonalMoments:
  def empty(dimension: Int): DiagonalMoments =
    DiagonalMoments(0, IArray.fill(dimension)(0.0), IArray.fill(dimension)(0.0))

/** The same, for a full covariance: the dense metric's estimate. */
final private[bayes4s] case class DenseMoments(
    dimension: Int,
    count: Int,
    mean: IArray[Double],
    m2: IArray[Double]
):
  def add(x: IArray[Double]): DenseMoments =
    val next = count + 1
    val nextMean = IArray.tabulate(dimension)(i => mean(i) + (x(i) - mean(i)) / next)
    val nextM2 = IArray.tabulate(dimension * dimension) { index =>
      val i = index / dimension
      val j = index % dimension
      m2(index) + (x(i) - mean(i)) * (x(j) - nextMean(j))
    }
    DenseMoments(dimension, next, nextMean, nextM2)

  /** `n/(n+5) * sample_cov + 1e-3 * 5/(n+5) * I`, which is positive definite for any draws. */
  def regularised: Either[BayesError, Matrix] =
    val n = count.toDouble
    val scale = n / (n + 5.0)
    val ridge = 1e-3 * 5.0 / (n + 5.0)
    val values = IArray.tabulate(dimension * dimension) { index =>
      val i = index / dimension
      val j = index % dimension
      val sample = if count > 1 then m2(index) / (n - 1.0) else 0.0
      // Symmetrised: Welford's cross term is symmetric in exact arithmetic and not in floating
      // point, and `Linalg.cholesky` refuses a matrix that is not symmetric.
      val other = m2(j * dimension + i)
      val symmetric = if count > 1 then 0.5 * (sample + other / (n - 1.0)) else 0.0
      scale * symmetric + (if i == j then ridge else 0.0)
    }
    Matrix.of(dimension, dimension, values)

private[bayes4s] object DenseMoments:
  def empty(dimension: Int): DenseMoments =
    DenseMoments(dimension, 0, IArray.fill(dimension)(0.0), IArray.fill(dimension * dimension)(0.0))

/** The warmup's adaptation, as an immutable state machine.
  *
  * `update` takes the transition that just happened and returns the next state. Two members beyond
  * the specification's sketch, both because the effect belongs to the driver and not here:
  * `metricChanged` says that the last update re-estimated the metric, and `restarted` injects the
  * step size the heuristic found afterwards. Stan does the same thing inside its sampler, where it
  * has the generator to hand; keeping it out here is what leaves this a function.
  */
trait Adaptation:
  def tuning: Tuning
  def update(t: Transition): Either[BayesError.NumericalOverflow, Adaptation]

  /** True when the last `update` re-estimated the metric, so the driver re-runs the step-size
    * heuristic and restarts the dual averaging, as `adapt_diag_e_nuts` does.
    */
  def metricChanged: Boolean

  /** The same state with the dual averaging restarted around a new step size. */
  def restarted(stepSize: Double): Adaptation

  /** The tuning the sampling phase uses: the averaged step size, not the last one. */
  def completed: Tuning

object Adaptation:

  /** The metric a warmup starts from: the identity in the shape the settings ask for. */
  def initialMetric(settings: AdaptationSettings, dimension: Int): Either[BayesError, Metric] =
    settings.metric match
      case MetricShape.Diagonal => Right(Metric.unit(dimension))
      case MetricShape.Dense => Matrix.identity(dimension).flatMap(Metric.dense)

  /** Stan's `base_hmc::init_stepsize`: double or halve until the energy error crosses `log(0.8)`.
    *
    * A fresh momentum is drawn at every trial, which is what makes this a property of the target
    * at that point rather than of one unlucky draw. The search stops at `1e7` and at zero; a step
    * size that reaches either says the target is improper there, and the driver turns it into an
    * error rather than sampling with it.
    */
  def initialStepSize[F[_]: Monad: Rng](
      target: DiffFn,
      at: Point,
      metric: Metric,
      from: Double = 1.0
  ): F[Double] =
    def errorAt(stepSize: Double): F[Double] =
      metric.sampleMomentum[F].map { momentum =>
        val initial = Leapfrog.energy(metric, at, momentum)
        val (moved, p) = Leapfrog.evolve(target, metric, at, momentum, stepSize)
        initial - Leapfrog.energy(metric, moved, p)
      }
    val threshold = math.log(0.8)
    if from == 0.0 || from > 1e7 || from.isNaN then Monad[F].pure(from)
    else
      errorAt(from).flatMap { first =>
        val growing = first > threshold
        Monad[F].tailRecM(from) { stepSize =>
          errorAt(stepSize).map { error =>
            val keepGoing = if growing then error > threshold else error < threshold
            if !keepGoing then Right(stepSize)
            else
              val next = if growing then 2.0 * stepSize else 0.5 * stepSize
              if next > 1e7 || next == 0.0 then Right(next) else Left(next)
          }
        }
      }

  /** The windowed adaptation of Stan's `adapt_diag_e` and `adapt_dense_e`. */
  def windowed(settings: AdaptationSettings, warmup: Int, initial: Tuning): Adaptation =
    Windowed(
      settings,
      WindowSchedule.of(settings, warmup),
      0,
      DualAveraging.of(settings, initial.stepSize),
      initial.metric,
      empty(initial.metric),
      false
    )

  private def empty(metric: Metric): Either[DiagonalMoments, DenseMoments] = metric match
    case Metric.Diagonal(inverse) => Left(DiagonalMoments.empty(inverse.length))
    case Metric.Dense(inverse, _) => Right(DenseMoments.empty(inverse.rows))

  final private case class Windowed(
      settings: AdaptationSettings,
      schedule: WindowSchedule,
      iteration: Int,
      stepSize: DualAveraging,
      metric: Metric,
      moments: Either[DiagonalMoments, DenseMoments],
      metricChanged: Boolean
  ) extends Adaptation:

    def tuning: Tuning = Tuning(stepSize.stepSize, metric)

    def completed: Tuning = Tuning(stepSize.completed, metric)

    def restarted(found: Double): Adaptation =
      copy(stepSize = stepSize.restarted(found), metricChanged = false)

    def update(t: Transition): Either[BayesError.NumericalOverflow, Adaptation] =
      val learnt = stepSize.learn(t.acceptStat)
      val accumulated =
        if schedule.inWindow(iteration) then
          moments.fold(
            diagonal => Left(diagonal.add(t.point.q)),
            dense => Right(dense.add(t.point.q))
          )
        else moments
      val next = copy(iteration = iteration + 1, stepSize = learnt, metricChanged = false)
      if !schedule.endsWindow(iteration) then Right(next.copy(moments = accumulated))
      else
        reestimate(accumulated).map { estimated =>
          next.copy(metric = estimated, moments = empty(estimated), metricChanged = true)
        }

    /** A window's end: the metric becomes the regularised estimate of this window's draws, and the
      * accumulator starts again from nothing — windows do not pool.
      */
    private def reestimate(
        accumulated: Either[DiagonalMoments, DenseMoments]
    ): Either[BayesError.NumericalOverflow, Metric] =
      accumulated match
        case Left(diagonal) => Right(Metric.Diagonal(diagonal.regularised))
        case Right(dense) =>
          dense.regularised
            .flatMap(Metric.dense)
            .left
            .map(error => BayesError.NumericalOverflow(s"Adaptation.update: ${error.message}"))
