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

import scala.reflect.ClassTag

/** A dual number: a value and its derivative along one direction.
  *
  * Forward mode, one pass per coordinate, exact to rounding. This is the oracle the reverse mode
  * is judged against, and it earns that role by sharing nothing with it: it keeps no tape, it
  * inherits **every** vectorised primitive from the defaults of `Real`, and those defaults are
  * written in terms of the scalar operations only. So when a reverse-mode override of
  * `poissonSum` is compared against this, what is being compared is a hand-written adjoint
  * against the derivative of the composition it claims to equal.
  *
  * Finite differences are the secondary check, at well-conditioned points only: they cost a digit
  * or two and say nothing at a rate of 1e-300.
  */
final case class Dual(value: Double, derivative: Double)

object Dual:

  given real: Real[Dual] with
    def tag: ClassTag[Dual] = summon[ClassTag[Dual]]

    def const(x: Double): Dual = Dual(x, 0.0)
    def value(a: Dual): Double = a.value

    def plus(a: Dual, b: Dual): Dual = Dual(a.value + b.value, a.derivative + b.derivative)
    def minus(a: Dual, b: Dual): Dual = Dual(a.value - b.value, a.derivative - b.derivative)
    def times(a: Dual, b: Dual): Dual =
      Dual(a.value * b.value, a.derivative * b.value + a.value * b.derivative)
    def div(a: Dual, b: Dual): Dual =
      Dual(
        a.value / b.value,
        (a.derivative * b.value - a.value * b.derivative) / (b.value * b.value)
      )
    def negate(a: Dual): Dual = Dual(-a.value, -a.derivative)

    def exp(a: Dual): Dual =
      val e = math.exp(a.value)
      Dual(e, e * a.derivative)
    def expm1(a: Dual): Dual = Dual(math.expm1(a.value), math.exp(a.value) * a.derivative)
    def log(a: Dual): Dual = Dual(math.log(a.value), a.derivative / a.value)
    def log1p(a: Dual): Dual = Dual(math.log1p(a.value), a.derivative / (1.0 + a.value))
    def sqrt(a: Dual): Dual =
      val r = math.sqrt(a.value)
      Dual(r, 0.5 * a.derivative / r)
    def pow(a: Dual, p: Double): Dual =
      Dual(math.pow(a.value, p), p * math.pow(a.value, p - 1.0) * a.derivative)

    def invLogit(a: Dual): Dual =
      val p = LogExp.invLogit(a.value)
      Dual(p, p * (1.0 - p) * a.derivative)
    def logInvLogit(a: Dual): Dual =
      Dual(LogExp.logInvLogit(a.value), LogExp.invLogit(-a.value) * a.derivative)
    def log1mInvLogit(a: Dual): Dual =
      Dual(LogExp.log1mInvLogit(a.value), -LogExp.invLogit(a.value) * a.derivative)
    def log1pExp(a: Dual): Dual =
      Dual(LogExp.log1pExp(a.value), LogExp.invLogit(a.value) * a.derivative)
    def lgamma(a: Dual): Dual =
      Dual(Special.lgamma(a.value), Special.digamma(a.value) * a.derivative)
    def lbeta(a: Dual, b: Dual): Dual =
      val shared = Special.digamma(a.value + b.value)
      Dual(
        Special.lbeta(a.value, b.value),
        (Special.digamma(a.value) - shared) * a.derivative +
          (Special.digamma(b.value) - shared) * b.derivative
      )

  /** The gradient, one forward pass per coordinate. */
  def gradient(f: DiffFn, x: IArray[Double]): IArray[Double] =
    IArray.tabulate(x.length) { i =>
      val seeded = IArray.tabulate(x.length)(j => Dual(x(j), if i == j then 1.0 else 0.0))
      f.apply[Dual](seeded).derivative
    }

  def value(f: DiffFn, x: IArray[Double]): Double =
    f.apply[Dual](IArray.tabulate(x.length)(j => Dual(x(j), 0.0))).value
