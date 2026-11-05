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

/** The pair of maps a constraint stands for, and the log Jacobian between them.
  *
  * `constrain` goes from the unconstrained vector a sampler moves in to the constrained values a
  * model is written in terms of, **and returns its log Jacobian rather than adding it anywhere**.
  * Stan's transforms take an accumulator by reference and add to it; here the caller sums, which is
  * what makes the function referentially transparent and the Jacobian something a test can assert
  * on its own.
  *
  * `unconstrain` is the inverse, and it is partial by nature: a value outside its constraint has no
  * antecedent at all. It returns `Either`, like every other partial operation in the library.
  * `constrain` is total: a proposal lands wherever the dynamics take it, and a transform that threw
  * there would take the chain with it.
  */
trait Transform:

  /** How many unconstrained reals stand for `size` constrained values. */
  def unconstrainedSize(size: Int): Int

  /** The constrained values, and `log |det J|`. */
  def constrain[A: Real](u: IArray[A]): (IArray[A], A)

  def unconstrain(x: IArray[Double]): Either[BayesError, IArray[Double]]

object Transform:

  def of(c: Constraint): Transform = c match
    case Constraint.Unconstrained => Identity
    case Constraint.Positive => new Lower(0.0)
    case Constraint.LowerBound(lower) => new Lower(lower)
    case Constraint.UpperBound(upper) => new Upper(upper)
    // `UnitInterval` is `Interval(0, 1)` and not a transform of its own: the Jacobian then carries
    // a `log(1 - 0)` that is exactly zero, so there is nothing to save and one formula fewer to
    // keep right.
    case Constraint.UnitInterval => new Bounded(0.0, 1.0)
    case Constraint.Interval(lower, upper) => new Bounded(lower, upper)
    case Constraint.OffsetMultiplier(offset, multiplier) => new Affine(offset, multiplier)
    case Constraint.Ordered => Increasing
    case Constraint.SumToZero => ZeroSum
    case Constraint.Simplex => Probabilities
    case Constraint.CholeskyFactorCorr(k) => new CorrelationFactor(k)

  /** `tanh`, which `Real[A]` does not carry, written so that it keeps its digits at both ends.
    *
    * `2 invLogit(2y) - 1` is the short way and loses everything near zero: at `y = 1e-10` the
    * subtraction of one from a number just above a half keeps six digits of sixteen. Through
    * `expm1` the small case is exact, and the branch on the sign keeps the exponential's argument
    * negative so that nothing overflows.
    */
  private[bayes4s] def tanh[A: Real](y: A): A =
    val r = Real[A]
    if r.value(y) >= 0.0 then
      val e = exp(y * -2.0)
      -expm1(y * -2.0) / (e + 1.0)
    else
      val e = exp(y * 2.0)
      expm1(y * 2.0) / (e + 1.0)

  /** The inverse of [[tanh]], in `Double` only: `unconstrain` never sees an abstract scalar.
    *
    * `0.5 (log1p(z) - log1p(-z))` rather than `0.5 log((1 + z) / (1 - z))`, which cancels as `z`
    * approaches zero.
    */
  private def atanh(z: Double): Double = 0.5 * (math.log1p(z) - math.log1p(-z))

  /** A transform that acts on every coordinate on its own.
    *
    * Its Jacobian is diagonal, so the log of its determinant is the sum of the per-coordinate
    * terms and `TransformSuite` can check it against a finite difference per coordinate.
    */
  abstract private class Elementwise extends Transform:
    final def unconstrainedSize(size: Int): Int = size
    protected def forward[A: Real](u: A): (A, A)
    protected def backward(x: Double): Either[BayesError, Double]

    final def constrain[A: Real](u: IArray[A]): (IArray[A], A) =
      val r = Real[A]
      val pairs = Vector.tabulate(u.length)(i => forward(u(i)))
      (r.vectorOf(u.length)(i => pairs(i)._1), r.sum(r.vectorOf(u.length)(i => pairs(i)._2)))

    final def unconstrain(x: IArray[Double]): Either[BayesError, IArray[Double]] =
      val folded =
        (0 until x.length).foldLeft[Either[BayesError, Vector[Double]]](Right(Vector.empty)) {
          (acc, i) => acc.flatMap(values => backward(x(i)).map(values :+ _))
        }
      folded.map(values => IArray.from(values))

  private object Identity extends Elementwise:
    protected def forward[A: Real](u: A): (A, A) = (u, Real[A].const(0.0))
    protected def backward(x: Double): Either[BayesError, Double] = Right(x)

  /** `lower + exp(u)`, whose log Jacobian is `u` itself. */
  final private class Lower(lower: Double) extends Elementwise:
    protected def forward[A: Real](u: A): (A, A) = (lower.plusLifted(exp(u)), u)
    protected def backward(x: Double): Either[BayesError, Double] =
      if x > lower && !x.isInfinite then Right(math.log(x - lower))
      else
        Left(
          BayesError.InvalidArgument("Transform.unconstrain", "value", x, s"value > $lower")
        )

  final private class Upper(upper: Double) extends Elementwise:
    protected def forward[A: Real](u: A): (A, A) = (upper.minusLifted(exp(u)), u)
    protected def backward(x: Double): Either[BayesError, Double] =
      if x < upper && !x.isInfinite then Right(math.log(upper - x))
      else
        Left(
          BayesError.InvalidArgument("Transform.unconstrain", "value", x, s"value < $upper")
        )

  /** `lower + (upper - lower) invLogit(u)`.
    *
    * The log Jacobian is `log(U - L) + logInvLogit(u) + log1mInvLogit(u)` and not
    * `log(U - L) + log(p) + log(1 - p)`: at `u = -1000` the probability underflows to zero and the
    * second form is `-Inf + log(1)`, while the first is -1000. That case is one Stan asserts.
    */
  final private class Bounded(lower: Double, upper: Double) extends Elementwise:
    private val width = upper - lower
    protected def forward[A: Real](u: A): (A, A) =
      (
        lower.plusLifted(invLogit(u) * width),
        math.log(width).plusLifted(logInvLogit(u)) + log1mInvLogit(u)
      )
    protected def backward(x: Double): Either[BayesError, Double] =
      if x > lower && x < upper then Right(LogExp.logit((x - lower) / width))
      else
        Left(
          BayesError.InvalidArgument(
            "Transform.unconstrain",
            "value",
            x,
            s"$lower < value < $upper"
          )
        )

  /** `offset + multiplier u`, whose Jacobian is a constant. */
  final private class Affine(offset: Double, multiplier: Double) extends Elementwise:
    protected def forward[A: Real](u: A): (A, A) =
      (offset.plusLifted(u * multiplier), Real[A].const(math.log(multiplier)))
    protected def backward(x: Double): Either[BayesError, Double] =
      if x.isNaN then
        Left(BayesError.InvalidArgument("Transform.unconstrain", "value", x, "value is finite"))
      else Right((x - offset) / multiplier)

  /** Increasing values: the first is free, each gap is an exponential. The Jacobian is triangular
    * and its log determinant is the sum of the unconstrained values after the first.
    */
  private object Increasing extends Transform:
    def unconstrainedSize(size: Int): Int = size

    def constrain[A: Real](u: IArray[A]): (IArray[A], A) =
      val r = Real[A]
      if u.length == 0 then (IArray.empty[A](using r.tag), r.const(0.0))
      else
        val walked =
          (1 until u.length).foldLeft(Vector(u(0)))((acc, i) => acc :+ (acc(i - 1) + exp(u(i))))
        (r.vectorOf(u.length)(i => walked(i)), r.sum(r.vectorOf(u.length - 1)(i => u(i + 1))))

    def unconstrain(x: IArray[Double]): Either[BayesError, IArray[Double]] =
      val gaps = (1 until x.length).collectFirst {
        case i if !(x(i) > x(i - 1)) =>
          BayesError.InvalidArgument(
            "Transform.unconstrain",
            s"value($i)",
            x(i),
            s"value($i) > value(${i - 1}) = ${x(i - 1)}"
          )
      }
      gaps.toLeft(
        IArray.tabulate(x.length)(i => if i == 0 then x(0) else math.log(x(i) - x(i - 1)))
      )

  /** Values summing to zero, by the recursion Stan Math uses: `N` unconstrained reals become
    * `N + 1` values whose sum is zero to the last bit.
    *
    * The map is linear and volume preserving, so there is no Jacobian to add — zero is the claim,
    * not an omission — and the orthogonal basis it uses is what makes the simplex transform below
    * an isometric log-ratio rather than a stick breaking.
    */
  private object ZeroSum extends Transform:
    def unconstrainedSize(size: Int): Int = math.max(size - 1, 0)

    def constrain[A: Real](u: IArray[A]): (IArray[A], A) =
      val r = Real[A]
      val n = u.length
      if n == 0 then (r.vector(r.const(0.0)), r.const(0.0))
      else
        // Walking i from N down to 1, each step adds its own weight to every earlier coordinate
        // and takes i times that weight out of coordinate i.
        val weights = IArray.tabulate(n)(i => 1.0 / math.sqrt((i + 1.0) * (i + 2.0)))
        val partial = (n - 1 to 0 by -1).foldLeft(Vector.empty[A]) { (acc, i) =>
          val w = u(i) * weights(i)
          acc :+ (if acc.isEmpty then w else acc.last + w)
        }
        // `partial(n - 1 - k)` is the sum of the weights from k + 1 to N.
        val running = (0 until n).map(i => partial(n - 1 - i))
        val values = r.vectorOf(n + 1) { k =>
          val fromAbove = if k < n then running(k) else r.const(0.0)
          if k == 0 then fromAbove
          else
            val w = u(k - 1) * weights(k - 1)
            fromAbove - w * k.toDouble
        }
        (values, r.const(0.0))

    def unconstrain(x: IArray[Double]): Either[BayesError, IArray[Double]] =
      val total = x.toList.sum
      if x.length == 0 then Left(BayesError.DimensionMismatch("Transform.unconstrain", 1, 0))
      else if math.abs(total) > 1e-9 then
        Left(
          BayesError.InvalidArgument("Transform.unconstrain", "sum", total, "the values sum to 0")
        )
      else Right(free(x))

    /** The inverse of the recursion: coordinate `N` fixes the last weight, and each earlier
      * coordinate fixes the next one from the running sum already known.
      */
    private[bayes4s] def free(x: IArray[Double]): IArray[Double] =
      val n = x.length - 1
      if n <= 0 then IArray.empty[Double]
      else
        val last = -x(n) / n
        val walked = (n - 1 to 1 by -1).foldLeft((Vector(last), last)) { case ((weights, sum), k) =>
          val w = (sum - x(k)) / k
          (weights :+ w, sum + w)
        }
        val weights = walked._1
        IArray.tabulate(n) { i =>
          val k = i + 1
          weights(n - k) * math.sqrt(k * (k + 1.0))
        }

  /** Positive values summing to one, by the isometric log-ratio transform Stan Math uses today:
    * `softmax(sumToZero(u))`.
    *
    * Stick breaking is not the reference any more. The log Jacobian is `sum(log x) + 0.5 log(N+1)`,
    * which the fixture confirms against a numerical determinant to round-off.
    */
  private object Probabilities extends Transform:
    def unconstrainedSize(size: Int): Int = math.max(size - 1, 0)

    def constrain[A: Real](u: IArray[A]): (IArray[A], A) =
      val r = Real[A]
      val (z, _) = ZeroSum.constrain(u)
      val total = r.logSumExp(z)
      val logX = r.vectorOf(z.length)(i => z(i) - total)
      val values = r.vectorOf(z.length)(i => exp(logX(i)))
      (values, r.sum(logX) + 0.5 * math.log(z.length.toDouble))

    def unconstrain(x: IArray[Double]): Either[BayesError, IArray[Double]] =
      val total = x.toList.sum
      val negative = (0 until x.length).collectFirst {
        case i if !(x(i) > 0.0) =>
          BayesError.InvalidArgument("Transform.unconstrain", s"value($i)", x(i), "value > 0")
      }
      if x.length == 0 then Left(BayesError.DimensionMismatch("Transform.unconstrain", 1, 0))
      else if negative.isDefined then Left(negative.get)
      else if math.abs(total - 1.0) > 1e-9 then
        Left(
          BayesError.InvalidArgument("Transform.unconstrain", "sum", total, "the values sum to 1")
        )
      else
        val logX = IArray.tabulate(x.length)(i => math.log(x(i)))
        val mean = logX.toList.sum / x.length
        Right(ZeroSum.free(IArray.tabulate(x.length)(i => logX(i) - mean)))

  /** The Cholesky factor of a correlation matrix, as `cholesky_corr_constrain` builds it: each row
    * starts from a tanh of an unconstrained real, is scaled by what is left of the unit norm, and
    * is completed by a diagonal entry that makes the norm exactly one.
    *
    * The log Jacobian has two halves and dropping either leaves every value plausible: `log1m(z²)`
    * for each tanh, and `0.5 log1m(sum of squares so far)` for each entry that was scaled.
    *
    * The constrained value is the whole matrix flattened **column by column**, which is Stan's
    * storage order and the order `Block.names` lists: one convention, so that a matrix read back
    * from a CmdStan file is not silently transposed.
    */
  final private class CorrelationFactor(k: Int) extends Transform:
    def unconstrainedSize(size: Int): Int = k * (k - 1) / 2

    def constrain[A: Real](u: IArray[A]): (IArray[A], A) =
      val r = Real[A]
      val z = r.vectorOf(u.length)(i => tanh(u(i)))
      val rows = (1 until k).foldLeft((Vector(firstRow[A]), 0, Vector.empty[A])) {
        case ((built, read, jacobian), i) =>
          val (row, next, terms) = buildRow[A](z, read, i)
          (built :+ row, next, jacobian ++ terms)
      }
      val built = rows._1
      val terms = rows._3
      val tanhTerms = r.vectorOf(z.length)(i => log1p(-(z(i) * z(i))))
      val flat = r.vectorOf(k * k)(index => built(index % k)(index / k))
      (flat, r.sum(tanhTerms) + r.sum(r.vectorOf(terms.length)(i => terms(i))))

    private def firstRow[A: Real]: Vector[A] =
      val r = Real[A]
      Vector.tabulate(k)(j => if j == 0 then r.const(1.0) else r.const(0.0))

    /** Row `i`: `i` entries from `z`, then the diagonal, then zeros. Returns the row, where it
      * stopped reading `z`, and the `0.5 log1m` terms it owes the Jacobian.
      */
    private def buildRow[A: Real](z: IArray[A], from: Int, i: Int): (Vector[A], Int, Vector[A]) =
      val r = Real[A]
      val start = (Vector(z(from)), z(from) * z(from), Vector.empty[A])
      val grown = (1 until i).foldLeft(start) { case ((entries, sumSquares, terms), j) =>
        val scale = log1p(-sumSquares)
        val entry = z(from + j) * sqrt(r.const(1.0) - sumSquares)
        (entries :+ entry, sumSquares + entry * entry, terms :+ (scale * 0.5))
      }
      val (entries, sumSquares, terms) = grown
      val row = entries :+ sqrt(r.const(1.0) - sumSquares)
      (row ++ Vector.fill(k - i - 1)(r.const(0.0)), from + i, terms)

    def unconstrain(x: IArray[Double]): Either[BayesError, IArray[Double]] =
      if x.length != k * k then
        Left(BayesError.DimensionMismatch("Transform.unconstrain", k * k, x.length))
      else
        def entry(i: Int, j: Int): Double = x(j * k + i)
        val structure = (0 until k).flatMap(i => (0 until k).map(j => (i, j))).collectFirst {
          case (i, j) if j > i && entry(i, j) != 0.0 =>
            BayesError.InvalidArgument(
              "Transform.unconstrain",
              s"L($i, $j)",
              entry(i, j),
              "entries above the diagonal are zero"
            )
          case (i, j) if i == j && !(entry(i, j) > 0.0) =>
            BayesError.InvalidArgument(
              "Transform.unconstrain",
              s"L($i, $i)",
              entry(i, j),
              "the diagonal is positive"
            )
        }
        def norm(i: Int): Double =
          (0 until k).foldLeft(0.0)((s, j) => s + entry(i, j) * entry(i, j))
        val norms = (0 until k).collectFirst {
          case i if math.abs(norm(i) - 1.0) > 1e-9 =>
            BayesError.InvalidArgument(
              "Transform.unconstrain",
              s"row $i",
              norm(i),
              "every row has norm 1"
            )
        }
        structure
          .orElse(norms)
          .toLeft {
            val free = (1 until k).foldLeft(Vector.empty[Double]) { (acc, i) =>
              val row =
                (1 until i).foldLeft((Vector(atanh(entry(i, 0))), entry(i, 0) * entry(i, 0))) {
                  case ((values, sumSquares), j) =>
                    val e = entry(i, j)
                    (values :+ atanh(e / math.sqrt(1.0 - sumSquares)), sumSquares + e * e)
                }
              acc ++ row._1
            }
            IArray.from(free)
          }
