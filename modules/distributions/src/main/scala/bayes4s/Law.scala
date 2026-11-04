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

/** A law as a value, on the real line.
  *
  * Built only through its smart constructor, so an instance is always valid and `logPdf` never has
  * to re-check its parameters. A value outside the support gives `-Inf`, never an exception.
  */
trait ContinuousLaw:
  def logPdf(x: Double): Double
  def mean: Double
  def variance: Double
  def sample[F[_]: Monad: Rng]: F[Double]

/** A law as a value, on the integers. `logMass(k)` is `-Inf` outside the support. */
trait DiscreteLaw:
  def logMass(k: Int): Double
  def mean: Double
  def variance: Double
  def sample[F[_]: Monad: Rng]: F[Int]
