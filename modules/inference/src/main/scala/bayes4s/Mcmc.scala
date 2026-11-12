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
import cats.data.State
import cats.syntax.all.*

/** Where a chain starts.
  *
  * A start far from the posterior is not merely slow: on a model with a latent rate it can leave
  * the sampler in a region where the gradient points nowhere useful and the step size collapses.
  * `PriorMedian` is the answer for those, and it needs the model's prior — from its generative
  * part if it has one, and otherwise from a short run on the prior alone.
  * **Example.**
  * ```scala
  * // Stan's default: uniform on (-2, 2) in the *unconstrained* space, so a positive parameter starts
  * // anywhere between about 0.14 and 7.4.
  * val spread = RunSettings(init = Init.Uniform(2.0))
  *
  * // Named constrained values, as `Model.values` reports them. A value outside its constraint is an
  * // error rather than a clamp.
  * val chosen = RunSettings(init = Init.Given(Map("mu" -> IArray(4.5), "sigma" -> IArray(1.0))))
  *
  * // The median of a sample from the prior, which needs the model's generative part.
  * val fromPrior = RunSettings(init = Init.PriorMedian(draws = 100))
  * ```
  */
enum Init:
  case Uniform(radius: Double)
  case Given(values: Map[String, IArray[Double]])
  case PriorMedian(draws: Int)

/** One chain: the draws on the **unconstrained** space, what each transition did, and the tuning
  * the sampling phase ran with.
  *
  * Unconstrained because that is what the sampler produces and what a diagnostic of the sampler
  * should see. `Model.values` names and constrains them, which is where a report starts.
  */
final case class Chain(
    draws: Vector[IArray[Double]],
    transitions: Vector[Transition],
    tuning: Tuning
)

/** Where a chain has got to: enough to carry on, and nothing else.
  *
  * `Mcmc.run` folds a whole chain in one call, which is what a pure program wants. A stream wants
  * one iteration at a time and the chance to stop in between — so that an effectful sampler can be
  * cancelled between iterations and never in the middle of a transition (§5.12). Both go through
  * `step`: `run` is a fold over it, so there is one implementation of the warmup schedule and the
  * two interfaces cannot drift apart.
  *
  * The tuning is carried rather than recomputed, because after warmup it is **frozen**: the
  * sampling phase uses the adaptation's completed tuning for every transition, and recomputing it
  * per iteration would keep adapting.
  */
final case class Progress private[bayes4s] (
    point: Point,
    adaptation: Adaptation,
    tuning: Tuning,
    iteration: Int,
    warmup: Int,
    samples: Int
):
  def warmingUp: Boolean = iteration < warmup
  def finished: Boolean = iteration >= warmup + samples

/** One iteration's outcome: what the transition was, whether it was warmup, and where that left
  * the chain.
  */
final case class Iteration(transition: Transition, warmup: Boolean, progress: Progress)

/** Everything a fit needs beyond the model itself: how many chains, how long, and how they start.
  *
  * The defaults are Stan's, so `RunSettings()` is four chains of a thousand warmup iterations and
  * a thousand kept samples under the no-U-turn sampler with a diagonal metric. The three nested
  * settings are the trajectory's limits ([[NutsSettings]]), the warmup schedule
  * ([[AdaptationSettings]]) and where each chain starts ([[Init]]).
  * **Example.**
  * ```scala
  * // What `RunSettings()` means, written out.
  * val stanDefaults = RunSettings(chains = 4, warmup = 1000, samples = 1000)
  *
  * // Small and quick, for a smoke test — and too few samples to report.
  * val quick = RunSettings(chains = 2, warmup = 200, samples = 200)
  *
  * // A posterior that is strongly correlated: learn the whole covariance rather than one scale per
  * // coordinate, and allow the integrator longer trajectories.
  * val correlated = RunSettings(
  *   chains = 4,
  *   warmup = 1500,
  *   samples = 1000,
  *   nuts = NutsSettings(maxTreeDepth = 12),
  *   adaptation = AdaptationSettings(metric = MetricShape.Dense)
  * )
  * ```
  */
final case class RunSettings(
    chains: Int = 4,
    warmup: Int = 1000,
    samples: Int = 1000,
    nuts: NutsSettings = NutsSettings(),
    adaptation: AdaptationSettings = AdaptationSettings(),
    init: Init = Init.Uniform(2.0)
)

/** The driver: a warmup with adaptation, then sampling. A fold over values, and with
  * `F = State[Seed, *]` the result is a function of the seed and nothing else.
  * **Example.**
  * The usual way to fit a model. The result is an `Either`, because a fit can fail for reasons worth
  * reporting, and it is a `State` program, so nothing runs until a seed is given:
  *
  * ```scala
  * //{
  * import bayes4s.syntax.{*, given}
  * final case class Mean[A](mu: A, sigma: A)
  * val observed = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4)
  * val model: Model[Mean] = new Model[Mean]:
  *   def params: ParamSpec[Mean] =
  *     (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
  *       .mapN[Mean]([A] => (mu: A, sigma: A) => Mean(mu, sigma))
  *   def logPrior[A: Real](p: Mean[A]): A =
  *     LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
  *       LogDensity.halfNormal(p.sigma, 5.0.lift[A])
  *   def logLikelihood[A: Real](p: Mean[A]): A =
  *     Real[A].normalSum(observed, Real[A].filled(observed.length)(p.mu), p.sigma)
  * //}
  * val fit: Either[BayesError, List[Chain]] =
  *   Mcmc.runNuts(model, RunSettings(chains = 4, warmup = 1000, samples = 1000))
  *     .runA(Seed(20261112L))
  *     .value
  *
  * // The same seed always gives the same chains, on any machine and in any order.
  * val again: Either[BayesError, List[Chain]] =
  *   Mcmc.runNuts(model, RunSettings(chains = 4, warmup = 1000, samples = 1000))
  *     .runA(Seed(20261112L))
  *     .value
  * ```
  *
  * **Example.**
  * `run` is the lower level: a kernel, a target and a starting point, for when you want a sampler
  * other than the default. This is a random walk, which needs no gradient and a far lower acceptance
  * target than a Hamiltonian method:
  *
  * ```scala
  * //{
  * import bayes4s.syntax.{*, given}
  * final case class Mean[A](mu: A, sigma: A)
  * val observed = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4)
  * val model: Model[Mean] = new Model[Mean]:
  *   def params: ParamSpec[Mean] =
  *     (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
  *       .mapN[Mean]([A] => (mu: A, sigma: A) => Mean(mu, sigma))
  *   def logPrior[A: Real](p: Mean[A]): A =
  *     LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
  *       LogDensity.halfNormal(p.sigma, 5.0.lift[A])
  *   def logLikelihood[A: Real](p: Mean[A]): A =
  *     Real[A].normalSum(observed, Real[A].filled(observed.length)(p.mu), p.sigma)
  * //}
  * val target = Model.target(model)
  *
  * val chain: Either[BayesError, Chain] =
  *   Mcmc
  *     .run[Rand](
  *       RandomWalkMetropolis.kernel[Rand](target, 1.0),
  *       target,
  *       IArray(4.0, 0.0),
  *       warmup = 1000,
  *       samples = 5000,
  *       AdaptationSettings(targetAccept = 0.234)
  *     )
  *     .runA(Seed(77L))
  *     .value
  * ```
  */
object Mcmc:

  /** Warmup then sampling, from a given unconstrained point.
    *
    * The specification writes `init: Init` here. It takes a point instead, because `Init` is about
    * a *model* — `Given` names parameters and `PriorMedian` draws from a prior — while this
    * function knows only a `DiffFn`. [[startingPoint]] turns one into the other, and `runNuts`
    * does it for you (docs/design.md).
    */
  /** A whole chain, folded in one call: a fold over [[step]].
    *
    * The draws and transitions it returns are the **sampling** phase only, as they always were;
    * the warmup's transitions are seen by the adaptation and then dropped.
    */
  def run[F[_]: Monad: Rng](
      kernel: Kernel[F],
      target: DiffFn,
      start: IArray[Double],
      warmup: Int,
      samples: Int,
      settings: AdaptationSettings
  ): F[Either[BayesError, Chain]] =
    begin[F](target, start, warmup, samples, settings).flatMap {
      case Left(error) => Monad[F].pure(Left(error))
      case Right(from) =>
        Monad[F].tailRecM((from, Vector.empty[IArray[Double]], Vector.empty[Transition])) {
          (progress, draws, transitions) =>
            step[F](kernel, target, progress).map {
              case Left(error) =>
                Right(Left(error)): Either[
                  (Progress, Vector[IArray[Double]], Vector[Transition]),
                  Either[BayesError, Chain]
                ]
              case Right(None) =>
                Right(Right(Chain(draws, transitions, progress.tuning)))
              case Right(Some(iteration)) =>
                if iteration.warmup then Left((iteration.progress, draws, transitions))
                else
                  Left(
                    (
                      iteration.progress,
                      draws :+ iteration.transition.point.q,
                      transitions :+ iteration.transition
                    )
                  )
            }
        }
    }

  /** The state a chain starts in: the starting point read, the metric chosen, the step size found.
    *
    * Everything that can go wrong before the first transition goes wrong here — a starting point
    * the target cannot read, a metric that cannot be built, a step-size search that runs away —
    * and each is a `Left` rather than an exception.
    */
  def begin[F[_]: Monad: Rng](
      target: DiffFn,
      start: IArray[Double],
      warmup: Int,
      samples: Int,
      settings: AdaptationSettings
  ): F[Either[BayesError, Progress]] =
    val at = Point.at(target, start)
    if !at.logDensity.isFinite then
      Monad[F].pure(
        Left(
          BayesError.InvalidArgument(
            "Mcmc.run",
            "start",
            at.logDensity,
            "the log density at the starting point is finite"
          )
        )
      )
    else
      Adaptation.initialMetric(settings, start.length) match
        case Left(error) => Monad[F].pure(Left(error))
        case Right(metric) =>
          Adaptation.initialStepSize[F](target, at, metric).map { stepSize =>
            if !(stepSize > 0.0) || stepSize > 1e7 then
              Left(
                BayesError.NumericalOverflow(
                  s"Mcmc.run: no usable step size was found at the starting point " +
                    s"(reached $stepSize)"
                )
              )
            else
              val initial = Tuning(stepSize, metric)
              val adaptation = Adaptation.windowed(settings, warmup, initial)
              Right(
                Progress(
                  at,
                  adaptation,
                  adaptation.tuning,
                  0,
                  math.max(warmup, 0),
                  math.max(samples, 0)
                )
              )
          }

  /** One iteration, or nothing when the chain is finished.
    *
    * During warmup the adaptation sees the transition and the step-size heuristic is re-run
    * whenever the metric changed, which is what Stan's sampler does inside itself. At the warmup
    * boundary the tuning is frozen at the adaptation's completed value and never moves again.
    */
  def step[F[_]: Monad: Rng](
      kernel: Kernel[F],
      target: DiffFn,
      progress: Progress
  ): F[Either[BayesError, Option[Iteration]]] =
    if progress.finished then Monad[F].pure(Right(None))
    else if progress.warmingUp then
      kernel.transition(progress.point, progress.tuning).flatMap { t =>
        progress.adaptation.update(t) match
          case Left(error) => Monad[F].pure(Left(error))
          case Right(updated) =>
            val advanced = progress.iteration + 1
            // The last warmup iteration freezes the tuning: from here the sampler does not adapt.
            def finish(state: Adaptation): Iteration =
              val tuning = if advanced >= progress.warmup then state.completed else state.tuning
              Iteration(
                t,
                warmup = true,
                progress
                  .copy(point = t.point, adaptation = state, tuning = tuning, iteration = advanced)
              )
            if !updated.metricChanged then Monad[F].pure(Right(Some(finish(updated))))
            else
              Adaptation
                .initialStepSize[F](target, t.point, updated.tuning.metric)
                .map(found => Right(Some(finish(updated.restarted(found)))))
      }
    else
      kernel.transition(progress.point, progress.tuning).map { t =>
        Right(
          Some(
            Iteration(
              t,
              warmup = false,
              progress.copy(point = t.point, iteration = progress.iteration + 1)
            )
          )
        )
      }

  def startingPoint[F[_]: Monad: Rng, T[_]](
      model: Model[T],
      init: Init,
      generative: Option[Generative[T, ?]],
      settings: RunSettings = RunSettings()
  ): F[Either[BayesError, IArray[Double]]] =
    val dimension = model.params.unconstrainedSize
    init match
      case Init.Uniform(radius) =>
        Monad[F]
          .replicateA(dimension, Rng[F].uniform)
          .map(us => Right(IArray.from(us.map(u => radius * (2.0 * u - 1.0)))))
      case Init.Given(values) => Monad[F].pure(Model.unconstrain(model, values))
      case Init.PriorMedian(draws) =>
        generative match
          case Some(prior) =>
            Monad[F]
              .replicateA(draws, prior.samplePrior[F])
              .map { drawn =>
                drawn
                  .traverse(p => Model.unconstrain(model, prior.named(p)))
                  .map(points => median(points, dimension))
              }
          case None => fromPriorOnly(model, draws, settings)

  /** Draws from the prior by sampling `Model.priorOnly`, for a model with no generative part. */
  private def fromPriorOnly[F[_]: Monad: Rng, T[_]](
      model: Model[T],
      draws: Int,
      settings: RunSettings
  ): F[Either[BayesError, IArray[Double]]] =
    val prior = Model.priorOnly(model)
    val target = Model.target(prior)
    val dimension = model.params.unconstrainedSize
    Monad[F]
      .replicateA(dimension, Rng[F].uniform)
      .flatMap { us =>
        val start = IArray.from(us.map(u => 2.0 * (2.0 * u - 1.0)))
        run[F](
          Nuts.kernel[F](target, settings.nuts),
          target,
          start,
          math.max(20, draws),
          draws,
          settings.adaptation
        )
      }
      .map(_.map(chain => median(chain.draws.toList, dimension)))

  private def median(points: List[IArray[Double]], dimension: Int): IArray[Double] =
    IArray.tabulate(dimension) { i =>
      val sorted = points.map(_(i)).sorted
      val n = sorted.length
      if n == 0 then 0.0
      else if n % 2 == 1 then sorted(n / 2)
      else 0.5 * (sorted(n / 2 - 1) + sorted(n / 2))
    }

  /** `settings.chains` chains in sequence, chain `c` from the seed `fold(c)`: the same chains
    * whether they are run here or in parallel by the `effect` module.
    */
  /** A fit's samples as named, constrained values: the shape everything downstream reads.
    *
    * A chain carries unconstrained points, which is what the sampler needs and what nothing else
    * wants. `Draws` carries named constrained ones, which is what a CSV row is, what ArviZ reads,
    * and what a predictive check has to turn back into parameters. Nothing bridged the two until
    * a predictive check needed it, which is why this is here rather than in `core` — `Draws` knows
    * nothing about models, and a model knows nothing about chains (docs/design.md).
    *
    * The values are the **declared** blocks, in the order `params.names` lists them, and derived
    * quantities are left out on purpose: this is the shape `Model.unconstrain` can invert, and a
    * derived quantity has no unconstrained point of its own.
    */
  def draws[T[_]](model: Model[T], chains: List[Chain]): Either[BayesError, Draws] =
    val names = IArray.from(model.params.names)
    val rows = chains.map(chain => IArray.from(chain.draws.map(q => row(model, q))))
    Draws.of(names, IArray.from(rows))

  /** One unconstrained point as the named constrained values, in `params.names` order.
    *
    * The row a CSV line is, and what a streamed draw carries. Public so that the effectful sampler
    * can build a row per iteration without reimplementing the layout walk — there is one place
    * that knows how a point becomes a row.
    */
  def row[T[_]](model: Model[T], q: IArray[Double]): IArray[Double] =
    val values = Model.values(model, q)
    IArray.from(
      model.params.layout.flatMap(block =>
        (0 until block.size).map(i =>
          values.get(block.name).map(column => column(i)).getOrElse(Double.NaN)
        )
      )
    )

  def runNuts[T[_]](
      model: Model[T],
      settings: RunSettings
  ): State[Seed, Either[BayesError, List[Chain]]] =
    val target = Model.target(model)
    State.get[Seed].flatMap { seed =>
      (0 until settings.chains).toList
        .traverse { c =>
          val chainSeed = seed.fold(c.toLong)
          val program = for
            start <- startingPoint[Rand, T](model, settings.init, None, settings)
            chain <- start match
              case Left(error) => State.pure[Seed, Either[BayesError, Chain]](Left(error))
              case Right(at) =>
                run[Rand](
                  Nuts.kernel[Rand](target, settings.nuts),
                  target,
                  at,
                  settings.warmup,
                  settings.samples,
                  settings.adaptation
                )
          yield chain
          State.pure[Seed, Either[BayesError, Chain]](program.runA(chainSeed).value)
        }
        .map(_.sequence)
    }

  /** The same, for a model that carries its generative part, so that `Init.PriorMedian` can draw
    * from the prior directly instead of sampling `Model.priorOnly`.
    */
  def runNutsGenerative[T[_], Y](
      model: GenerativeModel[T, Y],
      settings: RunSettings
  ): State[Seed, Either[BayesError, List[Chain]]] =
    val target = Model.target(model.model)
    State.get[Seed].flatMap { seed =>
      (0 until settings.chains).toList
        .traverse { c =>
          val program = for
            start <- startingPoint[Rand, T](
              model.model,
              settings.init,
              Some(model.generative),
              settings
            )
            chain <- start match
              case Left(error) => State.pure[Seed, Either[BayesError, Chain]](Left(error))
              case Right(at) =>
                run[Rand](
                  Nuts.kernel[Rand](target, settings.nuts),
                  target,
                  at,
                  settings.warmup,
                  settings.samples,
                  settings.adaptation
                )
          yield chain
          State.pure[Seed, Either[BayesError, Chain]](program.runA(seed.fold(c.toLong)).value)
        }
        .map(_.sequence)
    }
