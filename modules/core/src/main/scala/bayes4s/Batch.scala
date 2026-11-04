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

/** Vose's alias table: a categorical distribution in constant time per draw. */
final case class AliasTable private (probability: IArray[Double], alias: IArray[Int])

object AliasTable:

  /** Weights must be finite, non-negative and not all zero.
    *
    * Vose's construction, written over immutable vectors: the columns are filled by pairing one
    * light category with one heavy one until none is left, and there is no loop to mutate.
    */
  def of(weights: IArray[Double]): Either[BayesError, AliasTable] =
    val n = weights.length
    if n == 0 then
      Left(BayesError.InvalidArgument("AliasTable.of", "weights", 0.0, "at least one weight"))
    else
      weights.toList.zipWithIndex
        .collectFirst {
          case (w, i) if w.isNaN || w.isInfinite || w < 0.0 =>
            BayesError.InvalidArgument("AliasTable.of", s"weights($i)", w, "finite and at least 0")
        } match
        case Some(error) => Left(error)
        case None =>
          val total = weights.toList.sum
          if !(total > 0.0) then
            Left(
              BayesError.InvalidArgument("AliasTable.of", "weights", total, "a positive total")
            )
          else
            val scaled = Vector.tabulate(n)(i => weights(i) * n / total)
            val light = (0 until n).filter(i => scaled(i) < 1.0).toList
            val heavy = (0 until n).filter(i => scaled(i) >= 1.0).toList
            val (probability, alias) =
              fill(light, heavy, scaled, Vector.fill(n)(1.0), Vector.tabulate(n)(identity))
            Right(
              AliasTable(
                IArray.unsafeFromArray(probability.toArray),
                IArray.unsafeFromArray(alias.toArray)
              )
            )

  @tailrec
  private def fill(
      light: List[Int],
      heavy: List[Int],
      remaining: Vector[Double],
      probability: Vector[Double],
      alias: Vector[Int]
  ): (Vector[Double], Vector[Int]) =
    (light, heavy) match
      case (l :: lightRest, h :: heavyRest) =>
        val share = remaining(l)
        val left = remaining(h) - (1.0 - share)
        val updated = remaining.updated(h, left)
        val probabilityNext = probability.updated(l, share)
        val aliasNext = alias.updated(l, h)
        if left < 1.0 then fill(h :: lightRest, heavyRest, updated, probabilityNext, aliasNext)
        else fill(lightRest, h :: heavyRest, updated, probabilityNext, aliasNext)
      case (l :: lightRest, Nil) =>
        fill(lightRest, Nil, remaining, probability.updated(l, 1.0), alias)
      case (Nil, h :: heavyRest) =>
        fill(Nil, heavyRest, remaining, probability.updated(h, 1.0), alias)
      case (Nil, Nil) => (probability, alias)

/** Batch draws for predictive simulation: pure seen from outside, locally mutable inside.
  *
  * Each function draws exactly what the equivalent `Sampling` program would draw, in the same
  * order, from the same seed; `BatchSuite` asserts that equality element by element, which is what
  * forbids the fast path and the generic path from becoming different samplers. The algorithms are
  * therefore written twice on purpose, once against `Rng[F]` and once against a threaded seed,
  * and the duplication is guarded rather than hidden.
  *
  * This is the second of the two places where a pure function mutates. The array is created here,
  * filled here, and wrapped as an `IArray` on the way out: a simulation of a hundred thousand
  * futures would otherwise allocate a `State` step per draw. A batch takes a seed and returns
  * none, the caller deriving a fresh one with [[Seed.fold]], which keeps the seed out of millions
  * of monadic steps.
  */
object Batch:

  def normal(seed: Seed, n: Int): IArray[Double] =
    if n <= 0 then IArray.empty[Double]
    else
      val out = new Array[Double](n)
      var state = seed // scalafix:ok DisableSyntax.var; the threaded seed, local to this call
      var i = 0 // scalafix:ok DisableSyntax.var; the index being filled
      while i < n do // scalafix:ok DisableSyntax.while; one normal per slot
        val (next, z) = drawNormal(state)
        out(i) = z
        state = next
        i = i + 1
      IArray.unsafeFromArray(out)

  def poisson(seed: Seed, means: IArray[Double]): IArray[Int] =
    val n = means.length
    if n == 0 then IArray.empty[Int]
    else
      val out = new Array[Int](n)
      var state = seed // scalafix:ok DisableSyntax.var; the threaded seed, local to this call
      var i = 0 // scalafix:ok DisableSyntax.var; the index being filled
      while i < n do // scalafix:ok DisableSyntax.while; one count per mean
        val (next, k) = drawPoisson(state, means(i))
        out(i) = k
        state = next
        i = i + 1
      IArray.unsafeFromArray(out)

  def categorical(seed: Seed, table: AliasTable, n: Int): IArray[Int] =
    if n <= 0 then IArray.empty[Int]
    else
      val out = new Array[Int](n)
      var state = seed // scalafix:ok DisableSyntax.var; the threaded seed, local to this call
      var i = 0 // scalafix:ok DisableSyntax.var; the index being filled
      while i < n do // scalafix:ok DisableSyntax.while; one index per slot
        val (next, k) = drawAlias(state, table)
        out(i) = k
        state = next
        i = i + 1
      IArray.unsafeFromArray(out)

  // --- the same algorithms as `Sampling`, over a threaded seed -------------------------------

  @tailrec
  private def drawNormal(seed: Seed): (Seed, Double) =
    val (afterFirst, first) = seed.nextUniform
    val (afterSecond, second) = afterFirst.nextUniform
    val u = 2.0 * first - 1.0
    val v = 2.0 * second - 1.0
    val s = u * u + v * v
    if s >= 1.0 || s == 0.0 then drawNormal(afterSecond)
    else (afterSecond, u * math.sqrt(-2.0 * math.log(s) / s))

  private def drawPoisson(seed: Seed, mean: Double): (Seed, Int) =
    if mean.isNaN || mean < 0.0 then (seed, -1)
    else if mean == 0.0 then (seed, 0)
    else if mean < 10.0 then
      val (advanced, u) = seed.nextUniform
      @tailrec
      def walk(k: Int, mass: Double, cumulative: Double): Int =
        if u <= cumulative || k > 2000 then k
        else
          val nextK = k + 1
          val nextMass = mass * mean / nextK
          walk(nextK, nextMass, cumulative + nextMass)
      val start = math.exp(-mean)
      (advanced, walk(0, start, start))
    else
      val b = 0.931 + 2.53 * math.sqrt(mean)
      val a = -0.059 + 0.02483 * b
      val inverseAlpha = 1.1239 + 1.1328 / (b - 3.4)
      val boundary = 0.9277 - 3.6224 / (b - 2.0)
      val logMean = math.log(mean)
      @tailrec
      def attempt(state: Seed): (Seed, Int) =
        val (afterFirst, first) = state.nextUniform
        val (afterSecond, second) = afterFirst.nextUniform
        val u = first - 0.5
        val us = 0.5 - math.abs(u)
        val k = math.floor((2.0 * a / us + b) * u + mean + 0.43)
        if us >= 0.07 && second <= boundary then (afterSecond, k.toInt)
        else if k < 0.0 || (us < 0.013 && second > us) then attempt(afterSecond)
        else
          val accepted = math.log(second * inverseAlpha / (a / (us * us) + b)) <=
            k * logMean - mean - Special.lgamma(k + 1.0)
          if accepted then (afterSecond, k.toInt) else attempt(afterSecond)
      attempt(seed)

  private def drawAlias(seed: Seed, table: AliasTable): (Seed, Int) =
    val (afterColumn, column) = seed.nextUniform
    val (afterCoin, coin) = afterColumn.nextUniform
    val n = table.probability.length
    val index = math.min((column * n).toInt, n - 1)
    (afterCoin, if coin < table.probability(index) then index else table.alias(index))
