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
import cats.syntax.all.*

/** Prediction for a group that is not in the data.
  *
  * A multilevel fit gives every observed group its own effect and the level above them its own
  * distribution. A group nobody has seen has no effect to look up — what it has is that
  * distribution, so its effect is **drawn** from the group law once for every posterior sample,
  * which carries through both kinds of uncertainty: what the level above is, and where in it the
  * new group falls.
  *
  * Using the posterior mean of the level instead would keep the first and throw away the second,
  * and the interval that came out would be too narrow by exactly the amount that matters.
  */
object Hierarchy:

  /** One effect per posterior sample, for a group drawn from a Gaussian level.
    *
    * `levels` and `scales` are the samples of the hyperparameters — one of each per sample — and
    * the result has the same length: sample `s` gets an effect drawn from `Gaussian(levels(s),
    * scales(s))`. Pairing them that way is the whole point: a scale from one sample with a level
    * from another would describe a posterior nobody has.
    */
  def newGroup[F[_]: Monad: Rng](
      levels: IArray[Double],
      scales: IArray[Double]
  ): F[IArray[Double]] =
    if levels.length != scales.length then Monad[F].pure(IArray.empty[Double])
    else
      Monad[F]
        .replicateA(levels.length, Rng[F].normal)
        .map(drawn => IArray.tabulate(levels.length)(s => levels(s) + scales(s) * drawn(s)))

  /** The same, for a group whose effects are correlated: a vector per sample.
    *
    * `factors(s)` is the Cholesky factor of the correlation of the effects at sample `s` and
    * `scales(s)` their standard deviations, which is how a multilevel model with varying slopes
    * parameterises its level. The effect is `levels(s) + scales(s) ⊙ (L z)`, the same arrangement
    * the model itself uses, so a prediction cannot disagree with the fit about what the level is.
    */
  def newGroupCorrelated[F[_]: Monad: Rng](
      levels: IArray[IArray[Double]],
      scales: IArray[IArray[Double]],
      factors: IArray[Cholesky]
  ): F[IArray[IArray[Double]]] =
    val samples = levels.length
    if samples == 0 || scales.length != samples || factors.length != samples then
      Monad[F].pure(IArray.empty[IArray[Double]])
    else
      val dimension = levels(0).length
      (0 until samples).toList
        .traverse { s =>
          Monad[F]
            .replicateA(dimension, Rng[F].normal)
            .map { drawn =>
              val standardised = IArray.tabulate(dimension) { k =>
                (0 to k).foldLeft(0.0)((sum, j) => sum + factors(s).matrix(k, j) * drawn(j))
              }
              IArray.tabulate(dimension)(k => levels(s)(k) + scales(s)(k) * standardised(k))
            }
        }
        .map(effects => IArray.from(effects))
