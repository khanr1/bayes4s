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

/** One parameter's row of a posterior summary, in the order `stansummary` prints them. */
final case class Summary(
    name: String,
    mean: Double,
    sd: Double,
    q05: Double,
    q50: Double,
    q95: Double,
    rhat: Double,
    essBulk: Double,
    essTail: Double,
    mcseMean: Double
)

/** What a sampler's behaviour says about whether to believe its output.
  *
  * These three are not about the posterior: they are about the sampler, and a fit with a healthy
  * R-hat and ten divergent transitions is a fit to look at again rather than to report.
  */
final case class SamplerReport(
    divergent: Int,
    saturatedDepth: Int,
    maxTreeDepth: Int,
    eBfmi: List[Double]
):
  /** The rule of thumb from Betancourt (2018): below 0.3 the chain is not exploring the energy
    * distribution, whatever the other diagnostics say.
    */
  def lowEnergyChains(threshold: Double = 0.3): List[Int] =
    eBfmi.zipWithIndex.collect { case (value, c) if !(value >= threshold) => c }

object SamplerReport:

  /** The energy Bayesian fraction of missing information, per chain.
    *
    * `mean((E_n - E_{n-1})^2) / var(E)` over a chain's energies, with the population variance, as
    * Betancourt (2018) defines it and ArviZ computes it. No reference file states a value for it,
    * so what the suite asserts is the closed form: for independent Gaussian energies the
    * numerator is twice the variance and the answer is 2.
    */
  def eBfmi(energies: IArray[Double]): Double =
    val n = energies.length
    if n < 2 then Double.NaN
    else
      val mean = (0 until n).foldLeft(0.0)((sum, i) => sum + energies(i)) / n
      val variance =
        (0 until n).foldLeft(0.0)((sum, i) => sum + (energies(i) - mean) * (energies(i) - mean)) / n
      val differences = (1 until n).foldLeft(0.0) { (sum, i) =>
        val d = energies(i) - energies(i - 1)
        sum + d * d
      } / (n - 1.0)
      if variance > 0.0 then differences / variance else Double.NaN

  def of(chains: List[Chain], maxTreeDepth: Int): SamplerReport =
    val transitions = chains.flatMap(_.transitions)
    SamplerReport(
      divergent = transitions.count(_.divergent),
      saturatedDepth = transitions.count(_.treeDepth >= maxTreeDepth),
      maxTreeDepth = maxTreeDepth,
      eBfmi = chains.map(chain => eBfmi(IArray.from(chain.transitions.map(_.energy))))
    )

object Summary:

  /** `lp__`, the name CmdStan gives the log density on the unconstrained space. */
  val LogDensityName: String = "lp__"

  /** One row per value of the model's parameters, named as the `ParamSpec` names them, plus
    * `lp__` first as `stansummary` does.
    *
    * The draws arrive unconstrained — that is what the sampler produces — and are constrained
    * here, because a summary is about the parameters a model was written in terms of. `lp__` is
    * taken from the transitions, where it is the value of the target that the sampler actually
    * used, Jacobians included.
    */
  def of[T[_]](model: Model[T], chains: List[Chain]): List[Summary] =
    val names = model.params.names
    val constrained = chains.map { chain =>
      chain.draws.map(q => flatten(model, q))
    }
    val logDensity = chains.map(chain => IArray.from(chain.transitions.map(_.point.logDensity)))
    val rows = (0 until names.length).toList.map { index =>
      row(
        names(index),
        IArray.from(constrained.map(draws => IArray.from(draws.map(_(index)))))
      )
    }
    row(LogDensityName, IArray.from(logDensity)) :: rows

  /** The same rows, as text, in the shape `stansummary` prints. */
  def table[T[_]](model: Model[T], chains: List[Chain]): String = render(of(model, chains))

  def render(rows: List[Summary]): String =
    val header =
      List("name", "mean", "sd", "5%", "50%", "95%", "Rhat", "ESS_bulk", "ESS_tail", "MCSE")
    val body = rows.map { r =>
      List(
        r.name,
        number(r.mean),
        number(r.sd),
        number(r.q05),
        number(r.q50),
        number(r.q95),
        number(r.rhat),
        number(r.essBulk),
        number(r.essTail),
        number(r.mcseMean)
      )
    }
    val widths = (header :: body).transpose.map(column => column.map(_.length).max)
    (header :: body)
      .map(cells => cells.zip(widths).map((cell, width) => cell.padTo(width, ' ')).mkString("  "))
      .mkString("\n")

  /** A number for the table, in the root locale.
    *
    * `f"%.4f"` formats in the **default** locale, which writes a comma for the decimal point in
    * much of the world: the same fit would print `-54,1903` on one machine and `-54.1903` on
    * another, and a table nobody can diff is not a table. Found by reading the manual's own
    * rendered output (docs/validation.md).
    */
  private def number(x: Double): String =
    if x.isNaN then "NaN"
    else if x.isInfinite then if x > 0 then "Inf" else "-Inf"
    else if math.abs(x) >= 1000.0 then String.format(java.util.Locale.ROOT, "%.0f", x)
    else String.format(java.util.Locale.ROOT, "%.4f", x)

  private def row(name: String, chains: IArray[IArray[Double]]): Summary =
    val pooled = IArray.from(chains.toList.flatMap(_.toList))
    val sorted = IArray.from(pooled.toList.sorted)
    val mean = (0 until pooled.length).foldLeft(0.0)((sum, i) => sum + pooled(i)) / pooled.length
    val variance =
      (0 until pooled.length).foldLeft(0.0)((sum, i) =>
        sum + (pooled(i) - mean) * (pooled(i) - mean)
      ) /
        (pooled.length - 1.0)
    Summary(
      name = name,
      mean = mean,
      sd = math.sqrt(variance),
      q05 = Quantile.quantileOfSorted(sorted, 0.05),
      q50 = Quantile.quantileOfSorted(sorted, 0.5),
      q95 = Quantile.quantileOfSorted(sorted, 0.95),
      rhat = Convergence.rhatRank(chains),
      essBulk = Convergence.essBulk(chains),
      essTail = Convergence.essTail(chains),
      mcseMean = Convergence.mcseMean(chains)
    )

  /** A model's parameters at one point, flattened in the order its names are listed. */
  private def flatten[T[_]](model: Model[T], q: IArray[Double]): IArray[Double] =
    val values = Model.values(model, q)
    IArray.from(
      model.params.layout.flatMap(block =>
        values.getOrElse(block.name, IArray.empty[Double]).toList
      )
    )

/** Summaries of a sample, computed at the end and never summed.
  *
  * The warning in the name of the last one: a quantile of a sum is not the sum of the quantiles,
  * so every interval here takes draws and nothing else.
  */
object Intervals:

  /** The interval between the `(1 - mass)/2` and `(1 + mass)/2` quantiles. */
  def equalTailed(draws: IArray[Double], mass: Double): (Double, Double) =
    if draws.isEmpty || !(mass > 0.0) || !(mass < 1.0) then (Double.NaN, Double.NaN)
    else
      val sorted = IArray.from(draws.toList.sorted)
      val lower = (1.0 - mass) / 2.0
      (
        Quantile.quantileOfSorted(sorted, lower),
        Quantile.quantileOfSorted(sorted, 1.0 - lower)
      )

  /** The shortest interval holding `mass` of the draws: the empirical highest posterior density.
    *
    * Sort, then slide a window of the right width and keep the narrowest. For a posterior with two
    * modes this is not an interval anyone should report, and the narrowest window says so by
    * covering the gap — which the equal-tailed interval would hide.
    */
  def hpd(draws: IArray[Double], mass: Double): (Double, Double) =
    val n = draws.length
    if n == 0 || !(mass > 0.0) || !(mass < 1.0) then (Double.NaN, Double.NaN)
    else
      val sorted = IArray.from(draws.toList.sorted)
      val width = math.max(1, math.ceil(mass * n).toInt)
      if width >= n then (sorted(0), sorted(n - 1))
      else
        val best = (0 to n - width).minBy(i => sorted(i + width - 1) - sorted(i))
        (sorted(best), sorted(best + width - 1))

  /** The share of draws above a threshold: a posterior probability, estimated by counting. */
  def probabilityAbove(draws: IArray[Double], threshold: Double): Double =
    if draws.isEmpty then Double.NaN
    else (0 until draws.length).count(i => draws(i) > threshold).toDouble / draws.length
