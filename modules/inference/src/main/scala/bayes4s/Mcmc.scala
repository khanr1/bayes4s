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
  */
object Mcmc:

  /** Warmup then sampling, from a given unconstrained point.
    *
    * The specification writes `init: Init` here. It takes a point instead, because `Init` is about
    * a *model* — `Given` names parameters and `PriorMedian` draws from a prior — while this
    * function knows only a `DiffFn`. [[startingPoint]] turns one into the other, and `runNuts`
    * does it for you (docs/design.md).
    */
  def run[F[_]: Monad: Rng](
      kernel: Kernel[F],
      target: DiffFn,
      start: IArray[Double],
      warmup: Int,
      samples: Int,
      settings: AdaptationSettings
  ): F[Either[BayesError, Chain]] =
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
          Adaptation.initialStepSize[F](target, at, metric).flatMap { stepSize =>
            if !(stepSize > 0.0) || stepSize > 1e7 then
              Monad[F].pure(
                Left(
                  BayesError.NumericalOverflow(
                    s"Mcmc.run: no usable step size was found at the starting point " +
                      s"(reached $stepSize)"
                  )
                )
              )
            else
              val initial = Tuning(stepSize, metric)
              warmupPhase(
                kernel,
                target,
                at,
                Adaptation.windowed(settings, warmup, initial),
                warmup
              ).flatMap {
                case Left(error) => Monad[F].pure(Left(error))
                case Right((point, adaptation)) =>
                  samplePhase(kernel, point, adaptation.completed, samples).map(Right(_))
              }
          }

  /** The warmup: the adaptation sees every transition, and the driver re-runs the step-size
    * heuristic whenever the metric changed, which is what Stan's sampler does inside itself.
    */
  private def warmupPhase[F[_]: Monad: Rng](
      kernel: Kernel[F],
      target: DiffFn,
      from: Point,
      adaptation: Adaptation,
      warmup: Int
  ): F[Either[BayesError, (Point, Adaptation)]] =
    Monad[F].tailRecM((from, adaptation, 0)) { (point, state, i) =>
      if i >= warmup then
        Monad[F].pure(
          Right(Right((point, state))): Either[
            (Point, Adaptation, Int),
            Either[BayesError, (Point, Adaptation)]
          ]
        )
      else
        kernel.transition(point, state.tuning).flatMap { t =>
          state.update(t) match
            case Left(error) => Monad[F].pure(Right(Left(error)))
            case Right(updated) =>
              if !updated.metricChanged then Monad[F].pure(Left((t.point, updated, i + 1)))
              else
                Adaptation
                  .initialStepSize[F](target, t.point, updated.tuning.metric)
                  .map(found => Left((t.point, updated.restarted(found), i + 1)))
        }
    }

  private def samplePhase[F[_]: Monad](
      kernel: Kernel[F],
      from: Point,
      tuning: Tuning,
      samples: Int
  ): F[Chain] =
    Monad[F]
      .tailRecM((from, Vector.empty[IArray[Double]], Vector.empty[Transition])) {
        (point, draws, transitions) =>
          if draws.length >= samples then
            Monad[F].pure(
              Right(Chain(draws, transitions, tuning)): Either[
                (Point, Vector[IArray[Double]], Vector[Transition]),
                Chain
              ]
            )
          else
            kernel
              .transition(point, tuning)
              .map(t => Left((t.point, draws :+ t.point.q, transitions :+ t)))
      }

  /** The unconstrained point an [[Init]] asks for.
    *
    * `PriorMedian` takes the coordinate-wise median of draws from the prior **on the unconstrained
    * space**, which is where the sampler lives and where a median is a sensible middle. The draws
    * come from the generative part when there is one; without it, from a short run of the sampler
    * on `Model.priorOnly`, which is what the specification asks for.
    */
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
    val rows = chains.map { chain =>
      IArray.from(chain.draws.map { q =>
        val values = Model.values(model, q)
        IArray.from(
          model.params.layout.flatMap(block =>
            (0 until block.size).map(i =>
              values.get(block.name).map(column => column(i)).getOrElse(Double.NaN)
            )
          )
        )
      })
    }
    Draws.of(names, IArray.from(rows))

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
