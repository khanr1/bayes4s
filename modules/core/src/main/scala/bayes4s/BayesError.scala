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

import scala.util.control.NoStackTrace

import cats.Eq
import cats.Show

/** Every predictable failure in the library, as a value.
  *
  * The core returns these inside `Either` and never throws. They extend `NoStackTrace` so that the
  * `effect` module can raise them in `F` through `MonadThrow` without paying for a stack trace.
  * **Example.**
  * An error is a value, so it is handled where it happens rather than caught somewhere else:
  *
  * ```scala
  * val refused: Either[BayesError, Double] =
  *   Left(BayesError.InvalidArgument("Normal.of", "sigma", -1.0, "greater than zero"))
  *
  * // It names the place, the parameter, the offending value and the rule it broke.
  * val explained: String = refused.fold(_.message, value => s"fine: $value")
  *
  * // Several validations compose, and the first failure is the one you are told about.
  * val combined: Either[BayesError, (Double, Double)] =
  *   for
  *     location <- Right(0.0)
  *     scale <- refused
  *   yield (location, scale)
  * ```
  */
sealed trait BayesError extends Product with Serializable with NoStackTrace:

  /** What went wrong, in one line, naming the place and the offending value. */
  def message: String

  final override def getMessage: String = message

object BayesError:

  /** A parameter outside its domain: `sigma <= 0`, `p > 1`, a `NaN` where a number is required. */
  final case class InvalidArgument(where: String, name: String, value: Double, rule: String)
      extends BayesError:
    def message: String = s"$where: $name = $value violates $rule"

  final case class DimensionMismatch(where: String, expected: Int, obtained: Int)
      extends BayesError:
    def message: String = s"$where: expected $expected values, got $obtained"

  final case class UnknownParameter(name: String) extends BayesError:
    def message: String = s"no parameter named '$name'"

  /** Two blocks of a `ParamSpec` claim the same name, which would make a parameter ambiguous both
    * in `Model.unconstrain` and in a CmdStan file.
    */
  final case class DuplicateParameter(name: String) extends BayesError:
    def message: String = s"the parameter '$name' is declared twice"

  final case class NumericalOverflow(where: String) extends BayesError:
    def message: String = s"$where: the computation overflowed"

  /** A malformed external input: a CSV row, a JSON document. */
  final case class Malformed(source: String, detail: String) extends BayesError:
    def message: String = s"$source is malformed: $detail"

  given Eq[BayesError] = Eq.fromUniversalEquals
  given Show[BayesError] = Show.show(_.message)
