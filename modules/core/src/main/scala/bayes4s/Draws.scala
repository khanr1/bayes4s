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

/** Posterior samples, by chain, iteration and parameter.
  *
  * The shape a CmdStan file has and the shape predictive simulation wants, which is why it lives
  * in `core` rather than in either of them (docs/design.md, D-5): `stan-io` parses into it,
  * `diagnostics` reads it, `predictive` consumes it, and none of those three has to depend on
  * another for a data type.
  *
  * Built only through [[Draws.of]], so the rectangle is always a rectangle: every chain has the
  * same number of iterations and every iteration the same number of values as there are names.
  */
final case class Draws private (
    names: IArray[String],
    chains: IArray[IArray[IArray[Double]]]
):

  def chainCount: Int = chains.length

  def iterations: Int = if chains.isEmpty then 0 else chains(0).length

  def parameters: Int = names.length

  /** One parameter's samples, pooled across chains in chain order. */
  def column(name: String): Either[BayesError.UnknownParameter, IArray[Double]] =
    names.indexOf(name) match
      case -1 => Left(BayesError.UnknownParameter(name))
      case index =>
        Right(
          IArray.tabulate(chainCount * iterations) { k =>
            chains(k / iterations)(k % iterations)(index)
          }
        )

  /** One parameter's samples, kept apart by chain: what every diagnostic needs. */
  def byChain(name: String): Either[BayesError.UnknownParameter, IArray[IArray[Double]]] =
    names.indexOf(name) match
      case -1 => Left(BayesError.UnknownParameter(name))
      case index =>
        Right(
          IArray.tabulate(chainCount)(c => IArray.tabulate(iterations)(i => chains(c)(i)(index)))
        )

  /** Every iteration of every chain, in chain order: the shape a simulation iterates over. */
  def flatten: IArray[IArray[Double]] =
    IArray.tabulate(chainCount * iterations)(k => chains(k / iterations)(k % iterations))

  /** Each iteration repeated `perDraw` times, in place.
    *
    * What a predictive simulation asks for when it wants several futures per posterior sample: the
    * repetition is here rather than in the simulator so that the simulator stays a function of one
    * sample.
    */
  def repeatEach(perDraw: Int): Draws =
    if perDraw <= 1 then this
    else
      Draws(
        names,
        IArray.tabulate(chainCount) { c =>
          IArray.tabulate(iterations * perDraw)(i => chains(c)(i / perDraw))
        }
      )

object Draws:

  def of(
      names: IArray[String],
      chains: IArray[IArray[IArray[Double]]]
  ): Either[BayesError, Draws] =
    val width = names.length
    val duplicate = names.toList.groupBy(identity).collectFirst {
      case (name, repeats) if repeats.length > 1 => name
    }
    val height = if chains.isEmpty then 0 else chains(0).length
    val ragged = (0 until chains.length).collectFirst {
      case c if chains(c).length != height =>
        BayesError.DimensionMismatch(s"Draws.of, chain $c", height, chains(c).length)
      case c if (0 until chains(c).length).exists(i => chains(c)(i).length != width) =>
        BayesError.DimensionMismatch(
          s"Draws.of, chain $c",
          width,
          (0 until chains(c).length).map(chains(c)(_).length).find(_ != width).getOrElse(width)
        )
    }
    duplicate
      .map(BayesError.DuplicateParameter.apply)
      .orElse(ragged)
      .toLeft(Draws(names, chains))

  /** One chain's worth, which is what a single run produces. */
  def single(names: IArray[String], draws: IArray[IArray[Double]]): Either[BayesError, Draws] =
    of(names, IArray(draws))
