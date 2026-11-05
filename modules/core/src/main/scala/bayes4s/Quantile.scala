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

/** Empirical quantiles of type 7: the default of R, of NumPy's `linear` and of
  * `stan::math::quantile`.
  *
  * Every interval and every summary in the library reduces to this definition, so it is written
  * once and compared against the values NumPy and R assert rather than against an idea of what a
  * quantile should be.
  */
object Quantile:

  /** Quantile of unsorted values, type 7. `p` outside [0, 1], or no values at all, gives `NaN`.
    *
    * Stan's `quantile`, which is type 7; R offers nine definitions and this is its default too.
    */
  def quantile(xs: IArray[Double], p: Double): Double =
    if xs.length == 0 then Double.NaN else quantileOfSorted(sortedCopy(xs), p)

  /** The same, when the caller already holds sorted values. */
  def quantileOfSorted(sorted: IArray[Double], p: Double): Double =
    val n = sorted.length
    if n == 0 || p.isNaN || p < 0.0 || p > 1.0 then Double.NaN
    else if n == 1 then sorted(0)
    else
      val position = (n - 1) * p
      val below = math.floor(position)
      val index = below.toInt
      val fraction = position - below
      if fraction == 0.0 then sorted(index)
      else sorted(index) + fraction * (sorted(index + 1) - sorted(index))

  @deprecated("renamed to quantile, the Stan name", "0.1")
  def type7(xs: IArray[Double], p: Double): Double = quantile(xs, p)

  @deprecated("renamed to quantileOfSorted", "0.1")
  def type7OfSorted(sorted: IArray[Double], p: Double): Double = quantileOfSorted(sorted, p)

  /** A fresh array, sorted in place. The array is created here and never leaves, so the mutation
    * cannot be observed; `Arrays.sort` on primitives is what makes the summaries of a thousand
    * draws affordable.
    */
  private def sortedCopy(xs: IArray[Double]): IArray[Double] =
    val copy = Array.tabulate(xs.length)(i => xs(i))
    java.util.Arrays.sort(copy)
    IArray.unsafeFromArray(copy)
