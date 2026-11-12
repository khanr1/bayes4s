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

import scala.annotation.targetName
import scala.reflect.ClassTag

/** Arithmetic on an abstract scalar, so that a log density is written once and read by several
  * interpreters: plain `Double` for values, a reverse-mode tape for gradients, dual numbers as an
  * exact oracle in the tests.
  *
  * The vectorised operations carry **default implementations in terms of the scalar ones**. That is
  * deliberate and it is what makes the tests meaningful: the defaults define what each primitive
  * means, the `Double` and `Dual` interpreters inherit them, and the reverse-mode interpreter
  * overrides them with versions that push a single node onto its tape. A suite then compares the
  * two, so an override that computed something else would be caught rather than trusted.
  *
  * `tag` is here rather than as a second context bound on every signature. An `IArray[A]` cannot be
  * built without knowing the runtime class of `A`, and the type class that knows how to add two
  * `A`s is the natural place to know what an `A` is: it keeps `DiffFn.apply[A: Real]` and every
  * model free of a bound that says nothing about the mathematics.
  * **Example.** A log density written once against `Real[A]`, then read two ways. Nothing here
  * mentions a derivative, and nothing here is written twice:
  *
  * ```scala
  * /** A Gaussian log density, summed over observations, with a Gaussian prior on the location.
  *   *
  *   * `ad` knows nothing about distributions — `LogDensity` lives a module above — so this is the
  *   * arithmetic itself, which is what `Real[A]` is for.
  *   */
  * def target(y: IArray[Double]): DiffFn = new DiffFn:
  *   def apply[A: Real](q: IArray[A]): A =
  *     val r = Real[A]
  *     val mu = q(0)
  *     val logSigma = q(1)
  *     val sigma = r.exp(logSigma)
  *     // `normalSum` is one node of the gradient tape whatever the length of `y`; the same sum
  *     // written as a loop is a node per observation.
  *     val likelihood = r.normalSum(y, r.filled(y.length)(mu), sigma)
  *     val prior = r.times(r.const(-0.5), r.times(mu, mu))
  *     r.plus(likelihood, prior)
  *
  * val observed = IArray(1.2, 0.8, 1.9, 1.1)
  *
  * // The `Double` interpreter: a number, with no tape at all.
  * val value: Double = Reverse.value(target(observed), IArray(1.0, 0.0))
  *
  * // The same text read by a reverse-mode tape: the value and every partial derivative.
  * val gradient: ValueAndGradient = Reverse.valueAndGradient(target(observed), IArray(1.0, 0.0))
  * val partials: IArray[Double] = gradient.gradient
  * ```
  *
  * **Example.** The two primitives a multilevel model needs. `vectorOf` builds a vector of the
  * interpreter's own scalar, and `gather` gives every observation its cluster's parameter at no
  * cost on the tape — its adjoint accumulates over a cluster's observations in one pass:
  *
  * ```scala
  * def clustered[A: Real](mu: A, tau: A, z: IArray[A], cluster: IArray[Int], y: IArray[Double]): A =
  *   val r = Real[A]
  *   val levels = r.vectorOf(z.length)(j => r.plus(mu, r.times(tau, z(j))))
  *   val standardised = r.sum(r.vectorOf(z.length)(j => r.times(r.const(-0.5), r.times(z(j), z(j)))))
  *   r.plus(r.normalSum(y, r.gather(levels, cluster), r.const(1.0)), standardised)
  * ```
  */
trait Real[A]:

  /** The runtime class of `A`, needed by every operation that returns a vector. */
  def tag: ClassTag[A]

  def const(x: Double): A
  def value(a: A): Double

  def plus(a: A, b: A): A
  def minus(a: A, b: A): A
  def times(a: A, b: A): A
  def div(a: A, b: A): A
  def negate(a: A): A

  def exp(a: A): A
  def expm1(a: A): A
  def log(a: A): A
  def log1p(a: A): A
  def sqrt(a: A): A
  def pow(a: A, p: Double): A

  def invLogit(a: A): A
  def logInvLogit(a: A): A
  def log1mInvLogit(a: A): A
  def log1pExp(a: A): A
  def lgamma(a: A): A

  /** log B(a, b), a primitive rather than three `lgamma`s.
    *
    * The three cancel: at a concentration of 1e6 the two large terms agree to seven digits and
    * `lgamma(a) + lgamma(b) - lgamma(a + b)` keeps nine of the twelve this library asserts. The
    * negative binomial and the beta-binomial are written through it, as the law layer is, and the
    * LKJ constant is a sum of these. Every interpreter implements it, so the well-conditioned
    * value is the one every interpreter sees.
    */
  def lbeta(a: A, b: A): A

  // --- vectorised primitives, with the defaults that define them ----------------------------

  def sum(xs: IArray[A]): A =
    xs.foldLeft(const(0.0))(plus)

  def cumulativeSum(xs: IArray[A]): IArray[A] =
    given ClassTag[A] = tag
    val running = xs.toList
      .scanLeft(const(0.0))(plus)
      .tail
    IArray.from(running)

  def logSumExp(xs: IArray[A]): A =
    if xs.length == 0 then const(Double.NegativeInfinity)
    else
      val largest = xs.foldLeft(Double.NegativeInfinity)((m, a) => math.max(m, value(a)))
      if largest == Double.NegativeInfinity then const(Double.NegativeInfinity)
      else if largest.isInfinite then const(Double.PositiveInfinity)
      else
        val shifted = sum(mapped(xs)(a => exp(minus(a, const(largest)))))
        plus(log(shifted), const(largest))

  /** The Poisson log mass summed over cells.
    *
    * A count of zero contributes `-rate` and nothing else: writing `k log(rate)` and relying on
    * `0 * log(0) = 0` gives `NaN`, and adding a small constant to the rate to avoid it — the
    * common workaround — moves the answer. The term is simply not formed, which also gives the
    * gradient its exact value of -1 at a rate of zero.
    */
  def poissonSum(ks: IArray[Int], rates: IArray[A]): A =
    val terms = (0 until ks.length).map { i =>
      val k = ks(i)
      if k == 0 then negate(rates(i))
      else
        minus(
          minus(times(const(k.toDouble), log(rates(i))), rates(i)),
          const(Special.lgamma(k + 1.0))
        )
    }
    terms.foldLeft(const(0.0))(plus)

  def poissonLogSum(ks: IArray[Int], logRates: IArray[A]): A =
    val terms = (0 until ks.length).map { i =>
      val k = ks(i)
      minus(
        minus(times(const(k.toDouble), logRates(i)), exp(logRates(i))),
        const(Special.lgamma(k + 1.0))
      )
    }
    terms.foldLeft(const(0.0))(plus)

  def normalSum(xs: IArray[A], mu: A, sigma: A): A =
    val terms = (0 until xs.length).map { i =>
      val z = div(minus(xs(i), mu), sigma)
      negate(times(const(0.5), times(z, z)))
    }
    val quadratic = terms.foldLeft(const(0.0))(plus)
    minus(quadratic, times(const(xs.length.toDouble), plus(log(sigma), const(Real.HalfLog2Pi))))

  /** Observations with a mean of their own, which is what a group effect gives every row. */
  def normalSum(ys: IArray[Double], mus: IArray[A], sigma: A): A =
    val terms = (0 until ys.length).map { i =>
      val z = div(minus(const(ys(i)), mus(i)), sigma)
      negate(times(const(0.5), times(z, z)))
    }
    val quadratic = terms.foldLeft(const(0.0))(plus)
    minus(quadratic, times(const(ys.length.toDouble), plus(log(sigma), const(Real.HalfLog2Pi))))

  /** A scale of its own for every observation. Annotated because `A` and `IArray[A]` erase to the
    * same thing, and the specification asks for both overloads.
    */
  @targetName("normalSumVaryingScale")
  def normalSum(ys: IArray[Double], mus: IArray[A], sigmas: IArray[A]): A =
    val terms = (0 until ys.length).map { i =>
      val z = div(minus(const(ys(i)), mus(i)), sigmas(i))
      minus(
        negate(times(const(0.5), times(z, z))),
        plus(log(sigmas(i)), const(Real.HalfLog2Pi))
      )
    }
    terms.foldLeft(const(0.0))(plus)

  /** Stable for a log odds of any size: never through the probability. */
  def bernoulliLogitSum(ks: IArray[Int], etas: IArray[A]): A =
    val terms = (0 until ks.length).map { i =>
      if ks(i) == 1 then logInvLogit(etas(i)) else log1mInvLogit(etas(i))
    }
    terms.foldLeft(const(0.0))(plus)

  def binomialLogitSum(ks: IArray[Int], ns: IArray[Int], etas: IArray[A]): A =
    val terms = (0 until ks.length).map { i =>
      val k = ks(i)
      val n = ns(i)
      plus(
        plus(
          times(const(k.toDouble), logInvLogit(etas(i))),
          times(const((n - k).toDouble), log1mInvLogit(etas(i)))
        ),
        const(Real.logBinomialCoefficient(n.toDouble, k.toDouble))
      )
    }
    terms.foldLeft(const(0.0))(plus)

  /** `values(index(i))` for each i, the primitive that gives every observation its group's
    * parameter. Its adjoint accumulates, for each group, the adjoints of all its observations.
    */
  def gather(values: IArray[A], index: IArray[Int]): IArray[A] =
    given ClassTag[A] = tag
    IArray.tabulate(index.length)(i => values(index(i)))

  def dot(xs: IArray[A], ys: IArray[A]): A =
    val terms = (0 until xs.length).map(i => times(xs(i), ys(i)))
    terms.foldLeft(const(0.0))(plus)

  def matVec(m: IArray[IArray[A]], x: IArray[A]): IArray[A] =
    given ClassTag[A] = tag
    IArray.tabulate(m.length)(i => dot(m(i), x))

  /** Solves `L z = b` for a lower triangular `L`, by forward substitution. */
  def lowerSolve(l: IArray[IArray[A]], b: IArray[A]): IArray[A] =
    given ClassTag[A] = tag
    val solved = (0 until b.length).foldLeft(Vector.empty[A]) { (z, i) =>
      val known = (0 until i).foldLeft(const(0.0))((acc, k) => plus(acc, times(l(i)(k), z(k))))
      z :+ div(minus(b(i), known), l(i)(i))
    }
    IArray.from(solved)

  /** A vector of the interpreter's scalar, built where the tag is known. */
  final def vector(xs: A*): IArray[A] =
    given ClassTag[A] = tag
    IArray.from(xs)

  final def vectorOf(n: Int)(f: Int => A): IArray[A] =
    given ClassTag[A] = tag
    IArray.tabulate(n)(f)

  final def filled(n: Int)(x: A): IArray[A] =
    given ClassTag[A] = tag
    IArray.fill(n)(x)

  /** The runtime class of a **row**, which is what a matrix of `A` is an array of.
    *
    * The compiler cannot synthesise this one. For an abstract `A` it has no way to know that a row
    * is a `double[]` rather than an `Object[]`, so `IArray.tabulate(n)(i => ...)` inside a generic
    * method builds an array of `Object` and a caller that has fixed `A` to `Double` — which is
    * every model read for its values rather than its gradient — fails the cast on the way out.
    * `tag.wrap` is the one place that knows: it turns the element's class into its array's.
    */
  final def rowTag: ClassTag[IArray[A]] = ClassTag(tag.wrap.runtimeClass)

  /** A matrix of the interpreter's scalar, by row and column. */
  final def matrixOf(rows: Int, cols: Int)(f: (Int, Int) => A): IArray[IArray[A]] =
    given ClassTag[IArray[A]] = rowTag
    IArray.tabulate(rows)(i => vectorOf(cols)(j => f(i, j)))

  /** A matrix built a row at a time, for rows that are computed rather than tabulated. */
  final def rowsOf(rows: Int)(f: Int => IArray[A]): IArray[IArray[A]] =
    given ClassTag[IArray[A]] = rowTag
    IArray.tabulate(rows)(f)

  /** `map` over an `IArray[A]`, which needs the tag and so cannot be the standard one. */
  final protected def mapped(xs: IArray[A])(f: A => A): IArray[A] =
    given ClassTag[A] = tag
    IArray.tabulate(xs.length)(i => f(xs(i)))

object Real:
  def apply[A](using r: Real[A]): Real[A] = r

  private[bayes4s] val HalfLog2Pi: Double = 0.5 * math.log(2.0 * math.Pi)

  private[bayes4s] def logBinomialCoefficient(n: Double, k: Double): Double =
    if k == 0.0 || k == n then 0.0 else -math.log(n + 1.0) - Special.lbeta(k + 1.0, n - k + 1.0)

  /** Values, with no tape: the interpreter a model is read by when only its value is wanted. */
  given double: Real[Double] with
    def tag: ClassTag[Double] = ClassTag.Double
    def const(x: Double): Double = x
    def value(a: Double): Double = a
    def plus(a: Double, b: Double): Double = a + b
    def minus(a: Double, b: Double): Double = a - b
    def times(a: Double, b: Double): Double = a * b
    def div(a: Double, b: Double): Double = a / b
    def negate(a: Double): Double = -a
    def exp(a: Double): Double = math.exp(a)
    def expm1(a: Double): Double = math.expm1(a)
    def log(a: Double): Double = math.log(a)
    def log1p(a: Double): Double = math.log1p(a)
    def sqrt(a: Double): Double = math.sqrt(a)
    def pow(a: Double, p: Double): Double = math.pow(a, p)
    def invLogit(a: Double): Double = LogExp.invLogit(a)
    def logInvLogit(a: Double): Double = LogExp.logInvLogit(a)
    def log1mInvLogit(a: Double): Double = LogExp.log1mInvLogit(a)
    def log1pExp(a: Double): Double = LogExp.log1pExp(a)
    def lgamma(a: Double): Double = Special.lgamma(a)
    def lbeta(a: Double, b: Double): Double = Special.lbeta(a, b)

    /** The one place the default is replaced for values: `LogExp` already does it better. */
    override def logSumExp(xs: IArray[Double]): Double = LogExp.logSumExp(xs)
