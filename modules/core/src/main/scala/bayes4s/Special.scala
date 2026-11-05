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

import org.apache.commons.numbers.gamma.LogGamma

/** Special functions, total on the whole of `Double`.
  *
  * Outside their domain they return the documented IEEE value and never throw: `lgamma` is `+Inf`
  * at its poles, `digamma` is `NaN` at its own. Values are validated against mpmath at 50
  * significant digits.
  *
  * Apache Commons Numbers covers the positive half line. The negative half is reached by the
  * reflection formulae, whose only delicate part is the sine: `sin(pi x)` computed directly loses
  * most of its digits once `x` is large, so the argument is folded into a quarter period first.
  */
object Special:

  /** log |Gamma(x)|, `+Inf` at every pole. */
  def lgamma(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x == Double.PositiveInfinity then Double.PositiveInfinity
    else if x == Double.NegativeInfinity then Double.PositiveInfinity
    else if x > 0.0 then LogGamma.value(x)
    else if isNonPositiveInteger(x) then Double.PositiveInfinity
    else
      // log|Gamma(x)| = log(pi) - log|sin(pi x)| - log|Gamma(1 - x)|
      val reflected = LogGamma.value(1.0 - x)
      LogPi - math.log(math.abs(sinPi(x))) - reflected

  /** The derivative of `lgamma`, `NaN` at every pole: the two one-sided limits disagree there.
    *
    * Written here rather than taken from Commons Numbers, whose `Digamma` carries a relative error
    * of about 2e-10 near 1 and so misses the 1e-13 the reference asks for. The recurrence lifts the
    * argument above 20, where the Bernoulli series is good to the last bit.
    */
  def digamma(x: Double): Double =
    if x.isNaN then Double.NaN
    else if x == Double.PositiveInfinity then Double.PositiveInfinity
    else if x == Double.NegativeInfinity then Double.NaN
    else if x > 0.0 then digammaPositive(x)
    else if isNonPositiveInteger(x) then Double.NaN
    else
      // psi(x) = psi(1 - x) - pi / tan(pi x)
      digammaPositive(1.0 - x) - math.Pi * cosPi(x) / sinPi(x)

  /** log B(a, b), without the cancellation the textbook formula suffers.
    *
    * `lgamma(a) + lgamma(b) - lgamma(a + b)` is correct and useless once an argument is large: at
    * a = 300 the three terms are near 1400 and their sum is near -0.14, so eleven digits cancel and
    * nothing is left to spare. Subtracting Stirling's approximation first keeps every term the size
    * of the answer. This matters again later, where the negative binomial needs a dispersion of 1e8.
    */
  def lbeta(a: Double, b: Double): Double =
    if a.isNaN || b.isNaN then Double.NaN
    else if a <= 0.0 || b <= 0.0 then Double.NaN
    else if a.isInfinite || b.isInfinite then Double.NegativeInfinity
    else
      val small = math.min(a, b)
      val large = math.max(a, b)
      val total = small + large
      if large < StirlingThreshold then lgamma(small) + lgamma(large) - lgamma(total)
      else if small < StirlingThreshold then
        lgamma(small) + stirlingDiff(large) - stirlingDiff(total) +
          (large - 0.5) * math.log1p(-small / total) + small * (1.0 - math.log(total))
      else
        stirlingDiff(small) + stirlingDiff(large) - stirlingDiff(total) + 0.5 * Log2Pi +
          (small - 0.5) * math.log1p(-large / total) +
          (large - 0.5) * math.log1p(-small / total) - 0.5 * math.log(total)

  /** The standard normal quantile, by Wichura's AS241.
    *
    * Accurate to about 1e-16 relative over the whole range the double can express, down to
    * p = 1e-300 and beyond. `erfinv(2p - 1)` is not used: it loses every digit below p of about
    * 1e-50, which is exactly where the rank normalisation of the diagnostics needs it.
    */
  def invPhi(p: Double): Double =
    if p.isNaN || p < 0.0 || p > 1.0 then Double.NaN
    else if p == 0.0 then Double.NegativeInfinity
    else if p == 1.0 then Double.PositiveInfinity
    else
      val q = p - 0.5
      if math.abs(q) <= 0.425 then
        val r = 0.180625 - q * q
        q * centralNumerator(r) / centralDenominator(r)
      else
        val tail = if q < 0.0 then p else 1.0 - p
        val r = math.sqrt(-math.log(tail))
        val value =
          if r <= 5.0 then
            val s = r - 1.6
            nearNumerator(s) / nearDenominator(s)
          else
            val s = r - 5.0
            farNumerator(s) / farDenominator(s)
        if q < 0.0 then -value else value

  @deprecated("renamed to invPhi, the Stan name (inv_Phi)", "0.1")
  def normalQuantile(p: Double): Double = invPhi(p)

  // --- internals ----------------------------------------------------------------------------

  private val LogPi: Double = math.log(math.Pi)
  private val Log2Pi: Double = math.log(2.0 * math.Pi)

  /** Above this argument the asymptotic series are exact to the last bit; below it, the recurrence
    * and the direct difference are.
    */
  private val StirlingThreshold: Double = 20.0

  /** psi for a positive argument: lift above the threshold by psi(z) = psi(z + 1) - 1/z, then use
    * the Bernoulli series, whose next unused term at z = 20 is below 1e-20.
    */
  private def digammaPositive(x: Double): Double =
    @annotation.tailrec
    def lift(z: Double, shift: Double): Double =
      if z >= StirlingThreshold then shift + digammaLarge(z) else lift(z + 1.0, shift - 1.0 / z)
    lift(x, 0.0)

  private def digammaLarge(z: Double): Double =
    val inv = 1.0 / z
    val inv2 = inv * inv
    val series = inv2 * (-1.0 / 12.0 + inv2 * (1.0 / 120.0 + inv2 * (-1.0 / 252.0 +
      inv2 * (1.0 / 240.0 + inv2 * (-1.0 / 132.0 + inv2 * (691.0 / 32760.0))))))
    math.log(z) - 0.5 * inv + series

  /** lgamma(z) minus Stirling's approximation of it: a quantity of order 1/(12z), which is where
    * all the precision of [[lbeta]] lives.
    */
  private def stirlingDiff(z: Double): Double =
    if z < StirlingThreshold then lgamma(z) - stirlingApproximation(z)
    else
      val inv = 1.0 / z
      val inv2 = inv * inv
      inv * (1.0 / 12.0 + inv2 * (-1.0 / 360.0 + inv2 * (1.0 / 1260.0 +
        inv2 * (-1.0 / 1680.0 + inv2 * (1.0 / 1188.0)))))

  private def stirlingApproximation(z: Double): Double =
    0.5 * Log2Pi + (z - 0.5) * math.log(z) - z

  private def isNonPositiveInteger(x: Double): Boolean = x <= 0.0 && x == math.rint(x)

  /** sin(pi x), folded around the **nearest integer** rather than around a period.
    *
    * The distinction decides three digits. Near an integer the sine is small, and it is only its
    * relative accuracy that matters: computing it as `cos(pi (0.5 - r))` with an argument close to
    * pi/2 costs the absolute error of that argument, which at x = -0.001 is 5e-14 of a result of
    * 0.003. Folding to the small fractional part keeps the argument small and the sine exact.
    */
  private def sinPi(x: Double): Double =
    val n = math.rint(x)
    val f = x - n
    val value =
      if math.abs(f) <= 0.25 then math.sin(math.Pi * f)
      else math.copySign(math.cos(math.Pi * (0.5 - math.abs(f))), f)
    if isEven(n) then value else -value

  private def cosPi(x: Double): Double =
    val n = math.rint(x)
    val f = x - n
    val value =
      if math.abs(f) <= 0.25 then math.cos(math.Pi * f)
      else math.sin(math.Pi * (0.5 - math.abs(f)))
    if isEven(n) then value else -value

  private def isEven(n: Double): Boolean = n.toLong % 2L == 0L

  private def poly(coefficients: List[Double], x: Double): Double =
    coefficients.foldLeft(0.0)((acc, c) => acc * x + c)

  // Coefficients of AS241 (Wichura 1988), as used by R's qnorm.
  private def centralNumerator(r: Double): Double = poly(
    List(2509.0809287301226727, 33430.575583588128105, 67265.770927008700853, 45921.953931549871457,
      13731.693765509461125, 1971.5909503065514427, 133.14166789178437745, 3.387132872796366608),
    r
  )

  private def centralDenominator(r: Double): Double = poly(
    List(5226.495278852854561, 28729.085735721942674, 39307.89580009271061, 21213.794301586595867,
      5394.1960214247511077, 687.1870074920579083, 42.313330701600911252, 1.0),
    r
  )

  private def nearNumerator(r: Double): Double = poly(
    List(7.7454501427834140764e-4, 0.0227238449892691845833, 0.24178072517745061177,
      1.27045825245236838258, 3.64784832476320460504, 5.7694972214606914055, 4.6303378461565452959,
      1.42343711074968357734),
    r
  )

  private def nearDenominator(r: Double): Double = poly(
    List(1.05075007164441684324e-9, 5.475938084995344946e-4, 0.0151986665636164571966,
      0.14810397642748007459, 0.68976733498510000455, 1.6763848301838038494, 2.05319162663775882187,
      1.0),
    r
  )

  private def farNumerator(r: Double): Double = poly(
    List(2.01033439929228813265e-7, 2.71155556874348757815e-5, 0.0012426609473880784386,
      0.026532189526576123093, 0.29656057182850489123, 1.7848265399172913358, 5.4637849111641143699,
      6.6579046435011037772),
    r
  )

  private def farDenominator(r: Double): Double = poly(
    List(2.04426310338993978564e-15, 1.4215117583164458887e-7, 1.8463183175100546818e-5,
      7.868691311456132591e-4, 0.0148753612908506148525, 0.13692988092273580531,
      0.59983220655588793769, 1.0),
    r
  )
