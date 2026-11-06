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

/** The Bayesian bootstrap: one opinion about a pool's composition, per simulated future.
  *
  * Dirichlet(1, ..., 1) weights over the pool are the posterior of its composition in the
  * uninformative limit — Rubin's bootstrap — so a set of weights is one coherent answer to "which
  * of the events we observed are representative?". Each component has mean `1/n`, so no item is
  * favoured on average; what the weights carry is the uncertainty about the pool itself.
  *
  * **The weights are drawn once per future and held for the whole horizon.** Redrawing them every
  * period would average that uncertainty away inside each future — twelve independent opinions
  * averaged are far more certain than one — and the intervals would come out too narrow while
  * every mean stayed right. The suite measures the difference rather than taking it on trust.
  */
object BayesianBootstrap:

  /** One set of weights for a pool of `size` items. */
  def weights[F[_]: Monad: Rng](size: Int): F[IArray[Double]] =
    if size <= 0 then Monad[F].pure(IArray.empty[Double])
    else Sampling.dirichlet[F](IArray.fill(size)(1.0))

  /** How much of a group's own pool to use against the whole: `w = n / (n + k0)`.
    *
    * A group with plenty of its own events speaks for itself; one with few borrows from everybody.
    * `k0` is where the crossover sits, in events: at `n = k0` the two contribute equally. A group
    * with nothing has no opinion of its own (`0`), a prior of nothing means no borrowing (`1`),
    * and a negative prior is not a quantity (`NaN`).
    */
  def mixingWeight(groupSize: Int, mixingPrior: Double): Double =
    if mixingPrior < 0.0 || mixingPrior.isNaN then Double.NaN
    else if groupSize <= 0 then 0.0
    else groupSize.toDouble / (groupSize.toDouble + mixingPrior)

/** A pool of payloads with weights, drawn from by the alias method.
  *
  * Built once per simulated future — from `BayesianBootstrap.weights`, usually — and then drawn
  * from as many times as the future needs. The alias table is part of the value for that reason:
  * building it is `O(n)` and drawing is constant time, so it is built when the opinion is formed
  * rather than at every draw.
  */
final case class TemplatePool[P] private (
    items: IArray[P],
    weights: IArray[Double],
    private val table: AliasTable
):

  def size: Int = items.length

  def draw[F[_]: Monad: Rng]: F[P] =
    Sampling.alias[F](table).map(index => items(index))

object TemplatePool:

  /** A pool with the weights given. Refused if they cannot be drawn from. */
  def of[P](items: IArray[P], weights: IArray[Double]): Either[BayesError, TemplatePool[P]] =
    if items.length == 0 then
      Left(BayesError.InvalidArgument("TemplatePool.of", "items", 0.0, "at least one item"))
    else if items.length != weights.length then
      Left(BayesError.DimensionMismatch("TemplatePool.of", items.length, weights.length))
    else AliasTable.of(weights).map(table => TemplatePool(items, weights, table))

  /** A pool with its own bootstrap opinion: the weights drawn here, once, and then fixed. */
  def bootstrapped[F[_]: Monad: Rng, P](
      items: IArray[P]
  ): F[Either[BayesError, TemplatePool[P]]] =
    BayesianBootstrap.weights[F](items.length).map(drawn => of(items, drawn))

/** Pools by stratum, with a minimum size and a fallback on the whole pool.
  *
  * A stratum with enough events of its own gets a pool of them and mixes it with the global pool
  * at `n / (n + k0)`; one below `minimumSize` gets no pool at all and draws entirely from the
  * global one. The two rules answer different questions — the minimum is "can this stratum be
  * sampled from at all?", the mixing is "how much should it borrow?" — and a stratum can fail the
  * first while being large enough to matter in the second, which is why both exist.
  *
  * A stratum's pool is the **same item array** under a weight vector that is zero outside the
  * stratum. Nothing is copied, no payload is moved, and the alias table does the selecting — which
  * is also why `TemplatePool[P]` needs nothing of `P`, not even a runtime class.
  */
final case class StratifiedPools[K, P] private (
    byStratum: Map[K, TemplatePool[P]],
    counts: Map[K, Int],
    global: TemplatePool[P],
    mixingPrior: Double
):

  /** How much of its own pool a stratum uses. Zero when it has none. */
  def mixingWeight(stratum: K): Double =
    counts.get(stratum).fold(0.0)(n => BayesianBootstrap.mixingWeight(n, mixingPrior))

  /** One payload for a stratum: its own pool with probability `mixingWeight`, else the global. */
  def draw[F[_]: Monad: Rng](stratum: K): F[P] =
    byStratum.get(stratum) match
      case None => global.draw[F]
      case Some(pool) =>
        val own = mixingWeight(stratum)
        Rng[F].uniform.flatMap(u => if u < own then pool.draw[F] else global.draw[F])

object StratifiedPools:

  /** Equal weights per item, which is the pool before any bootstrap opinion is formed. */
  def of[K, P](
      items: IArray[P],
      stratumOf: P => K,
      minimumSize: Int,
      mixingPrior: Double
  ): Either[BayesError, StratifiedPools[K, P]] =
    build(items, stratumOf, minimumSize, mixingPrior)((_, members) =>
      Right(indicator(items.length, members))
    )

  /** Each stratum's pool with its own bootstrap opinion, and the global pool with one too.
    *
    * A stratum's weights are drawn over its own members and placed where those members are, so the
    * opinion is about the stratum and not about the items it excludes.
    */
  def bootstrapped[F[_]: Monad: Rng, K, P](
      items: IArray[P],
      stratumOf: P => K,
      minimumSize: Int,
      mixingPrior: Double
  ): F[Either[BayesError, StratifiedPools[K, P]]] =
    val strata =
      indicesByStratum(items, stratumOf).filter((_, members) => members.length >= minimumSize)
    for
      globalWeights <- BayesianBootstrap.weights[F](items.length)
      drawn <- strata.toList.traverse { (stratum, members) =>
        BayesianBootstrap
          .weights[F](members.length)
          .map(w => stratum -> placedAt(items.length, members, w))
      }
    yield
      val counts = strata.view.mapValues(_.length).toMap
      for
        global <- TemplatePool.of(items, globalWeights)
        pools <- drawn.traverse((stratum, weights) =>
          TemplatePool.of(items, weights).map(pool => stratum -> pool)
        )
      yield StratifiedPools(pools.toMap, counts, global, mixingPrior)

  private def build[K, P](
      items: IArray[P],
      stratumOf: P => K,
      minimumSize: Int,
      mixingPrior: Double
  )(
      weightsFor: (K, List[Int]) => Either[BayesError, IArray[Double]]
  ): Either[BayesError, StratifiedPools[K, P]] =
    val strata =
      indicesByStratum(items, stratumOf).filter((_, members) => members.length >= minimumSize)
    for
      global <- TemplatePool.of(items, IArray.fill(items.length)(1.0))
      pools <- strata.toList.traverse { (stratum, members) =>
        weightsFor(stratum, members)
          .flatMap(weights => TemplatePool.of(items, weights))
          .map(pool => stratum -> pool)
      }
    yield StratifiedPools(pools.toMap, strata.view.mapValues(_.length).toMap, global, mixingPrior)

  private def indicesByStratum[K, P](items: IArray[P], stratumOf: P => K): Map[K, List[Int]] =
    (0 until items.length).toList.groupBy(i => stratumOf(items(i)))

  /** One where a member is, zero elsewhere. */
  private def indicator(size: Int, members: List[Int]): IArray[Double] =
    val wanted = members.toSet
    IArray.tabulate(size)(i => if wanted.contains(i) then 1.0 else 0.0)

  /** The stratum's own weights, placed at its members' positions and zero elsewhere. */
  private def placedAt(size: Int, members: List[Int], weights: IArray[Double]): IArray[Double] =
    val byPosition = members.zipWithIndex.map((position, k) => position -> weights(k)).toMap
    IArray.tabulate(size)(i => byPosition.getOrElse(i, 0.0))
