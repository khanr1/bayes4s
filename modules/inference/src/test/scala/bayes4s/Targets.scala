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

import bayes4s.syntax.*

/** The targets the sampler is judged on, each with a closed form to be judged against.
  *
  * Every one is a Gaussian or a funnel written out in closed form, so that the truth is a formula
  * and not another sampler's output. The funnel is the exception that proves the point: it has no
  * closed-form posterior for the sampler to match, and what is asserted about it is that the
  * sampler *reports* its difficulty.
  */
object Targets:

  /** Independent standard Gaussians, in as many dimensions as the point it is given. */
  val standardGaussian: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A = -(Real[A].dot(x, x) * 0.5)

  /** Unit variances and the same correlation between every pair.
    *
    * Written through the closed form of the precision of `(1 - rho) I + rho 1 1'`, so the gradient
    * is exact and the cost does not grow with the dimension squared.
    */
  def equicorrelated(dimension: Int, rho: Double): DiffFn = new DiffFn:
    private val scale = 1.0 / (1.0 - rho)
    private val weight = rho / (1.0 + (dimension - 1) * rho)
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      val squares = r.dot(x, x)
      val total = r.sum(x)
      -((squares - total * total * weight) * scale * 0.5)

  /** Independent Gaussians whose scales span four orders of magnitude: what the metric is for. */
  def badlyScaled(scales: IArray[Double]): DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      -(r.sum(r.vectorOf(x.length) { i =>
        val z = x(i) / scales(i).lift[A]
        z * z
      }) * 0.5)

  /** Neal's funnel, centred: `v ~ Gaussian(0, 3)` and `x_i ~ Gaussian(0, exp(v/2))`.
    *
    * No step size works everywhere on it — the neck needs a small one and the mouth a large one —
    * so a correct sampler reports divergent transitions rather than pretending.
    */
  def funnel(dimension: Int): DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      val v = x(0)
      val scale = exp(v * 0.5)
      LogDensity.normal(v, 0.0.lift[A], 3.0.lift[A]) +
        r.sum(r.vectorOf(dimension - 1)(i => LogDensity.normal(x(i + 1), 0.0.lift[A], scale)))
