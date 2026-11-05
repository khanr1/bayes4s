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

import scala.annotation.unused

import cats.Monad

/** A model as a value: what its parameters are, what it believes before the data, and what the
  * data says.
  *
  * The density is split in two on purpose. `logPrior` alone is a model in its own right
  * ([[Model.priorOnly]]), which is what a prior predictive check and simulation-based calibration
  * need; the sum is what a posterior sampler wants. Both are written on the **constrained** space,
  * the one the model is about, and [[Model.target]] is what adds the log Jacobians and hands the
  * sampler a function on the unconstrained space.
  */
trait Model[T[_]]:

  def params: ParamSpec[T]

  /** The prior, on the constrained space. */
  def logPrior[A: Real](p: T[A]): A

  /** The likelihood of the data this model holds, on the constrained space. */
  def logLikelihood[A: Real](p: T[A]): A

  final def logDensity[A: Real](p: T[A]): A = Real[A].plus(logPrior(p), logLikelihood(p))

  /** Quantities worth keeping that are not parameters: a latent path, a contrast, a prediction.
    *
    * Only ever asked for in `Double`, because a derived quantity is reported and not
    * differentiated.
    */
  def derived(@unused p: T[Double]): Map[String, IArray[Double]] = Map.empty

/** The same model read as a program that generates data.
  *
  * Optional: without it a posterior can still be sampled. With it, parameters can be drawn from
  * the prior and data simulated from them, which is what parameter recovery and
  * simulation-based calibration are built on.
  */
trait Generative[T[_], Y]:
  def samplePrior[F[_]: Monad: Rng]: F[T[Double]]
  def simulateData[F[_]: Monad: Rng](p: T[Double]): F[Y]

/** A model, its data, and the program that could have generated that data.
  *
  * `conditionedOn` is a function rather than a derived operation because nothing generic can swap
  * the data inside a `Model`: the likelihood closes over it. Handing the pair (the data, how to
  * build the model from data) is what makes `conditionedOn(simulated)` possible, and that is the
  * one operation simulation-based calibration is.
  */
final case class GenerativeModel[T[_], Y](
    observed: Y,
    conditionedOn: Y => Model[T],
    generative: Generative[T, Y]
):
  /** The model on the data it was given. */
  val model: Model[T] = conditionedOn(observed)

object Model:

  /** The density on the unconstrained space, log Jacobians included: what the sampler sees.
    *
    * This is the one place the Jacobians are added, and it is why `Transform.constrain` returns
    * them instead of accumulating them somewhere: the sum happens once, here, where it can be
    * read.
    */
  def target[T[_]](m: Model[T]): DiffFn = new DiffFn:
    def apply[A: Real](u: IArray[A]): A =
      val (p, jacobian) = m.params.read(u)
      Real[A].plus(m.logDensity(p), jacobian)

  /** The same, without the likelihood: the prior as a model of its own. */
  def priorOnly[T[_]](m: Model[T]): Model[T] = new Model[T]:
    def params: ParamSpec[T] = m.params
    def logPrior[A: Real](p: T[A]): A = m.logPrior(p)
    def logLikelihood[A: Real](p: T[A]): A = Real[A].const(0.0)
    override def derived(p: T[Double]): Map[String, IArray[Double]] = m.derived(p)

  /** The constrained parameters at an unconstrained point. */
  def constrain[T[_]](m: Model[T], q: IArray[Double]): T[Double] = m.params.read[Double](q)._1

  /** The constrained values, by name, in the order each block names them.
    *
    * Not in the plan's sketch and needed by two things: a CSV row is exactly this map, and it is
    * what makes a round trip testable — nothing generic can look inside a `T[Double]`, so the only
    * way back from a point to its names is to read the blocks again (docs/design.md).
    */
  def values[T[_]](m: Model[T], q: IArray[Double]): Map[String, IArray[Double]] =
    m.params.layout
      .foldLeft((Map.empty[String, IArray[Double]], 0)) { case ((collected, from), block) =>
        val size = block.unconstrainedSize
        val (constrained, _) =
          Transform.of(block.constraint).constrain[Double](IArray.tabulate(size)(i => q(from + i)))
        (collected + (block.name -> constrained), from + size)
      }
      ._1

  /** The unconstrained point that a set of named constrained values came from.
    *
    * Partial for two reasons, and it says which: a name the model does not have, and a value
    * outside its constraint.
    */
  def unconstrain[T[_]](
      m: Model[T],
      values: Map[String, IArray[Double]]
  ): Either[BayesError, IArray[Double]] =
    m.params.validated.flatMap { spec =>
      spec.layout
        .foldLeft[Either[BayesError, Vector[Double]]](Right(Vector.empty)) { (accumulated, block) =>
          for
            sofar <- accumulated
            given_ <- values.get(block.name).toRight(BayesError.UnknownParameter(block.name))
            _ <-
              if given_.length == block.size then Right(())
              else
                Left(
                  BayesError.DimensionMismatch(
                    s"Model.unconstrain at '${block.name}'",
                    block.size,
                    given_.length
                  )
                )
            free <- Transform
              .of(block.constraint)
              .unconstrain(given_)
              .left
              .map(named(block, _))
          yield sofar ++ free.toList
        }
        .map(IArray.from)
    }

  /** The same error, saying which block it was about.
    *
    * A transform knows a value is negative; only the model knows the value was `tau`. Without this
    * the error a user reads from a file-reading failure says `value = -1.0 violates value > 0.0`
    * and leaves them to find which of thirty parameters it was.
    */
  private def named(block: Block, error: BayesError): BayesError = error match
    case BayesError.InvalidArgument(_, "value", value, rule) =>
      BayesError.InvalidArgument("Model.unconstrain", block.name, value, rule)
    case BayesError.InvalidArgument(_, detail, value, rule) =>
      BayesError.InvalidArgument("Model.unconstrain", s"${block.name}, $detail", value, rule)
    case other => other
