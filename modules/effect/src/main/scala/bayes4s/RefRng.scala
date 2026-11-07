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

import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.syntax.all.*

/** The randomness algebra over a `Ref`, for the effectful edge.
  *
  * It runs the **same pure programs** the `State` interpreter does — `Rng[Rand].normal` and its
  * siblings — and only replaces how the seed is carried: `State` threads it, this swaps it in a
  * `Ref`. That is deliberate rather than convenient. Reimplementing the generators here would mean
  * two implementations of the same arithmetic, and the claim that a fit is a function of its seed
  * would rest on them agreeing; this way they cannot differ, and the suite proves it is what
  * happens.
  *
  * Safe to share between fibres, because a `Ref` serialises. Nothing in this library does share
  * one: a sampler gives every chain its own, seeded by `seed.fold(chain)`, so that what each chain
  * draws does not depend on the order the scheduler ran them in.
  */
object RefRng:

  def make[F[_]: Sync](seed: Seed): F[Rng[F]] =
    Ref.of[F, Seed](seed).map { ref =>
      new Rng[F]:
        def nextLong: F[Long] = run(ref, Rng[Rand].nextLong)
        def uniform: F[Double] = run(ref, Rng[Rand].uniform)
        def normal: F[Double] = run(ref, Rng[Rand].normal)
    }

  /** One pure program, interpreted against the `Ref`: the seed goes in, the value comes out. */
  private def run[F[_], A](ref: Ref[F, Seed], program: Rand[A]): F[A] =
    ref.modify(seed => program.run(seed).value)
