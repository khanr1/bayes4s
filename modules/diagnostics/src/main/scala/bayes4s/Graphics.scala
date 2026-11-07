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

/** Which rule chooses the kernel's width.
  *
  * The names are shared with SciPy and R and mean **different formulas** there, which is why the
  * ones here are written out: a density drawn with the wrong Silverman looks entirely plausible
  * and is wrong by a fifth. These are ArviZ's, because the catalogue is modelled on ArviZ's.
  */
enum Bandwidth:
  /** `0.9 min(sd, IQR/1.34) n^(-1/5)`, the default. */
  case Silverman

  /** `1.06 sd n^(-1/5)`: wider than Silverman, and smoother than a bimodal sample wants. */
  case Scott

  /** Botev's improved Sheather-Jones, by the diffusion method: slower, and the only one of the
    * three that adapts to a sample that is not roughly Gaussian.
    */
  case Isj

  /** A width chosen by hand, when the rule is the thing being questioned. */
  case Fixed(value: Double)

/** Kernel density estimation, exactly.
  *
  * A sum of Gaussians over the sample, evaluated at each grid point: no binning, no FFT, no
  * interpolation. That costs `O(sample × grid)` and buys a density that is the definition rather
  * than an approximation of it — which matters because a binned estimate and an exact one differ
  * in the fourth digit, and a test that compared the two would be testing the binning.
  */
object Density:

  /** The width the rule asks for. `NaN` for a sample too small or constant to have one. */
  def bandwidth(x: IArray[Double], rule: Bandwidth): Double =
    val n = x.length
    if n < 2 then Double.NaN
    else
      rule match
        case Bandwidth.Fixed(value) => if value > 0.0 then value else Double.NaN
        case Bandwidth.Scott => 1.06 * populationSd(x) * math.pow(n.toDouble, -0.2)
        case Bandwidth.Silverman =>
          val spread = Quantile.quantile(x, 0.75) - Quantile.quantile(x, 0.25)
          0.9 * math.min(populationSd(x), spread / 1.34) * math.pow(n.toDouble, -0.2)
        case Bandwidth.Isj => isj(x)

  /** The density at each grid point.
    *
    * `reflectAt` mirrors the sample across a lower bound, which is what a scale parameter needs:
    * without it the kernel spreads mass below zero, the density near the bound is too low, and a
    * posterior pressed against zero looks like one that is not.
    */
  def gaussian(
      x: IArray[Double],
      bandwidth: Double,
      grid: IArray[Double],
      reflectAt: Option[Double] = None
  ): IArray[Double] =
    val n = x.length
    if n == 0 || !(bandwidth > 0.0) then IArray.fill(grid.length)(Double.NaN)
    else
      val scale = 1.0 / (n * bandwidth * math.sqrt(2.0 * math.Pi))
      IArray.tabulate(grid.length) { g =>
        val at = grid(g)
        val direct = (0 until n).foldLeft(0.0) { (sum, i) =>
          val z = (at - x(i)) / bandwidth
          sum + math.exp(-0.5 * z * z)
        }
        val mirrored = reflectAt.fold(0.0) { bound =>
          (0 until n).foldLeft(0.0) { (sum, i) =>
            val z = (at - (2.0 * bound - x(i))) / bandwidth
            sum + math.exp(-0.5 * z * z)
          }
        }
        scale * (direct + mirrored)
      }

  /** An evenly spaced grid covering the sample, padded by a bandwidth at each end. */
  def grid(x: IArray[Double], points: Int, bandwidth: Double): IArray[Double] =
    if x.length == 0 || points < 2 then IArray.empty[Double]
    else
      val low = x.min - bandwidth
      val high = x.max + bandwidth
      IArray.tabulate(points)(i => low + (high - low) * i / (points - 1.0))

  private def populationSd(x: IArray[Double]): Double =
    val n = x.length
    val mean = (0 until n).foldLeft(0.0)((s, i) => s + x(i)) / n
    math.sqrt((0 until n).foldLeft(0.0)((s, i) => s + math.pow(x(i) - mean, 2.0)) / n)

  /** Botev, Grotowski and Kroese (2010), as ArviZ implements it.
    *
    * The sample is binned onto 256 points, cosine-transformed, and the diffusion time `t` solved
    * for by the fixed point of equation (30). The bandwidth is `sqrt(t)` times the grid's range.
    * When the root cannot be bracketed — which happens for a sample with almost no spread — the
    * answer falls back to Silverman's, as ArviZ's does.
    */
  private def isj(x: IArray[Double]): Double =
    val n = x.length
    val spread = populationSd(x)
    val low = x.min - 0.5 * spread
    val high = x.max + 0.5 * spread
    val range = high - low
    val bins = 256
    if !(range > 0.0) then Double.NaN
    else
      val counts = histogram(x, bins, low, high)
      val relative = IArray.tabulate(bins)(i => counts(i) / n.toDouble)
      val transformed = dct(relative)
      val kSquared = IArray.tabulate(bins - 1)(i => math.pow(i + 1.0, 2.0))
      val aSquared = IArray.tabulate(bins - 1)(i => transformed(i + 1) * transformed(i + 1))
      Optimize
        .brentRoot(t => fixedPoint(t, n, kSquared, aSquared), 0.0, 0.01)
        .toOption
        .filter(t => t > 0.0)
        .fold(bandwidth(x, Bandwidth.Silverman))(t => math.sqrt(t) * range)

  /** `t - zeta gamma^[l](t)` of equation (30), with `l = 7` as the paper and ArviZ use. */
  private def fixedPoint(
      t: Double,
      n: Int,
      kSquared: IArray[Double],
      aSquared: IArray[Double]
  ): Double =
    def weighted(power: Double, at: Double): Double =
      (0 until kSquared.length).foldLeft(0.0) { (sum, i) =>
        sum + math.pow(kSquared(i), power) * aSquared(i) *
          math.exp(-kSquared(i) * math.Pi * math.Pi * at)
      }
    val start = 0.5 * math.pow(math.Pi, 14.0) * weighted(7.0, t)
    val f = (2 to 6).reverse.foldLeft(start) { (previous, j) =>
      val c1 = (1.0 + math.pow(0.5, j + 0.5)) / 3.0
      // The product of the odd numbers up to 2j - 1, over sqrt(pi/2).
      val odd = (1 to (2 * j - 1) by 2).foldLeft(1.0)((p, k) => p * k)
      val c2 = odd / math.sqrt(math.Pi / 2.0)
      val tj = math.pow(c1 * (c2 / (n * previous)), 2.0 / (3.0 + 2.0 * j))
      0.5 * math.pow(math.Pi, 2.0 * j) * weighted(j.toDouble, tj)
    }
    t - math.pow(2.0 * n * math.sqrt(math.Pi) * f, -0.4)

  /** Counts in `bins` equal intervals over `[low, high]`, the last one closed, as numpy does. */
  private def histogram(x: IArray[Double], bins: Int, low: Double, high: Double): IArray[Double] =
    val width = (high - low) / bins
    val counted = (0 until x.length).foldLeft(Vector.fill(bins)(0.0)) { (collected, i) =>
      val at = x(i)
      if at < low || at > high then collected
      else
        val bin = math.min(((at - low) / width).toInt, bins - 1)
        collected.updated(bin, collected(bin) + 1.0)
    }
    IArray.from(counted)

  /** ArviZ's one-dimensional cosine transform, built on the library's own Fourier transform. */
  private def dct(x: IArray[Double]): IArray[Double] =
    val n = x.length
    val reordered = IArray.tabulate(n) { i =>
      if i < (n + 1) / 2 then x(2 * i) else x(2 * (n - i) - 1)
    }
    Fft.forwardReal(reordered) match
      case Left(_) => IArray.fill(n)(Double.NaN)
      case Right(spectrum) =>
        IArray.tabulate(n) { k =>
          if k == 0 then spectrum.re(0)
          else
            val angle = -k * math.Pi / (2.0 * n)
            2.0 * (math.cos(angle) * spectrum.re(k) - math.sin(angle) * spectrum.im(k))
        }

/** Ranks of the samples, pooled over chains: what a rank plot draws. */
object Ranks:

  /** The rank of every value among all the chains' values together, one-based.
    *
    * **Ties are broken by position**, not averaged, which is ArviZ's convention and whose own
    * documentation says it assumes there are none: the samples a rank plot is drawn for are
    * continuous, so two exactly equal values are a measure-zero accident. ArviZ's answer for
    * genuinely discrete values is to jitter them onto a continuous scale first, which this does
    * not do — so a rank plot of a discrete posterior drawn here depends on the order of the
    * draws, and the thing to do is jitter before calling this.
    *
    * Averaging ties would be the other defensible choice, and is what the **rank normalisation**
    * behind `Convergence.rhatRank` does. The two conventions coexist on purpose: one is for a
    * statistic that must be invariant, the other for a picture of how the chains interleave.
    */
  def pooled(chains: IArray[IArray[Double]]): IArray[IArray[Double]] =
    val flat = chains.toList.flatMap(_.toList)
    val n = flat.length
    if n == 0 then IArray.empty[IArray[Double]]
    else
      // A stable sort, so that equal values keep the order they arrived in and the ranks are a
      // function of the data rather than of the sort's internals.
      val ranked = flat.zipWithIndex
        .sortBy((value, _) => value)
        .zipWithIndex
        .map { case ((_, position), rank) => position -> (rank + 1.0) }
        .toMap
      val rows = chains.toList
        .foldLeft((Vector.empty[IArray[Double]], 0)) { case ((collected, offset), chain) =>
          val row = IArray.tabulate(chain.length)(i => ranked.getOrElse(offset + i, Double.NaN))
          (collected :+ row, offset + chain.length)
        }
        ._1
      IArray.from(rows)

  /** How many of each chain's ranks fall in each of `bins` equal intervals. */
  def histogram(chains: IArray[IArray[Double]], bins: Int): IArray[IArray[Int]] =
    val ranked = pooled(chains)
    val total = chains.toList.map(_.length).sum
    if bins < 1 || total == 0 then IArray.empty[IArray[Int]]
    else
      IArray.tabulate(ranked.length) { c =>
        val counts = (0 until ranked(c).length).foldLeft(Vector.fill(bins)(0)) { (collected, i) =>
          val share = (ranked(c)(i) - 0.5) / total
          val bin = math.min(math.max((share * bins).toInt, 0), bins - 1)
          collected.updated(bin, collected(bin) + 1)
        }
        IArray.from(counts)
      }

/** A band around an empirical cumulative function. */
final case class EcdfBand(points: IArray[Double], lower: IArray[Double], upper: IArray[Double])

/** The empirical cumulative function, and how far from a reference it may stray.
  *
  * Two bands, and the difference between them is the one people get wrong. The **pointwise** band
  * is right for one point chosen in advance and far too narrow for the whole curve: a calibrated
  * sample leaves it somewhere about half the time. The **simultaneous** band is the one to draw
  * when the question is "does this curve look wrong anywhere", which it always is.
  */
object Ecdf:

  /** The share of the sample at or below each point. */
  def values(sample: IArray[Double], points: IArray[Double]): IArray[Double] =
    val n = sample.length
    if n == 0 then IArray.fill(points.length)(Double.NaN)
    else
      val sorted = IArray.from(sample.toList.sorted)
      IArray.tabulate(points.length) { p =>
        val at = points(p)
        val below = sorted.toList.count(_ <= at)
        below.toDouble / n
      }

  /** The exact binomial interval at each point, taken one point at a time. */
  def pointwiseBand(
      n: Int,
      points: IArray[Double],
      referenceCdf: IArray[Double],
      mass: Double
  ): EcdfBand =
    val tail = (1.0 - mass) / 2.0
    EcdfBand(
      points,
      IArray.tabulate(points.length)(i => binomialQuantile(n, referenceCdf(i), tail) / n.toDouble),
      IArray.tabulate(points.length)(i =>
        binomialQuantile(n, referenceCdf(i), 1.0 - tail) / n.toDouble
      )
    )

  /** The band a whole curve stays inside with probability `mass`.
    *
    * Säilynoja, Bürkner and Vehtari (2022): widen the pointwise interval until the **simultaneous**
    * coverage is right. The adjustment is estimated by simulation — draw complete samples from the
    * reference, see how extreme the most extreme point of each is, and take the quantile of that —
    * which is why it takes a seed and is a function of it.
    */
  def simultaneousBand(
      n: Int,
      points: IArray[Double],
      referenceCdf: IArray[Double],
      mass: Double,
      seed: Seed,
      replications: Int = 2000
  ): EcdfBand =
    if n < 1 || points.length == 0 || replications < 1 then
      EcdfBand(
        points,
        IArray.fill(points.length)(Double.NaN),
        IArray.fill(points.length)(Double.NaN)
      )
    else
      val program = Monad[Rand].replicateA(
        replications,
        Monad[Rand]
          .replicateA(n, Rng[Rand].uniform)
          .map { drawn =>
            val sorted = drawn.sorted
            // The most extreme point of this curve, measured as the smallest pointwise tail
            // probability it reaches anywhere.
            (0 until points.length).foldLeft(1.0) { (smallest, i) =>
              val below = sorted.count(_ <= referenceCdf(i))
              val p = referenceCdf(i)
              val lowerTail = binomialCdf(n, p, below)
              val upperTail = 1.0 - binomialCdf(n, p, below - 1)
              math.min(smallest, math.min(lowerTail, upperTail))
            }
          }
      )
      val extremes = IArray.from(program.runA(seed).value)
      // The pointwise level that leaves `mass` of whole curves inside.
      val adjusted = Quantile.quantile(extremes, 1.0 - mass)
      EcdfBand(
        points,
        IArray.tabulate(points.length)(i =>
          binomialQuantile(n, referenceCdf(i), adjusted) / n.toDouble
        ),
        IArray.tabulate(points.length)(i =>
          binomialQuantile(n, referenceCdf(i), 1.0 - adjusted) / n.toDouble
        )
      )

  /** `P(X <= k)` for a binomial, by summing its mass: exact, and `n` is small here. */
  private[bayes4s] def binomialCdf(n: Int, p: Double, k: Int): Double =
    if k < 0 then 0.0
    else if k >= n then 1.0
    else (0 to k).foldLeft(0.0)((sum, i) => sum + math.exp(LogMass.binomial(i, n, p)))

  /** The smallest `k` with `P(X <= k) >= probability`, as `scipy.stats.binom.ppf` gives it. */
  private[bayes4s] def binomialQuantile(n: Int, p: Double, probability: Double): Double =
    if !(probability > 0.0) then 0.0
    else if probability >= 1.0 then n.toDouble
    else
      val found = (0 to n).foldLeft((0.0, -1)) { case ((cumulative, answer), k) =>
        if answer >= 0 then (cumulative, answer)
        else
          val next = cumulative + math.exp(LogMass.binomial(k, n, p))
          if next >= probability - 1e-12 then (next, k) else (next, -1)
      }
      if found._2 < 0 then n.toDouble else found._2.toDouble
