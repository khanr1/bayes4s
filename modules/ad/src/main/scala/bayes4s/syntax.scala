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

/** Operators and free functions, so that a density reads as a formula rather than as a sequence of
  * method calls on a type class.
  *
  * `import bayes4s.syntax.*` turns `r.minus(r.times(a, b), c)` into `a * b - c`. The alternative —
  * writing densities against the type class directly — is correct and unreadable, and a density
  * nobody can read is a density nobody can check.
  */
object syntax:

  /** The runtime class of the interpreter's scalar, from its arithmetic.
    *
    * A model that writes `IArray.fill(y.length)(p.mu)` needs it, and asking every model for a
    * second context bound that says nothing about the mathematics would be the alternative. It
    * lives behind this import so that it is opt-in.
    */
  given tagFromReal[A](using r: Real[A]): ClassTag[A] = r.tag

  extension [A](a: A)(using r: Real[A])
    def +(b: A): A = r.plus(a, b)
    def -(b: A): A = r.minus(a, b)
    def *(b: A): A = r.times(a, b)
    def /(b: A): A = r.div(a, b)
    def unary_- : A = r.negate(a)
    def +(b: Double): A = r.plus(a, r.const(b))
    def -(b: Double): A = r.minus(a, r.const(b))
    def *(b: Double): A = r.times(a, r.const(b))
    def /(b: Double): A = r.div(a, r.const(b))
    def primal: Double = r.value(a)

  extension (x: Double)
    /** A constant of the interpreter's scalar type: `2.0.lift[A]`. */
    def lift[A](using r: Real[A]): A = r.const(x)
    def plusLifted[A](a: A)(using r: Real[A]): A = r.plus(r.const(x), a)
    def minusLifted[A](a: A)(using r: Real[A]): A = r.minus(r.const(x), a)
    def timesLifted[A](a: A)(using r: Real[A]): A = r.times(r.const(x), a)

  def exp[A: Real](a: A): A = Real[A].exp(a)
  def expm1[A: Real](a: A): A = Real[A].expm1(a)
  def log[A: Real](a: A): A = Real[A].log(a)
  def log1p[A: Real](a: A): A = Real[A].log1p(a)
  def sqrt[A: Real](a: A): A = Real[A].sqrt(a)
  def pow[A: Real](a: A, p: Double): A = Real[A].pow(a, p)
  def invLogit[A: Real](a: A): A = Real[A].invLogit(a)
  def logInvLogit[A: Real](a: A): A = Real[A].logInvLogit(a)
  def log1mInvLogit[A: Real](a: A): A = Real[A].log1mInvLogit(a)
  def log1pExp[A: Real](a: A): A = Real[A].log1pExp(a)
  def lgamma[A: Real](a: A): A = Real[A].lgamma(a)
  def lbeta[A: Real](a: A, b: A): A = Real[A].lbeta(a, b)
  def logSumExp[A: Real](xs: IArray[A]): A = Real[A].logSumExp(xs)
  def cumulativeSum[A: Real](xs: IArray[A]): IArray[A] = Real[A].cumulativeSum(xs)
  def gather[A: Real](values: IArray[A], index: IArray[Int]): IArray[A] =
    Real[A].gather(values, index)
