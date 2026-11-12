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

import cats.Monoid

/** Which axis a rule is drawn along. */
enum Axis:
  case X, Y

/** One layer of a plot. The numbers are already computed: a layer only shows them.
  *
  * That is the whole discipline of this module. A layer never estimates a density, never bins, never
  * decides a bandwidth — those are statistics and they live in `diagnostics`, where they are tested
  * against ArviZ. A layer is a picture of numbers somebody else computed, so a plot can be
  * inspected, compared and tested as a value.
  */
enum Layer:
  case Line(x: IArray[Double], y: IArray[Double], group: Option[IArray[String]] = None)
  case Points(x: IArray[Double], y: IArray[Double], highlight: Option[IArray[Boolean]] = None)
  case Ribbon(x: IArray[Double], lower: IArray[Double], upper: IArray[Double])
  case Bars(edges: IArray[Double], counts: IArray[Double], group: Option[IArray[String]] = None)
  case Interval(
      label: IArray[String],
      point: IArray[Double],
      lower: IArray[Double],
      upper: IArray[Double],
      innerLower: Option[IArray[Double]] = None,
      innerUpper: Option[IArray[Double]] = None
  )
  case Rule(value: Double, axis: Axis)
  case Text(x: Double, y: Double, text: String)

/** Small multiples: one panel per value of a layer's group. */
final case class Facet(field: String, columns: Int)

/** A plot: layers, titles, and the size it asks to be drawn at.
  *
  * Pure, comparable, and compiled to a Vega-Lite specification by [[VegaLite]]. Nothing here opens
  * a window, writes a file or renders anything — writing is in `effect` and rendering is a
  * browser's job.
  *
  * Axis titles are not optional in spirit even though they are `Option` in type: the catalogue
  * always sets them, and a figure in the manual without them is a bug (§5.14).
  * **Example.** A plot is a value: layers, titles, and the size it asks to be drawn at. Nothing here
  * renders anything.
  *
  * ```scala
  * //{
  * import cats.syntax.all.*
  * //}
  * val trace = Catalogue.tracePlot(
  *   IArray(IArray.tabulate(100)(i => math.sin(i * 0.1))),
  *   "mu"
  * )
  *
  * // How many marks it is made of, and what they are.
  * val count: Int = trace.layers.length
  *
  * // Superposition: `Plot` is a monoid, so a figure can be assembled from pieces rather than
  * // configured through options. The left plot keeps its titles and its size.
  * val withZero: Plot = trace |+| Plot(List(Layer.Rule(0.0, Axis.Y)))
  * val identity: Boolean = (Plot.empty |+| trace) == trace
  *
  * // Split into panels by a field of the data.
  * val faceted: Plot = trace.copy(facet = Some(Facet("city", columns = 2)))
  * ```
  */
final case class Plot(
    layers: List[Layer],
    title: Option[String] = None,
    xTitle: Option[String] = None,
    yTitle: Option[String] = None,
    facet: Option[Facet] = None,
    width: Int = 400,
    height: Int = 250
)

object Plot:

  val empty: Plot = Plot(Nil)

  /** Superposition: the layers of one plot drawn over the layers of another.
    *
    * The titles and the size come from the left plot when it has any, which is what makes
    * `catalogue(...) |+| annotation` read the way it looks — the thing being annotated keeps its
    * identity. `empty` is a true identity, so the laws hold: that is checked with discipline
    * rather than argued.
    */
  given monoid: Monoid[Plot] with
    def empty: Plot = Plot.empty
    def combine(x: Plot, y: Plot): Plot =
      if x == Plot.empty then y
      else if y == Plot.empty then x
      else
        Plot(
          x.layers ++ y.layers,
          x.title.orElse(y.title),
          x.xTitle.orElse(y.xTitle),
          x.yTitle.orElse(y.yTitle),
          x.facet.orElse(y.facet),
          x.width,
          x.height
        )
