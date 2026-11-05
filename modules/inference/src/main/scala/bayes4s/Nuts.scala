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

final case class NutsSettings(maxTreeDepth: Int = 10, maxEnergyError: Double = 1000.0)

/** A state of the trajectory: a position with its momentum. */
final private[bayes4s] case class Phase(point: Point, momentum: IArray[Double])

/** What one subtree of the trajectory came to.
  *
  * `base_nuts.hpp` writes its results into references passed by the caller; this is the same
  * information returned as a value, which is what makes the recursion a function. The fields are
  * Stan's, so that the case `unit_e_nuts_test.cpp` asserts can be read off directly.
  */
final private[bayes4s] case class Subtree(
    valid: Boolean,
    divergent: Boolean,
    proposal: Phase,
    end: Phase,
    sharpBegin: IArray[Double],
    sharpEnd: IArray[Double],
    momentumBegin: IArray[Double],
    momentumEnd: IArray[Double],
    rho: IArray[Double],
    logSumWeight: Double,
    leapfrogSteps: Int,
    sumMetropolisProbability: Double
)

/** The no-U-turn sampler, following Stan: multinomial sampling, the generalised U-turn criterion,
  * a maximum tree depth, and a divergence when the energy error exceeds a threshold.
  *
  * The recursion is a pure function returning a [[Subtree]], and the case Stan's own
  * `unit_e_nuts_test.cpp` asserts is what checks the translation: `rho`, `p_sharp` at both ends,
  * the number of leapfrog steps, the log of the summed weights and the summed Metropolis
  * probability, all to the six digits Stan's `EXPECT_FLOAT_EQ` carries and to sixteen against the
  * independent port the fixture ships.
  *
  * `Rng` is asked for a number in exactly the two places Stan asks for one: the momentum at the
  * start of the transition, and the multinomial choice at each subtree. Everything else is
  * determined by those draws.
  */
object Nuts:

  def kernel[F[_]: Monad: Rng](target: DiffFn, settings: NutsSettings): Kernel[F] =
    new Kernel[F]:
      def transition(current: Point, tuning: Tuning): F[Transition] =
        Nuts.transition(target, settings, current, tuning)

  /** The generalised U-turn criterion: the trajectory still moves apart at both ends.
    *
    * `rho` is the sum of the momenta over the states concerned and the two arguments are the
    * velocities at its ends. Both products must be positive; a zero is a turn, not a
    * continuation, which is what the fixture's second case pins down.
    */
  private[bayes4s] def criterion(
      sharpStart: IArray[Double],
      sharpEnd: IArray[Double],
      rho: IArray[Double]
  ): Boolean =
    def dot(x: IArray[Double]): Double =
      (0 until rho.length).foldLeft(0.0)((sum, i) => sum + x(i) * rho(i))
    dot(sharpEnd) > 0.0 && dot(sharpStart) > 0.0

  private def add(x: IArray[Double], y: IArray[Double]): IArray[Double] =
    IArray.tabulate(x.length)(i => x(i) + y(i))

  /** `log(exp(a) + exp(b))`, with `-Inf` as the identity: the weight of an empty subtree. */
  private def logSumExp(a: Double, b: Double): Double =
    if a == Double.NegativeInfinity then b
    else if b == Double.NegativeInfinity then a
    else
      val largest = math.max(a, b)
      largest + math.log(math.exp(a - largest) + math.exp(b - largest))

  /** `build_tree` of `base_nuts.hpp`, as a function.
    *
    * At depth zero it takes one leapfrog step and reports it. Above zero it builds the two halves,
    * stops at the first invalid one, samples the proposal multinomially between them, and applies
    * the three checks of the criterion: once across the merged subtree, then once at each of the
    * two junctions. The junction checks are what the generalised criterion adds to the original
    * algorithm, and dropping them costs nothing visible on an easy posterior.
    */
  private[bayes4s] def buildTree[F[_]: Monad: Rng](
      target: DiffFn,
      settings: NutsSettings,
      depth: Int,
      from: Phase,
      epsilon: Double,
      initialEnergy: Double,
      metric: Metric
  ): F[Subtree] =
    if depth == 0 then
      val (point, momentum) = Leapfrog.evolve(target, metric, from.point, from.momentum, epsilon)
      val energy = Leapfrog.energy(metric, point, momentum)
      val error = energy - initialEnergy
      val divergent = error > settings.maxEnergyError
      val weight = initialEnergy - energy
      val sharp = metric.velocity(momentum)
      val phase = Phase(point, momentum)
      Monad[F].pure(
        Subtree(
          valid = !divergent,
          divergent = divergent,
          proposal = phase,
          end = phase,
          sharpBegin = sharp,
          sharpEnd = sharp,
          momentumBegin = momentum,
          momentumEnd = momentum,
          rho = momentum,
          logSumWeight = weight,
          leapfrogSteps = 1,
          // Stan adds 1 rather than an acceptance probability above one.
          sumMetropolisProbability = if weight > 0.0 then 1.0 else math.exp(weight)
        )
      )
    else
      for
        left <- buildTree(target, settings, depth - 1, from, epsilon, initialEnergy, metric)
        subtree <-
          if !left.valid then Monad[F].pure(left)
          else
            for
              right <- buildTree(
                target,
                settings,
                depth - 1,
                left.end,
                epsilon,
                initialEnergy,
                metric
              )
              merged <-
                if !right.valid then
                  Monad[F].pure(
                    right.copy(
                      leapfrogSteps = left.leapfrogSteps + right.leapfrogSteps,
                      sumMetropolisProbability =
                        left.sumMetropolisProbability + right.sumMetropolisProbability,
                      divergent = left.divergent || right.divergent
                    )
                  )
                else combine(left, right)
            yield merged
      yield subtree

  /** The two halves of a valid subtree, merged: the multinomial proposal and the three checks. */
  private def combine[F[_]: Monad: Rng](left: Subtree, right: Subtree): F[Subtree] =
    val weight = logSumExp(left.logSumWeight, right.logSumWeight)
    val chosen =
      if right.logSumWeight > weight then Monad[F].pure(right.proposal)
      else
        Rng[F].uniform.map { u =>
          if u < math.exp(right.logSumWeight - weight) then right.proposal else left.proposal
        }
    chosen.map { proposal =>
      val rho = add(left.rho, right.rho)
      val persists = criterion(left.sharpBegin, right.sharpEnd, rho) &&
        criterion(left.sharpBegin, right.sharpBegin, add(left.rho, right.momentumBegin)) &&
        criterion(left.sharpEnd, right.sharpEnd, add(right.rho, left.momentumEnd))
      Subtree(
        valid = persists,
        divergent = left.divergent || right.divergent,
        proposal = proposal,
        end = right.end,
        sharpBegin = left.sharpBegin,
        sharpEnd = right.sharpEnd,
        momentumBegin = left.momentumBegin,
        momentumEnd = right.momentumEnd,
        rho = rho,
        logSumWeight = weight,
        leapfrogSteps = left.leapfrogSteps + right.leapfrogSteps,
        sumMetropolisProbability = left.sumMetropolisProbability + right.sumMetropolisProbability
      )
    }

  /** One transition: draw a momentum, double the trajectory until the criterion fails or the depth
    * runs out, and return the multinomially chosen state.
    *
    * The two sides of the trajectory keep their own summed momenta, because the two junction
    * checks are about one side plus the first state of the other. The initial momentum belongs to
    * both sides — the starting state is the inner boundary of each — so it is carried in both
    * sums, which is the same arrangement the recursion uses at its own junctions.
    */
  private def transition[F[_]: Monad: Rng](
      target: DiffFn,
      settings: NutsSettings,
      current: Point,
      tuning: Tuning
  ): F[Transition] =
    val metric = tuning.metric
    tuning.metric.sampleMomentum[F].flatMap { initial =>
      val start = Phase(current, initial)
      val initialEnergy = Leapfrog.energy(metric, current, initial)
      val sharp = metric.velocity(initial)

      /** Everything the doubling loop carries. */
      final case class Doubling(
          forward: Phase,
          backward: Phase,
          sample: Phase,
          sharpForwardOuter: IArray[Double],
          sharpForwardInner: IArray[Double],
          sharpBackwardOuter: IArray[Double],
          sharpBackwardInner: IArray[Double],
          momentumForwardInner: IArray[Double],
          momentumBackwardInner: IArray[Double],
          rhoForward: IArray[Double],
          rhoBackward: IArray[Double],
          logSumWeight: Double,
          depth: Int,
          leapfrogSteps: Int,
          sumMetropolisProbability: Double,
          divergent: Boolean,
          stop: Boolean
      )

      val first = Doubling(
        forward = start,
        backward = start,
        sample = start,
        sharpForwardOuter = sharp,
        sharpForwardInner = sharp,
        sharpBackwardOuter = sharp,
        sharpBackwardInner = sharp,
        momentumForwardInner = initial,
        momentumBackwardInner = initial,
        rhoForward = initial,
        rhoBackward = initial,
        logSumWeight = 0.0,
        depth = 0,
        leapfrogSteps = 0,
        sumMetropolisProbability = 0.0,
        divergent = false,
        stop = false
      )

      def step(state: Doubling): F[Doubling] =
        for
          direction <- Rng[F].uniform
          goForward = direction > 0.5
          subtree <- buildTree(
            target,
            settings,
            state.depth,
            if goForward then state.forward else state.backward,
            if goForward then tuning.stepSize else -tuning.stepSize,
            initialEnergy,
            metric
          )
          counted = state.copy(
            leapfrogSteps = state.leapfrogSteps + subtree.leapfrogSteps,
            sumMetropolisProbability =
              state.sumMetropolisProbability + subtree.sumMetropolisProbability,
            divergent = state.divergent || subtree.divergent,
            forward = if goForward then subtree.end else state.forward,
            backward = if goForward then state.backward else subtree.end,
            sharpForwardOuter = if goForward then subtree.sharpEnd else state.sharpForwardOuter,
            sharpForwardInner = if goForward then subtree.sharpBegin else state.sharpForwardInner,
            sharpBackwardOuter = if goForward then state.sharpBackwardOuter else subtree.sharpEnd,
            sharpBackwardInner =
              if goForward then state.sharpBackwardInner else subtree.sharpBegin,
            momentumForwardInner =
              if goForward then subtree.momentumBegin else state.momentumForwardInner,
            momentumBackwardInner =
              if goForward then state.momentumBackwardInner else subtree.momentumBegin,
            rhoForward = if goForward then add(state.rhoForward, subtree.rho) else state.rhoForward,
            rhoBackward =
              if goForward then state.rhoBackward else add(state.rhoBackward, subtree.rho)
          )
          advanced <-
            if !subtree.valid then Monad[F].pure(counted.copy(stop = true))
            else
              val keep =
                if subtree.logSumWeight > counted.logSumWeight then Monad[F].pure(subtree.proposal)
                else
                  Rng[F].uniform.map { u =>
                    if u < math.exp(subtree.logSumWeight - counted.logSumWeight) then
                      subtree.proposal
                    else counted.sample
                  }
              keep.map { sample =>
                val rho = add(counted.rhoBackward, add(counted.rhoForward, negated(initial)))
                val persists =
                  criterion(counted.sharpBackwardOuter, counted.sharpForwardOuter, rho) &&
                    criterion(
                      counted.sharpBackwardOuter,
                      counted.sharpForwardInner,
                      add(counted.rhoBackward, counted.momentumForwardInner)
                    ) &&
                    criterion(
                      counted.sharpBackwardInner,
                      counted.sharpForwardOuter,
                      add(counted.rhoForward, counted.momentumBackwardInner)
                    )
                counted.copy(
                  sample = sample,
                  logSumWeight = logSumExp(counted.logSumWeight, subtree.logSumWeight),
                  depth = counted.depth + 1,
                  stop = !persists
                )
              }
        yield advanced

      Monad[F]
        .tailRecM(first) { state =>
          if state.stop || state.depth >= settings.maxTreeDepth then
            Monad[F].pure(Right(state): Either[Doubling, Doubling])
          else step(state).map(Left(_))
        }
        .map { finished =>
          Transition(
            point = finished.sample.point,
            // Stan's `accept_stat__`: the average Metropolis probability over the trajectory.
            acceptStat =
              if finished.leapfrogSteps == 0 then 0.0
              else finished.sumMetropolisProbability / finished.leapfrogSteps,
            stepSize = tuning.stepSize,
            treeDepth = finished.depth,
            leapfrogSteps = finished.leapfrogSteps,
            divergent = finished.divergent,
            energy = Leapfrog.energy(metric, finished.sample.point, finished.sample.momentum)
          )
        }
    }

  /** The initial momentum sits in both sides' summed momenta, so the whole-trajectory check has to
    * take it out once.
    */
  private def negated(x: IArray[Double]): IArray[Double] =
    IArray.tabulate(x.length)(i => -x(i))
