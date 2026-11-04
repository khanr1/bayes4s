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
import cats.data.State

/** A pure, deterministic program that consumes randomness. */
type Rand[A] = State[Seed, A]

/** Randomness as a capability.
  *
  * A program asks only for the type classes it uses, and the interpreter is chosen at the edge:
  * `State` in the core and in tests, a `Ref` in `effect`.
  */
trait Rng[F[_]]:
  def nextLong: F[Long]

  /** Uniform on the **open** interval (0, 1), so that `log(u)` is always finite. */
  def uniform: F[Double]

  def normal: F[Double]

object Rng:
  def apply[F[_]](using r: Rng[F]): Rng[F] = r

  /** The pure interpreter. `State` is exactly lawful, unlike a monad that splits the seed on every
    * `flatMap`, which is lawful only in distribution; that is why the core uses it.
    */
  given state: Rng[Rand] = new Rng[Rand]:

    def nextLong: Rand[Long] = State(s => s.next)

    def uniform: Rand[Double] = State(s => s.nextUniform)

    /** Marsaglia's polar method. The second variate it produces is discarded, which costs draws
      * and buys something worth more: one deterministic sequence of uniforms, identical in every
      * interpreter and in the batch path, with no state carried between calls.
      */
    def normal: Rand[Double] =
      Monad[Rand].tailRecM(()) { _ =>
        for
          first <- uniform
          second <- uniform
        yield
          val u = 2.0 * first - 1.0
          val v = 2.0 * second - 1.0
          val s = u * u + v * v
          if s >= 1.0 || s == 0.0 then Left(())
          else Right(u * math.sqrt(-2.0 * math.log(s) / s))
      }
