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

import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** A chain, one iteration at a time.
  *
  * `Mcmc.run` folds a whole chain in one call, which is what a pure program wants. A stream wants
  * the opposite: one iteration, a state, and the chance to stop in between — so that an effectful
  * sampler can be cancelled between iterations and never in the middle of a transition.
  *
  * Both are the **same loop**. `run` is written as a fold over `step`, so the batched chain and the
  * stepped one cannot drift apart: there is one implementation of the warmup schedule, the
  * step-size restarts and the freezing of the tuning, and this suite asserts the two interfaces
  * agree draw for draw.
  */
object ProgressSuite extends SimpleIOSuite with Approx:

  /** The eight schools, non-centred: a model with a scale, a vector and a known shape. */
  private val schools = EightSchools.Data
    .of(
      IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0),
      IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
    )
    .toOption
    .get
  private val model = EightSchools.nonCentred(schools)
  private val target = Model.target(model)
  private val dimension = model.params.unconstrainedSize
  private val start = IArray.tabulate(dimension)(i => 0.1 * math.sin(i.toDouble))
  private val warmup = 60
  private val samples = 40
  private val settings = AdaptationSettings()

  private def stepped(
      seed: Long
  ): Either[BayesError, (Vector[IArray[Double]], Vector[Transition], Tuning)] =
    val kernel = Nuts.kernel[Rand](target, NutsSettings())
    val program = Mcmc.begin[Rand](target, start, warmup, samples, settings).flatMap {
      case Left(error) => cats.Monad[Rand].pure(Left(error))
      case Right(from) =>
        cats.Monad[Rand].tailRecM((from, Vector.empty[IArray[Double]], Vector.empty[Transition])) {
          (progress, draws, transitions) =>
            Mcmc.step[Rand](kernel, target, progress).map {
              case Left(error) =>
                Right(Left(error))
              case Right(None) =>
                Right(Right((draws, transitions, progress.tuning)))
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
    program.runA(Seed(seed)).value

  test("the stepped chain is the folded chain, draw for draw") {
    cats.effect.IO {
      val kernel = Nuts.kernel[Rand](target, NutsSettings())
      val folded = Mcmc
        .run[Rand](kernel, target, start, warmup, samples, settings)
        .runA(Seed(20261107L))
        .value
      (folded, stepped(20261107L)) match
        case (Left(error), _) => failure(s"the folded chain: ${error.message}")
        case (_, Left(error)) => failure(s"the stepped chain: ${error.message}")
        case (Right(chain), Right((draws, transitions, tuning))) =>
          check(chain.draws.length == samples, s"${chain.draws.length} folded draws")
            .and(check(draws.length == samples, s"${draws.length} stepped draws"))
            .and(
              check(
                (0 until samples).forall(i =>
                  (0 until dimension).forall(j => chain.draws(i)(j) == draws(i)(j))
                ),
                "the two interfaces disagree about a draw"
              )
            )
            // Bit for bit, including the transitions' own numbers and the adapted tuning: the
            // whole point is that there is one loop, not two that happen to agree.
            .and(
              check(
                (0 until samples).forall(i =>
                  chain.transitions(i).acceptStat == transitions(i).acceptStat &&
                    chain.transitions(i).treeDepth == transitions(i).treeDepth &&
                    chain.transitions(i).energy == transitions(i).energy
                ),
                "the two interfaces disagree about a transition"
              )
            )
            .and(check(chain.tuning.stepSize == tuning.stepSize, "the adapted step sizes differ"))
    }
  }

  test("the progress says which phase it is in, and stops when it is done") {
    cats.effect.IO {
      Mcmc.begin[Rand](target, start, 5, 3, settings).runA(Seed(3L)).value match
        case Left(error) => failure(error.message)
        case Right(from) =>
          val kernel = Nuts.kernel[Rand](target, NutsSettings())
          // Nine calls for a chain of five warmup and three sampling iterations: eight that do
          // something and one that says there is nothing left.
          val program =
            cats.Monad[Rand].tailRecM((from, Vector.empty[Boolean])) { (progress, phases) =>
              Mcmc.step[Rand](kernel, target, progress).map {
                case Left(error) => Right(Left(error))
                case Right(None) => Right(Right((phases, progress)))
                case Right(Some(iteration)) =>
                  Left((iteration.progress, phases :+ iteration.warmup))
              }
            }
          program.runA(Seed(3L)).value match
            case Left(error) => failure(error.message)
            case Right((phases, finished)) =>
              check(phases.length == 8, s"${phases.length} iterations, expected 8")
                .and(check(phases.take(5).forall(identity), "the first five are not warmup"))
                .and(check(phases.drop(5).forall(!_), "the last three are not sampling"))
                .and(check(finished.finished, "the progress does not know it is finished"))
                .and(check(!from.finished, "a chain that has not started is finished"))
                .and(check(from.warmingUp, "a chain that has not started is not warming up"))
    }
  }

  test("a starting point the target cannot read is an error, not a chain") {
    cats.effect.IO {
      val nowhere = IArray.fill(dimension)(Double.NaN)
      val begun = Mcmc.begin[Rand](target, nowhere, warmup, samples, settings).runA(Seed(1L)).value
      check(begun.isLeft, "a NaN starting point began a chain")
        .and {
          val huge = IArray.fill(dimension)(1e10)
          check(
            Mcmc.begin[Rand](target, huge, warmup, samples, settings).runA(Seed(1L)).value.isLeft,
            "a starting point with no usable step size began a chain"
          )
        }
    }
  }
