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

import cats.MonadThrow
import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream

/** One iteration of one chain, as it comes out.
  *
  * `values` are the named constrained values in `params.names` order — the row a CSV line is — so
  * a stream of these can be written out or summarised without going back to the model. `warmup`
  * says whether this iteration was part of the adaptation, which a diagnostic wants and a summary
  * must drop.
  */
final case class Draw(
    chain: Int,
    iteration: Int,
    warmup: Boolean,
    values: IArray[Double],
    transition: Transition
)

/** Sampling at the effectful edge: chains as streams, and chains in parallel.
  *
  * There is no statistical logic here. The warmup schedule, the step sizes, the metric and every
  * transition come from `inference`'s `Mcmc.begin` and `Mcmc.step`; this module decides only
  * **when** they run, on which fibre, and what happens to an error.
  *
  * Three properties it is responsible for, each with a test of its own:
  *
  *   - **Parallel equals sequential, bit for bit.** Chain `c` is driven by its own generator seeded
  *     `seed.fold(c)`, so what it draws cannot depend on the order the scheduler ran the fibres in,
  *     and the result equals the pure `Mcmc.runNuts` on the same seed.
  *   - **Cancellation lands between iterations.** `Stream.unfoldEval` yields between steps, so a
  *     cancelled run stops at an iteration boundary and never half way through a transition.
  *   - **No error is swallowed.** The core returns `Either`; every `Left` is raised in `F` through
  *     `MonadThrow`. `BayesError` extends `NoStackTrace` for exactly this.
  */
trait Sampler[F[_]]:

  /** One chain, iteration by iteration, warmup included. */
  def chain[T[_]](model: Model[T], settings: RunSettings, chainId: Int, seed: Seed): Stream[F, Draw]

  /** Every chain, in parallel, as named draws — the sampling phase only. */
  def run[T[_]](model: Model[T], settings: RunSettings, seed: Seed): F[Draws]

object Sampler:

  def apply[F[_]: Async]: Sampler[F] = new Sampler[F]:

    def chain[T[_]](
        model: Model[T],
        settings: RunSettings,
        chainId: Int,
        seed: Seed
    ): Stream[F, Draw] =
      val target = Model.target(model)
      Stream.eval(RefRng.make[F](seed.fold(chainId.toLong))).flatMap { generator =>
        given Rng[F] = generator
        val kernel = Nuts.kernel[F](target, settings.nuts)
        val begun = for
          start <- Mcmc
            .startingPoint[F, T](model, settings.init, None, settings)
            .flatMap(MonadThrow[F].fromEither)
          progress <- Mcmc
            .begin[F](target, start, settings.warmup, settings.samples, settings.adaptation)
            .flatMap(MonadThrow[F].fromEither)
        yield progress
        Stream.eval(begun).flatMap { from =>
          Stream.unfoldEval(from) { progress =>
            Mcmc
              .step[F](kernel, target, progress)
              .flatMap(MonadThrow[F].fromEither)
              .map(
                _.map { iteration =>
                  val drawn = Draw(
                    chainId,
                    progress.iteration,
                    iteration.warmup,
                    Mcmc.row(model, iteration.transition.point.q),
                    iteration.transition
                  )
                  (drawn, iteration.progress)
                }
              )
          }
        }
      }

    def run[T[_]](model: Model[T], settings: RunSettings, seed: Seed): F[Draws] =
      val chains = math.max(settings.chains, 1)
      (0 until chains).toList
        .parTraverseN(chains)(c =>
          chain(model, settings, c, seed).filter(!_.warmup).map(_.values).compile.toVector
        )
        .flatMap { perChain =>
          MonadThrow[F].fromEither(
            Draws.of(
              IArray.from(model.params.names),
              IArray.from(perChain.map(rows => IArray.from(rows)))
            )
          )
        }
