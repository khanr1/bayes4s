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

import io.circe.Json

/** A plot compiled to a Vega-Lite specification: pure JSON, no renderer.
  *
  * Vega-Lite because it is a declarative grammar with an official schema, rendered by any browser,
  * embeddable in the manual, in a notebook and in an editor — and because its specifications are
  * JSON, which can be inspected and asserted on. Every specification this produces is validated
  * against the committed schema in the suite, so a plot that would not render is a test failure
  * rather than a blank page.
  *
  * The data is inlined in each layer. That keeps a specification self-contained — one file, one
  * picture, no paths to resolve — at the cost of size, which is the right trade for figures in a
  * manual and for a few thousand points.
  * **Example.** Compiling a plot to a specification, several into a grid, and editing one when the
  * catalogue is nearly right:
  *
  * ```scala
  * //{
  * import io.circe.Json
  * //}
  * val plot = Catalogue.tracePlot(IArray(IArray.tabulate(50)(i => i * 0.1)), "mu")
  *
  * // One figure, as Vega-Lite JSON. Validated against Vega-Lite's own schema by the suite.
  * val spec: Json = VegaLite.spec(plot)
  *
  * // Several side by side, as one specification.
  * val grid: Json = VegaLite.concat(List(plot, plot), columns = 2)
  *
  * // The escape hatch: rather than grow options until the catalogue is a second grammar, the
  * // specification is handed over and Vega-Lite's own documentation applies.
  * val edited: Json =
  *   VegaLite.raw(plot)(_.deepMerge(Json.obj("background" -> Json.fromString("#fafafa"))))
  * ```
  */
object VegaLite:

  /** The schema version the specifications declare, and the one committed under `plot/`. */
  val schemaUrl: String = "https://vega.github.io/schema/vega-lite/v6.json"

  /** The palette: readable with colour blindness, and legible on light and dark backgrounds.
    *
    * Okabe and Ito's, minus the yellow that disappears on white. The order matters — the first two
    * are the ones a two-chain or two-model comparison gets.
    */
  val palette: List[String] =
    List("#0072B2", "#D55E00", "#009E73", "#CC79A7", "#56B4E9", "#E69F00", "#000000")

  /** The colour a highlighted point gets: divergent transitions, flagged observations. */
  val highlight: String = "#D55E00"

  def spec(p: Plot): Json =
    val layers = p.layers.map(layer => withTitles(layerSpec(layer), p))
    val base = Json.obj(
      "$schema" -> Json.fromString(schemaUrl),
      "width" -> Json.fromInt(p.width),
      "height" -> Json.fromInt(p.height)
    )
    val titled = p.title.fold(base)(t => base.deepMerge(Json.obj("title" -> Json.fromString(t))))
    val body =
      if layers.isEmpty then Json.obj("mark" -> Json.fromString("point"), "data" -> values(Nil))
      else if layers.length == 1 then layers.head
      else Json.obj("layer" -> Json.arr(layers*))
    val composed = titled.deepMerge(body)
    p.facet.fold(composed)(f => faceted(layers, p, f))

  /** A faceted specification: the data hoisted, and each layer filtered back to its own rows.
    *
    * Vega-Lite's facet operator reads its data at the **top** level, so a specification whose
    * layers each carry their own inlined data cannot simply be wrapped — the schema rejects it,
    * which is how this was found rather than by a blank panel in a browser. The rows of every
    * layer are therefore pooled into one dataset, tagged with the layer they came from, and each
    * layer filters itself back out.
    */
  private def faceted(layers: List[Json], p: Plot, f: Facet): Json =
    val tagged = layers.zipWithIndex.flatMap { (layer, i) =>
      layer.hcursor
        .downField("data")
        .downField("values")
        .as[List[Json]]
        .getOrElse(Nil)
        .map(row => row.deepMerge(Json.obj(layerField -> Json.fromInt(i))))
    }
    val filtered = layers.zipWithIndex.map { (layer, i) =>
      layer
        .mapObject(_.remove("data"))
        .deepMerge(
          Json.obj(
            "transform" -> Json.arr(
              Json.obj("filter" -> Json.fromString(s"datum.$layerField === $i"))
            )
          )
        )
    }
    val panel =
      if filtered.length == 1 then filtered.head else Json.obj("layer" -> Json.arr(filtered*))
    val sized = Json.obj("width" -> Json.fromInt(p.width), "height" -> Json.fromInt(p.height))
    Json
      .obj(
        "$schema" -> Json.fromString(schemaUrl),
        "data" -> Json.obj("values" -> Json.arr(tagged*)),
        "facet" -> Json.obj(
          "field" -> Json.fromString(f.field),
          "type" -> Json.fromString("nominal")
        ),
        "columns" -> Json.fromInt(math.max(f.columns, 1)),
        "spec" -> panel.deepMerge(sized)
      )
      .deepMerge(p.title.fold(Json.obj())(t => Json.obj("title" -> Json.fromString(t))))

  /** The field a faceted specification tags its pooled rows with. */
  private val layerField: String = "__layer"

  /** Several plots side by side, in a grid. */
  def concat(ps: List[Plot], columns: Int): Json =
    Json.obj(
      "$schema" -> Json.fromString(schemaUrl),
      "columns" -> Json.fromInt(math.max(columns, 1)),
      "concat" -> Json.arr(ps.map(p => spec(p).mapObject(_.remove("$schema")))*)
    )

  /** The escape hatch: the specification, edited.
    *
    * The catalogue is finite and somebody will always want something it does not do. Rather than
    * grow options until the API is a second grammar, the specification is handed over as JSON —
    * Vega-Lite's own documentation then applies, and nothing here has to pretend to cover it.
    */
  def raw(p: Plot)(edit: Json => Json): Json = edit(spec(p))

  // --- the layers -------------------------------------------------------------------------------

  private def layerSpec(layer: Layer): Json = layer match
    case Layer.Line(x, y, group) =>
      val rows = (0 until x.length).toList.map { i =>
        Json.obj(
          "x" -> number(x(i)),
          "y" -> number(y(i)),
          "group" -> group.fold(Json.Null)(g => Json.fromString(g(i)))
        )
      }
      Json.obj(
        "data" -> values(rows),
        "mark" -> Json.obj("type" -> Json.fromString("line"), "clip" -> Json.True),
        "encoding" -> encoding(group.isDefined)
      )

    case Layer.Points(x, y, highlighted) =>
      val rows = (0 until x.length).toList.map { i =>
        Json.obj(
          "x" -> number(x(i)),
          "y" -> number(y(i)),
          "highlight" -> highlighted.fold(Json.False)(h => Json.fromBoolean(h(i)))
        )
      }
      val colour =
        if highlighted.isEmpty then Json.obj()
        else
          Json.obj(
            "color" -> Json.obj(
              "field" -> Json.fromString("highlight"),
              "type" -> Json.fromString("nominal"),
              "title" -> Json.fromString("divergent"),
              "scale" -> Json.obj(
                "domain" -> Json.arr(Json.False, Json.True),
                "range" -> Json.arr(Json.fromString(palette.head), Json.fromString(highlight))
              )
            )
          )
      Json.obj(
        "data" -> values(rows),
        "mark" -> Json.obj("type" -> Json.fromString("point"), "filled" -> Json.True),
        "encoding" -> encoding(false).deepMerge(colour)
      )

    case Layer.Ribbon(x, lower, upper) =>
      val rows = (0 until x.length).toList.map { i =>
        Json.obj("x" -> number(x(i)), "lower" -> number(lower(i)), "upper" -> number(upper(i)))
      }
      Json.obj(
        "data" -> values(rows),
        "mark" -> Json
          .obj("type" -> Json.fromString("area"), "opacity" -> Json.fromDoubleOrNull(0.3)),
        "encoding" -> Json.obj(
          "x" -> quantitative("x"),
          "y" -> quantitative("lower"),
          "y2" -> Json.obj("field" -> Json.fromString("upper"))
        )
      )

    case Layer.Bars(edges, counts, group) =>
      val rows = (0 until counts.length).toList.map { i =>
        Json.obj(
          "x" -> number(edges(i)),
          "x2" -> number(if i + 1 < edges.length then edges(i + 1) else edges(i)),
          "y" -> number(counts(i)),
          "group" -> group.fold(Json.Null)(g => Json.fromString(g(i)))
        )
      }
      Json.obj(
        "data" -> values(rows),
        "mark" -> Json.fromString("bar"),
        "encoding" -> Json
          .obj(
            "x" -> quantitative("x"),
            "x2" -> Json.obj("field" -> Json.fromString("x2")),
            "y" -> quantitative("y")
          )
          .deepMerge(if group.isDefined then colourBy("group") else Json.obj())
      )

    case Layer.Interval(label, point, lower, upper, innerLower, innerUpper) =>
      val rows = (0 until label.length).toList.map { i =>
        Json.obj(
          "label" -> Json.fromString(label(i)),
          "point" -> number(point(i)),
          "lower" -> number(lower(i)),
          "upper" -> number(upper(i)),
          "innerLower" -> innerLower.fold(Json.Null)(v => number(v(i))),
          "innerUpper" -> innerUpper.fold(Json.Null)(v => number(v(i)))
        )
      }
      val outer = Json.obj(
        "mark" -> Json.obj("type" -> Json.fromString("rule")),
        "encoding" -> Json.obj(
          "y" -> Json
            .obj("field" -> Json.fromString("label"), "type" -> Json.fromString("nominal")),
          "x" -> quantitative("lower"),
          "x2" -> Json.obj("field" -> Json.fromString("upper"))
        )
      )
      val inner = Json.obj(
        "mark" -> Json.obj("type" -> Json.fromString("rule"), "size" -> Json.fromInt(3)),
        "encoding" -> Json.obj(
          "y" -> Json
            .obj("field" -> Json.fromString("label"), "type" -> Json.fromString("nominal")),
          "x" -> quantitative("innerLower"),
          "x2" -> Json.obj("field" -> Json.fromString("innerUpper"))
        )
      )
      val dot = Json.obj(
        "mark" -> Json.obj(
          "type" -> Json.fromString("point"),
          "filled" -> Json.True,
          "size" -> Json.fromInt(60)
        ),
        "encoding" -> Json.obj(
          "y" -> Json
            .obj("field" -> Json.fromString("label"), "type" -> Json.fromString("nominal")),
          "x" -> quantitative("point")
        )
      )
      val marks = if innerLower.isDefined && innerUpper.isDefined then List(outer, inner, dot)
      else List(outer, dot)
      Json.obj("data" -> values(rows), "layer" -> Json.arr(marks*))

    case Layer.Rule(value, axis) =>
      val field = if axis == Axis.X then "x" else "y"
      Json.obj(
        "data" -> values(List(Json.obj(field -> number(value)))),
        "mark" -> Json.obj(
          "type" -> Json.fromString("rule"),
          "strokeDash" -> Json.arr(Json.fromInt(4), Json.fromInt(4))
        ),
        "encoding" -> Json.obj(field -> quantitative(field))
      )

    case Layer.Text(x, y, text) =>
      Json.obj(
        "data" -> values(
          List(Json.obj("x" -> number(x), "y" -> number(y), "label" -> Json.fromString(text)))
        ),
        "mark" -> Json.obj("type" -> Json.fromString("text"), "align" -> Json.fromString("left")),
        "encoding" -> Json.obj(
          "x" -> quantitative("x"),
          "y" -> quantitative("y"),
          "text" -> Json.obj(
            "field" -> Json.fromString("label"),
            "type" -> Json.fromString("nominal")
          )
        )
      )

  private def encoding(grouped: Boolean): Json =
    val base = Json.obj("x" -> quantitative("x"), "y" -> quantitative("y"))
    if grouped then base.deepMerge(colourBy("group")) else base

  private def colourBy(field: String): Json =
    Json.obj(
      "color" -> Json.obj(
        "field" -> Json.fromString(field),
        "type" -> Json.fromString("nominal"),
        "scale" -> Json.obj("range" -> Json.arr(palette.map(Json.fromString)*))
      )
    )

  private def quantitative(field: String): Json =
    Json.obj("field" -> Json.fromString(field), "type" -> Json.fromString("quantitative"))

  private def values(rows: List[Json]): Json = Json.obj("values" -> Json.arr(rows*))

  /** A number Vega-Lite can read: `NaN` and the infinities become null rather than invalid JSON. */
  private def number(x: Double): Json = Json.fromDoubleOrNull(x)

  /** The axis titles, applied to whatever encodings a layer produced.
    *
    * Titles are set on the layers rather than hoisted to the top, because a layered specification
    * with its own top-level encoding is harder to edit through [[raw]] — and because §5.14 makes
    * axis titles mandatory, so every layer that has an axis should carry them.
    */
  private def withTitles(layer: Json, p: Plot): Json =
    val x = p.xTitle.fold(Json.obj())(t => Json.obj("x" -> Json.obj("title" -> Json.fromString(t))))
    val y = p.yTitle.fold(Json.obj())(t => Json.obj("y" -> Json.obj("title" -> Json.fromString(t))))
    val titles = x.deepMerge(y)
    if titles.asObject.exists(_.isEmpty) then layer
    else
      // An `Interval` layer is itself a stack of marks, so the titles go inside each of them.
      layer.asObject.flatMap(_.apply("layer")).flatMap(_.asArray) match
        case Some(inner) =>
          layer.deepMerge(
            Json.obj(
              "layer" -> Json.arr(
                inner.map(one => one.deepMerge(Json.obj("encoding" -> titles)))*
              )
            )
          )
        case None => layer.deepMerge(Json.obj("encoding" -> titles))
