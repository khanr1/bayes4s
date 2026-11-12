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

import scala.annotation.tailrec

final case class Optimum(argument: Double, value: Double, iterations: Int)

/** Where a many-dimensional search stopped, and how hard it had to work to get there. */
final case class MultiOptimum(
    argument: IArray[Double],
    value: Double,
    gradient: IArray[Double],
    iterations: Int
)

/** One-dimensional optimisation, pure and total.
  *
  * Both searches are the classical Brent algorithms, written as a tail recursion over an immutable
  * state rather than as a loop over mutable locals: the state is small, the recursion is a fold,
  * and nothing escapes. A function that does not converge, or that cannot be evaluated, returns
  * `Left` — never a silently wrong value, which is the whole reason these return `Either`.
  *
  * One limit is worth knowing before trusting a result. Near a smooth minimum the objective
  * changes by less than a double's resolution once the argument is within about sqrt(eps) of the
  * answer, so `brentMinimize` locates the **argument** to roughly 1e-8 and its **value** to full
  * precision. Roots are well conditioned and `brentRoot` reaches 1e-12 on the argument itself.
  * **Example.** One dimension, without derivatives, on a bracket:
  *
  * ```scala
  * // The minimum of a parabola, which Brent finds to about 1e-8 in the argument.
  * val found: Either[BayesError, Optimum] =
  *   Optimize.brentMinimize(x => (x - 2.0) * (x - 2.0) + 1.0, lower = -10.0, upper = 10.0)
  *
  * // And a root, which is better conditioned: 1e-12 on the argument itself. The bracket must change
  * // sign, and a bracket that does not is an error rather than a guess.
  * val root: Either[BayesError, Double] =
  *   Optimize.brentRoot(x => x * x * x - 8.0, lower = 0.0, upper = 10.0)
  * ```
  *
  * **Example.** Many dimensions, with a gradient, and the curvature at the answer — which together
  * are a Laplace approximation:
  *
  * ```scala
  * /** A log posterior, and its gradient. Reverse-mode automatic differentiation is where a real one
  *   * would come from; this is written out so the example needs no model.
  *   */
  * def logPosterior(x: IArray[Double]): Double = -0.5 * (4.0 * x(0) * x(0) + x(1) * x(1))
  * def gradient(x: IArray[Double]): IArray[Double] = IArray(4.0 * x(0), x(1))
  *
  * // `lbfgs` minimises, so a posterior is passed negated.
  * val mode: Either[BayesError, MultiOptimum] =
  *   Optimize.lbfgs(x => -logPosterior(x), gradient, IArray(1.0, 1.0))
  *
  * // The curvature there, by central differences of the same gradient. For a posterior this is the
  * // **negative** Hessian, whose inverse is the approximation's covariance.
  * val curvature: Either[BayesError, Matrix] = mode.flatMap(m => Optimize.hessian(gradient, m.argument))
  * ```
  */
object Optimize:

  /** Brent's method on [lower, upper], without derivatives. */
  def brentMinimize(
      f: Double => Double,
      lower: Double,
      upper: Double,
      tolerance: Double = 1e-10,
      maxIterations: Int = 200
  ): Either[BayesError, Optimum] =
    guard("Optimize.brentMinimize", lower, upper, tolerance, maxIterations).flatMap { _ =>
      val start = lower + GoldenComplement * (upper - lower)
      val fStart = f(start)
      if fStart.isNaN then Left(notEvaluable("Optimize.brentMinimize", start))
      else
        minimumLoop(
          f,
          MinimumState(lower, upper, start, start, start, fStart, fStart, fStart, 0.0, 0.0),
          tolerance,
          maxIterations,
          0
        )
    }

  /** Limited-memory BFGS: a quasi-Newton minimiser for a handful to a few hundred parameters.
    *
    * It takes the gradient rather than computing one, because where a gradient comes from is the
    * caller's business — reverse-mode automatic differentiation when there is a model to
    * differentiate, central differences when there is not.
    *
    * The method's whole idea is that it never forms a Hessian. It keeps the last `memory` pairs of
    * (step, change in gradient) and uses them to apply an approximate inverse Hessian in two
    * passes, which is what lets it walk a stretched valley that steepest descent zig-zags across.
    * The pairs are a small immutable list, oldest dropped, so nothing here mutates.
    *
    * The line search is a backtracking one on the Armijo condition: take the quasi-Newton step,
    * halve it until the objective falls by enough, and give up rather than loop. That is the
    * simple half of a textbook line search and it is enough for a well-behaved posterior; what it
    * does not do is satisfy the curvature condition, so a pair that would make the approximation
    * indefinite is **skipped** rather than applied, which is the standard guard.
    *
    * Returns `Left` rather than a wrong answer when the objective cannot be evaluated, when the
    * start is empty, or when the iterations run out before the gradient is small.
    */
  def lbfgs(
      f: IArray[Double] => Double,
      gradient: IArray[Double] => IArray[Double],
      start: IArray[Double],
      tolerance: Double = 1e-8,
      maxIterations: Int = 500,
      memory: Int = 8
  ): Either[BayesError, MultiOptimum] =
    val n = start.length
    if n == 0 then
      Left(BayesError.InvalidArgument("Optimize.lbfgs", "start", 0.0, "at least one parameter"))
    else if !(tolerance > 0.0) then
      Left(BayesError.InvalidArgument("Optimize.lbfgs", "tolerance", tolerance, "above zero"))
    else if maxIterations < 1 then
      Left(
        BayesError
          .InvalidArgument("Optimize.lbfgs", "maxIterations", maxIterations.toDouble, "at least 1")
      )
    else
      val value = f(start)
      val slope = gradient(start)
      if value.isNaN || value.isInfinite then Left(notEvaluable("Optimize.lbfgs", value))
      else if slope.exists(g => g.isNaN || g.isInfinite) then
        Left(notEvaluable("Optimize.lbfgs", Double.NaN))
      else lbfgsLoop(f, gradient, start, value, slope, Nil, tolerance, maxIterations, memory, 0)

  /** The Hessian of a function, by central differences of its **gradient**.
    *
    * Differencing the gradient rather than the value costs `2n` gradient evaluations instead of
    * `4n²` function evaluations and loses far less precision: a second difference of the value
    * divides by the square of the step, so at the step a first difference wants it has already
    * lost most of its digits.
    *
    * The result is symmetrised — `(H + H')/2` — because the two one-sided estimates of a mixed
    * partial differ by rounding, and a curvature matrix that is not symmetric is not a curvature
    * matrix. `step` defaults to the cube root of the machine epsilon times a scale, which is the
    * usual compromise between truncation and rounding for a central difference.
    */
  def hessian(
      gradient: IArray[Double] => IArray[Double],
      at: IArray[Double],
      step: Double = 1e-5
  ): Either[BayesError, Matrix] =
    val n = at.length
    if n == 0 then
      Left(BayesError.InvalidArgument("Optimize.hessian", "at", 0.0, "at least one parameter"))
    else if !(step > 0.0) then
      Left(BayesError.InvalidArgument("Optimize.hessian", "step", step, "above zero"))
    else
      val columns = (0 until n).map { j =>
        val h = step * math.max(1.0, math.abs(at(j)))
        val forward = gradient(IArray.tabulate(n)(i => if i == j then at(i) + h else at(i)))
        val backward = gradient(IArray.tabulate(n)(i => if i == j then at(i) - h else at(i)))
        IArray.tabulate(n)(i => (forward(i) - backward(i)) / (2.0 * h))
      }
      if columns.exists(_.exists(x => x.isNaN || x.isInfinite)) then
        Left(notEvaluable("Optimize.hessian", Double.NaN))
      else
        Matrix.of(
          n,
          n,
          IArray.tabulate(n * n) { k =>
            val (i, j) = (k / n, k % n)
            0.5 * (columns(j)(i) + columns(i)(j))
          }
        )

  /** Brent-Dekker root finding. The bracket must change sign. */
  def brentRoot(
      f: Double => Double,
      lower: Double,
      upper: Double,
      tolerance: Double = 1e-12,
      maxIterations: Int = 200
  ): Either[BayesError, Double] =
    guard("Optimize.brentRoot", lower, upper, tolerance, maxIterations).flatMap { _ =>
      val fLower = f(lower)
      val fUpper = f(upper)
      if fLower.isNaN then Left(notEvaluable("Optimize.brentRoot", lower))
      else if fUpper.isNaN then Left(notEvaluable("Optimize.brentRoot", upper))
      else if fLower == 0.0 then Right(lower)
      else if fUpper == 0.0 then Right(upper)
      else if fLower > 0.0 == (fUpper > 0.0) then
        Left(
          BayesError.InvalidArgument(
            "Optimize.brentRoot",
            "bracket",
            lower,
            s"f(lower) = $fLower and f(upper) = $fUpper must have opposite signs"
          )
        )
      else
        rootLoop(
          f,
          RootState(lower, upper, upper, fLower, fUpper, fUpper, upper - lower, upper - lower),
          tolerance,
          maxIterations,
          0
        )
    }

  // --- internals ----------------------------------------------------------------------------

  /** (3 - sqrt 5) / 2, the golden-section step. */
  private val GoldenComplement: Double = 0.5 * (3.0 - math.sqrt(5.0))

  /** The square root of the machine epsilon: no argument of a smooth minimum is worth more. */
  private val SqrtEpsilon: Double = math.sqrt(2.220446049250313e-16)

  private val Epsilon: Double = 2.220446049250313e-16

  private def guard(
      where: String,
      lower: Double,
      upper: Double,
      tolerance: Double,
      maxIterations: Int
  ): Either[BayesError, Unit] =
    if lower.isNaN || upper.isNaN then
      Left(BayesError.InvalidArgument(where, "bracket", lower, "both bounds must be numbers"))
    else if lower.isInfinite || upper.isInfinite then
      Left(BayesError.InvalidArgument(where, "bracket", lower, "both bounds must be finite"))
    else if !(lower < upper) then
      Left(BayesError.InvalidArgument(where, "bracket", lower, "lower must be below upper"))
    else if !(tolerance > 0.0) then
      Left(BayesError.InvalidArgument(where, "tolerance", tolerance, "tolerance > 0"))
    else if maxIterations < 1 then
      Left(
        BayesError.InvalidArgument(where, "maxIterations", maxIterations.toDouble, "at least one")
      )
    else Right(())

  private def notEvaluable(where: String, at: Double): BayesError =
    BayesError.InvalidArgument(where, "f", at, "the function must return a number")

  private def exhausted(where: String, steps: Int): BayesError =
    BayesError.NumericalOverflow(s"$where did not converge in $steps iterations")

  final private case class MinimumState(
      lower: Double,
      upper: Double,
      best: Double,
      second: Double,
      third: Double,
      fBest: Double,
      fSecond: Double,
      fThird: Double,
      lastStep: Double,
      previousStep: Double
  )

  @tailrec
  private def minimumLoop(
      f: Double => Double,
      s: MinimumState,
      tolerance: Double,
      maxIterations: Int,
      step: Int
  ): Either[BayesError, Optimum] =
    val middle = 0.5 * (s.lower + s.upper)
    val near = SqrtEpsilon * math.abs(s.best) + tolerance / 3.0
    val twiceNear = 2.0 * near
    if math.abs(s.best - middle) <= twiceNear - 0.5 * (s.upper - s.lower) then
      Right(Optimum(s.best, s.fBest, step))
    else if step >= maxIterations then Left(exhausted("Optimize.brentMinimize", step))
    else
      // A parabola through the three best points, kept only if its vertex falls inside the
      // bracket and shortens the step; otherwise a golden-section step, which always does.
      val parabola =
        if math.abs(s.previousStep) > near then
          val r = (s.best - s.second) * (s.fBest - s.fThird)
          val q0 = (s.best - s.third) * (s.fBest - s.fSecond)
          val p0 = (s.best - s.third) * q0 - (s.best - s.second) * r
          val q = 2.0 * (q0 - r)
          val p = if q > 0.0 then -p0 else p0
          Some((p, math.abs(q), s.previousStep))
        else None
      val (proposed, carried) = parabola match
        case Some((p, q, limit))
            if math.abs(p) < math.abs(0.5 * q * limit) &&
              p > q * (s.lower - s.best) && p < q * (s.upper - s.best) =>
          val candidate = p / q
          val landing = s.best + candidate
          val adjusted =
            if landing - s.lower < twiceNear || s.upper - landing < twiceNear then
              math.copySign(near, middle - s.best)
            else candidate
          (adjusted, s.lastStep)
        case _ =>
          val span = if s.best < middle then s.upper - s.best else s.lower - s.best
          (GoldenComplement * span, span)
      val taken = if math.abs(proposed) >= near then proposed else math.copySign(near, proposed)
      val at = s.best + taken
      val fAt = f(at)
      if fAt.isNaN then Left(notEvaluable("Optimize.brentMinimize", at))
      else
        val next =
          if fAt <= s.fBest then
            MinimumState(
              lower = if at < s.best then s.lower else s.best,
              upper = if at < s.best then s.best else s.upper,
              best = at,
              second = s.best,
              third = s.second,
              fBest = fAt,
              fSecond = s.fBest,
              fThird = s.fSecond,
              lastStep = taken,
              previousStep = carried
            )
          else
            val lower = if at < s.best then at else s.lower
            val upper = if at < s.best then s.upper else at
            if fAt <= s.fSecond || s.second == s.best then
              s.copy(
                lower = lower,
                upper = upper,
                second = at,
                third = s.second,
                fSecond = fAt,
                fThird = s.fSecond,
                lastStep = taken,
                previousStep = carried
              )
            else if fAt <= s.fThird || s.third == s.best || s.third == s.second then
              s.copy(
                lower = lower,
                upper = upper,
                third = at,
                fThird = fAt,
                lastStep = taken,
                previousStep = carried
              )
            else s.copy(lower = lower, upper = upper, lastStep = taken, previousStep = carried)
        minimumLoop(f, next, tolerance, maxIterations, step + 1)

  final private case class RootState(
      a: Double,
      b: Double,
      c: Double,
      fa: Double,
      fb: Double,
      fc: Double,
      lastStep: Double,
      previousStep: Double
  )

  @tailrec
  private def rootLoop(
      f: Double => Double,
      state: RootState,
      tolerance: Double,
      maxIterations: Int,
      step: Int
  ): Either[BayesError, Double] =
    // Keep c on the far side of the root from b, and b the best estimate of the two.
    val bracketed =
      if (state.fb > 0.0) == (state.fc > 0.0) then
        state.copy(
          c = state.a,
          fc = state.fa,
          lastStep = state.b - state.a,
          previousStep = state.b - state.a
        )
      else state
    val s =
      if math.abs(bracketed.fc) < math.abs(bracketed.fb) then
        bracketed.copy(
          a = bracketed.b,
          b = bracketed.c,
          c = bracketed.b,
          fa = bracketed.fb,
          fb = bracketed.fc,
          fc = bracketed.fb
        )
      else bracketed
    val near = 2.0 * Epsilon * math.abs(s.b) + 0.5 * tolerance
    val half = 0.5 * (s.c - s.b)
    if math.abs(half) <= near || s.fb == 0.0 then Right(s.b)
    else if step >= maxIterations then Left(exhausted("Optimize.brentRoot", step))
    else
      // Inverse quadratic interpolation where it is safe, bisection where it is not.
      val interpolation =
        if math.abs(s.previousStep) >= near && math.abs(s.fa) > math.abs(s.fb) then
          val ratio = s.fb / s.fa
          val (p0, q0) =
            if s.a == s.c then (2.0 * half * ratio, 1.0 - ratio)
            else
              val outer = s.fa / s.fc
              val inner = s.fb / s.fc
              (
                ratio * (2.0 * half * outer * (outer - inner) - (s.b - s.a) * (inner - 1.0)),
                (outer - 1.0) * (inner - 1.0) * (ratio - 1.0)
              )
          val q = if p0 > 0.0 then -q0 else q0
          val p = math.abs(p0)
          val boundA = 3.0 * half * q - math.abs(near * q)
          val boundB = math.abs(s.previousStep * q)
          if 2.0 * p < math.min(boundA, boundB) then Some(p / q) else None
        else None
      val (taken, carried) = interpolation match
        case Some(d) => (d, s.lastStep)
        case None => (half, half)
      val moved = s.b + (if math.abs(taken) > near then taken else math.copySign(near, half))
      val fMoved = f(moved)
      if fMoved.isNaN then Left(notEvaluable("Optimize.brentRoot", moved))
      else
        rootLoop(
          f,
          s.copy(
            a = s.b,
            fa = s.fb,
            b = moved,
            fb = fMoved,
            lastStep = taken,
            previousStep = carried
          ),
          tolerance,
          maxIterations,
          step + 1
        )

  // --- the quasi-Newton loop ------------------------------------------------------------------

  /** One remembered step: how far the argument moved, and how much the gradient changed. */
  final private case class Pair(s: IArray[Double], y: IArray[Double], rho: Double)

  private def dot(a: IArray[Double], b: IArray[Double]): Double =
    (0 until a.length).foldLeft(0.0)((sum, i) => sum + a(i) * b(i))

  /** The two-loop recursion: the remembered pairs applied as an approximate inverse Hessian.
    *
    * Written as two folds over the list of pairs rather than as a loop with a mutable array of
    * coefficients. The list is at most `memory` long, so the allocation is bounded and small.
    */
  private def direction(slope: IArray[Double], pairs: List[Pair]): IArray[Double] =
    val n = slope.length
    val (afterFirst, alphas) =
      pairs.foldLeft((IArray.tabulate(n)(i => -slope(i)), List.empty[Double])) {
        case ((q, collected), pair) =>
          val alpha = pair.rho * dot(pair.s, q)
          (IArray.tabulate(n)(i => q(i) - alpha * pair.y(i)), alpha :: collected)
      }
    // Nocedal's scaling of the initial Hessian: the most recent pair's curvature.
    val scaled = pairs.headOption.fold(afterFirst) { pair =>
      val gamma = dot(pair.s, pair.y) / dot(pair.y, pair.y)
      IArray.tabulate(n)(i => gamma * afterFirst(i))
    }
    pairs.reverse
      .zip(alphas)
      .foldLeft(scaled) { case (r, (pair, alpha)) =>
        val beta = pair.rho * dot(pair.y, r)
        IArray.tabulate(n)(i => r(i) + pair.s(i) * (alpha - beta))
      }

  @annotation.tailrec
  private def lbfgsLoop(
      f: IArray[Double] => Double,
      gradient: IArray[Double] => IArray[Double],
      x: IArray[Double],
      value: Double,
      slope: IArray[Double],
      pairs: List[Pair],
      tolerance: Double,
      maxIterations: Int,
      memory: Int,
      step: Int
  ): Either[BayesError, MultiOptimum] =
    val n = x.length
    val norm = math.sqrt(dot(slope, slope))
    if norm <= tolerance then Right(MultiOptimum(x, value, slope, step))
    else if step >= maxIterations then
      Left(BayesError.NumericalOverflow(s"Optimize.lbfgs did not converge in $maxIterations"))
    else
      val d = direction(slope, pairs)
      val descent = dot(d, slope)
      // A direction that does not go downhill means the memory has gone bad; steepest descent is
      // always a descent direction, so the search restarts from it rather than failing.
      val downhill = if descent < 0.0 then d else IArray.tabulate(n)(i => -slope(i))
      val initialSlope = dot(downhill, slope)
      // After a quasi-Newton step the natural length is 1. On the **first** step there is no
      // curvature remembered yet, the direction is steepest descent, and its length is the
      // gradient's — which on a steep objective is enormous and costs thirty halvings before the
      // first useful move. Scaling that one step by 1/|g| is the standard remedy.
      val firstLength = if pairs.isEmpty then 1.0 / math.max(1.0, norm) else 1.0
      lineSearch(f, x, value, downhill, initialSlope, firstLength, 0) match
        case None =>
          // No step of any length improved the objective: at a minimum to within the arithmetic
          // available, which is an answer rather than a failure.
          Right(MultiOptimum(x, value, slope, step))
        case Some((moved, movedValue)) =>
          val movedSlope = gradient(moved)
          if movedValue.isNaN || movedSlope.exists(g => g.isNaN || g.isInfinite) then
            Left(notEvaluable("Optimize.lbfgs", movedValue))
          else if value - movedValue <= 1e-15 * (math.abs(value) + 1.0) then
            // The objective has stopped changing to the resolution of a double. A gradient-norm
            // test alone would keep walking a flat valley for ever, which is what Rosenbrock's
            // does: this is the second stopping rule every real implementation carries.
            Right(MultiOptimum(moved, movedValue, movedSlope, step + 1))
          else
            val sVector = IArray.tabulate(n)(i => moved(i) - x(i))
            val yVector = IArray.tabulate(n)(i => movedSlope(i) - slope(i))
            val curvature = dot(sVector, yVector)
            // A pair with no positive curvature would make the approximation indefinite, so it is
            // skipped rather than applied: the standard guard, and the reason a backtracking line
            // search is enough here.
            val kept =
              if curvature > 1e-12 then
                (Pair(sVector, yVector, 1.0 / curvature) :: pairs).take(memory)
              else pairs
            lbfgsLoop(
              f,
              gradient,
              moved,
              movedValue,
              movedSlope,
              kept,
              tolerance,
              maxIterations,
              memory,
              step + 1
            )

  /** Backtracking on the Armijo condition: halve until the objective falls by enough. */
  @annotation.tailrec
  private def lineSearch(
      f: IArray[Double] => Double,
      x: IArray[Double],
      value: Double,
      d: IArray[Double],
      slope: Double,
      length: Double,
      tries: Int
  ): Option[(IArray[Double], Double)] =
    if tries > 60 then None
    else
      val candidate = IArray.tabulate(x.length)(i => x(i) + length * d(i))
      val f1 = f(candidate)
      if !f1.isNaN && f1 <= value + 1e-4 * length * slope then Some((candidate, f1))
      else lineSearch(f, x, value, d, slope, length * 0.5, tries + 1)
