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
