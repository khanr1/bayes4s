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

package bayes4s.rethinking

import bayes4s.*

/** The book's figures, under the package's names, built on the `plot` module.
  *
  * Each one is a pure [[bayes4s.Plot]] compiled to a Vega-Lite specification — nothing opens a
  * window and nothing writes a file, which is `PlotFile`'s job. The names are the package's;
  * `drawdag` is deliberately absent, since a causal diagram is not a statistical figure and this
  * library draws none.
  *
  * **Example.**
  *
  * ```scala
  * //{
  * import bayes4s.*
  * //}
  * val chains: IArray[IArray[Double]] =
  *   IArray.tabulate(2)(c => IArray.tabulate(400)(i => math.sin(i * 0.07 + c)))
  *
  * // The posterior of one parameter, and the chains that produced it.
  * val density: Plot = Figures.dens(chains, "mu")
  * val trace: Plot = Figures.traceplot(chains, "mu")
  * val ranks: Plot = Figures.trankplot(chains, "mu")
  *
  * // The same density with its 89% interval shaded, which is what `shade` does in the book.
  * val shaded: Plot = Figures.shade(chains, "mu")
  * ```
  */
object Figures:

  /** `dens`: the posterior density of one parameter, a line per chain. */
  def dens(
      chains: IArray[IArray[Double]],
      parameter: String,
      rule: Bandwidth = Bandwidth.Silverman
  ): Plot =
    Catalogue.densityPlot(chains, parameter, rule, mass = Rethinking.defaultProb)

  /** `shade`: the same density with its compatibility interval shaded underneath.
    *
    * In the book `shade` is a verb applied to an existing plot; here it is the figure with the
    * shading already in it, because a `Plot` is a value and the catalogue's density draws its own
    * band. The mass is the book's 89%.
    */
  def shade(
      chains: IArray[IArray[Double]],
      parameter: String,
      prob: Double = Rethinking.defaultProb
  ): Plot =
    Catalogue.densityPlot(chains, parameter, Bandwidth.Silverman, mass = prob)

  /** `traceplot`: the chains over their iterations, with divergent transitions marked. */
  def traceplot(
      chains: IArray[IArray[Double]],
      parameter: String,
      divergent: Option[IArray[IArray[Boolean]]] = None
  ): Plot =
    Catalogue.tracePlot(chains, parameter, divergent)

  /** `trankplot`: the same chains, ranked — which is the one to believe for a long chain. */
  def trankplot(chains: IArray[IArray[Double]], parameter: String, bins: Int = 20): Plot =
    Catalogue.trankPlot(chains, parameter, bins)

  /** `plot(precis(fit))`: every parameter's interval, one row each.
    *
    * The book's `precis` plot shows the mean and the interval; this takes the rows `precis`
    * returned, so the figure and the table cannot disagree.
    */
  def precisPlot(rows: List[Summary], title: String = "precis"): Plot =
    Catalogue.intervalPlot(rows.map(row => (row.name, row.mean, row.q05, row.q95)), title)

  /** `postcheck`: the observed data against what the fitted model simulates for it.
    *
    * `observed(i)` against the replications of observation `i`, which is the posterior predictive
    * check of chapter 5 under the book's name for it.
    */
  def postcheck(
      observed: IArray[Double],
      replicated: IArray[IArray[Double]],
      prob: Double = Rethinking.defaultProb
  ): Plot =
    Catalogue.predictivePlot(observed, replicated, prob, "postcheck")
