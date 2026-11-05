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

/** The constants and the parameter checks every law shares.
  *
  * A parameter must be **finite** and satisfy its own constraint, uniformly across the library.
  * Stan's test suite is less uniform than that — it rejects an infinite scale for some laws and
  * not for others — so the cases it asserts are a subset of what is refused here.
  */
private[bayes4s] object Laws:

  val HalfLog2Pi: Double = 0.5 * math.log(2.0 * math.Pi)
  val Log2Pi: Double = math.log(2.0 * math.Pi)
  val LogPi: Double = math.log(math.Pi)
  val HalfLogPi: Double = 0.5 * math.log(math.Pi)
  val Log2: Double = math.log(2.0)
  val HalfLog2OverPi: Double = 0.5 * math.log(2.0 / math.Pi)

  def finite(where: String, name: String, value: Double): Either[BayesError.InvalidArgument, Unit] =
    if value.isNaN || value.isInfinite then
      Left(BayesError.InvalidArgument(where, name, value, s"$name must be finite"))
    else Right(())

  def positiveFinite(
      where: String,
      name: String,
      value: Double
  ): Either[BayesError.InvalidArgument, Unit] =
    if value.isNaN || value.isInfinite || !(value > 0.0) then
      Left(BayesError.InvalidArgument(where, name, value, s"$name > 0 and finite"))
    else Right(())

  def nonNegativeFinite(
      where: String,
      name: String,
      value: Double
  ): Either[BayesError.InvalidArgument, Unit] =
    if value.isNaN || value.isInfinite || value < 0.0 then
      Left(BayesError.InvalidArgument(where, name, value, s"$name >= 0 and finite"))
    else Right(())

  def unitInterval(
      where: String,
      name: String,
      value: Double
  ): Either[BayesError.InvalidArgument, Unit] =
    if value.isNaN || value < 0.0 || value > 1.0 then
      Left(BayesError.InvalidArgument(where, name, value, s"$name in [0, 1]"))
    else Right(())

  /** log C(n, k) through lbeta, which keeps its precision where the three lgammas would cancel. */
  def logBinomialCoefficient(n: Double, k: Double): Double =
    if k == 0.0 || k == n then 0.0
    else -math.log(n + 1.0) - Special.lbeta(k + 1.0, n - k + 1.0)
