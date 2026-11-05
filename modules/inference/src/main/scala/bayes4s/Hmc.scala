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

/** Hamiltonian Monte Carlo with a fixed number of leapfrog steps (Neal 2011).
  *
  * The step that NUTS replaces, and the one worth having on its own: it has no tree, no
  * multinomial sampling and no criterion, so a failure here is a failure of the integrator or of
  * the Metropolis correction and of nothing else. The phase tests it on a correlated Gaussian in a
  * hundred dimensions before asking NUTS for anything.
  */
object Hmc:

  def kernel[F[_]: Monad: Rng](target: DiffFn, leapfrogSteps: Int): Kernel[F] =
    new Kernel[F]:
      def transition(current: Point, tuning: Tuning): F[Transition] =
        val metric = tuning.metric
        for
          momentum <- metric.sampleMomentum[F]
          initialEnergy = Leapfrog.energy(metric, current, momentum)
          evolved = (1 to leapfrogSteps).foldLeft((current, momentum)) { case ((point, p), _) =>
            Leapfrog.evolve(target, metric, point, p, tuning.stepSize)
          }
          (proposed, proposedMomentum) = evolved
          proposedEnergy = Leapfrog.energy(metric, proposed, proposedMomentum)
          error = proposedEnergy - initialEnergy
          acceptProbability = math.min(1.0, math.exp(-error))
          u <- Rng[F].uniform
        yield
          val accepted = u < acceptProbability
          Transition(
            point = if accepted then proposed else current,
            acceptStat = acceptProbability,
            stepSize = tuning.stepSize,
            treeDepth = 0,
            leapfrogSteps = leapfrogSteps,
            divergent = error > 1000.0,
            energy = if accepted then proposedEnergy else initialEnergy
          )

/** A random walk with a Gaussian proposal and a Metropolis correction.
  *
  * Here to be the simplest thing that works: it needs no gradient, it is the example the manual
  * uses to show what a sampler is, and when a model's posterior looks wrong it answers the
  * question "is it the model or the sampler?" without any of HMC's machinery in the way.
  */
object RandomWalkMetropolis:

  /** `scale` is the standard deviation of the proposal, per coordinate, before the metric. */
  def kernel[F[_]: Monad: Rng](target: DiffFn, scale: Double): Kernel[F] =
    new Kernel[F]:
      def transition(current: Point, tuning: Tuning): F[Transition] =
        for
          step <- Monad[F].replicateA(current.dimension, Rng[F].normal)
          // The metric scales the proposal exactly as it scales a momentum, so a tuned metric
          // helps a random walk too.
          scaled = tuning.metric.velocity(IArray.from(step.map(_ * scale)))
          proposed = Point.at(
            target,
            IArray.tabulate(current.dimension)(i => current.q(i) + tuning.stepSize * scaled(i))
          )
          difference = proposed.logDensity - current.logDensity
          acceptProbability =
            if difference.isNaN then 0.0 else math.min(1.0, math.exp(difference))
          u <- Rng[F].uniform
        yield
          val accepted = u < acceptProbability
          Transition(
            point = if accepted then proposed else current,
            acceptStat = acceptProbability,
            stepSize = tuning.stepSize,
            treeDepth = 0,
            leapfrogSteps = 1,
            divergent = false,
            energy = -(if accepted then proposed else current).logDensity
          )
