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

import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import cats.kernel.laws.discipline.MonoidTests
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.circe.Json
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.discipline.Discipline
import weaver.scalacheck.Checkers

/** Every plot the catalogue can make, validated against Vega-Lite's own schema — and the contents
  * of a few of them checked by hand.
  *
  * Two different claims, and both are needed. **Validity** says a browser will render the
  * specification rather than showing a blank panel, and it is checked against the official schema
  * committed under `plot/`, not against a guess about what Vega-Lite accepts. **Content** says the
  * picture shows the numbers it was given: the data of a layer is exactly what was computed, the
  * encodings are the expected ones, and the divergent transitions are marked on the right points.
  *
  * No image is ever compared. A test that rendered a picture and diffed pixels would fail on a
  * font, a browser version and a colour profile, and would say nothing about whether the plot is
  * right.
  */
object VegaLiteSuite extends SimpleIOSuite with Discipline with Checkers with Approx:

  /** The committed schema, loaded once. */
  private lazy val validator =
    Fixtures.vegaLiteSchema.map { text =>
      // Vega-Lite's schema declares draft 7, and the validator is told so explicitly rather than
      // left to guess: a schema read under the wrong dialect validates almost everything.
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(text)
    }

  /** The schema's complaints about a specification, empty when it is valid. */
  private def invalid(spec: Json): List[String] =
    validator match
      case Left(error) => List(error)
      case Right(schema) =>
        import scala.jdk.CollectionConverters.*
        schema.validate(spec.noSpaces, InputFormat.JSON).asScala.toList.map(_.toString)

  // --- the catalogue ----------------------------------------------------------------------------

  private val chains =
    IArray.tabulate(3)(c => IArray.tabulate(60)(i => math.sin(c + i * 0.3) + c * 0.2))
  private val divergent =
    IArray.tabulate(3)(c => IArray.tabulate(60)(i => c == 1 && i % 17 == 0))
  private val energies = IArray.tabulate(60)(i => 10.0 + math.cos(i * 0.4))
  private val looResult = LooResult(
    elpd = -12.5,
    se = 1.2,
    pLoo = 2.1,
    pointwiseElpd = IArray.tabulate(8)(i => -1.0 - 0.1 * i),
    paretoK = IArray(0.1, 0.3, 0.75, 0.2, 0.9, 0.4, 0.5, 0.6)
  )
  private val sensitivities = List(
    Sensitivity("mu", 0.02, 0.30, "the data is doing the work"),
    Sensitivity("tau", 0.40, 0.08, "the prior is doing the work")
  )
  private val ensemble = Ensemble
    .of(
      40,
      Periods.monthly(java.time.YearMonth.of(2027, 1), 6).toOption.get,
      Map(() -> IArray.tabulate(40)(s => IArray.tabulate(6)(p => 100.0 + p * 5.0 + s * 0.5)))
    )
    .toOption
    .get

  /** Every plot in the catalogue, by name, so a failure says which one. */
  private val catalogue: List[(String, Plot)] = List(
    "tracePlot" -> Catalogue.tracePlot(chains, "mu", Some(divergent)),
    "tracePlot, no divergences" -> Catalogue.tracePlot(chains, "mu"),
    "trankPlot" -> Catalogue.trankPlot(chains, "mu"),
    "autocorrelationPlot" -> Catalogue.autocorrelationPlot(chains, "mu"),
    "energyPlot" -> Catalogue.energyPlot(energies, SamplerReport.eBfmi(energies)),
    "pairsPlot" -> Catalogue.pairsPlot(chains(0), chains(1), ("mu", "tau"), Some(divergent(1))),
    "densityPlot" -> Catalogue.densityPlot(chains, "mu"),
    "densityPlot, reflected" -> Catalogue.densityPlot(
      IArray(IArray.tabulate(80)(i => math.abs(math.sin(i * 0.7)))),
      "tau",
      reflectAt = Some(0.0)
    ),
    "intervalPlot" -> Catalogue.intervalPlot(
      List(("mu", 1.0, 0.2, 1.8), ("tau", 0.5, 0.1, 1.1))
    ),
    "compareModelsPlot" -> Catalogue.compareModelsPlot(
      List(LooComparison("partial", -53.2, 0.0, 0.0), LooComparison("pooled", -72.9, -19.8, 6.1))
    ),
    "predictivePlot" -> Catalogue.predictivePlot(
      IArray.tabulate(6)(p => 100.0 + p * 4.0),
      IArray.tabulate(40)(r => IArray.tabulate(6)(p => 100.0 + p * 5.0 + r * 0.3))
    ),
    "sbcPlot" -> Catalogue.sbcPlot(IArray.tabulate(64)(i => (i * 7) % 64), 63),
    "paretoKPlot" -> Catalogue.paretoKPlot(looResult),
    "pitPlot" -> Catalogue.pitPlot(IArray.tabulate(50)(i => (i + 0.5) / 50.0)),
    "sensitivityPlot" -> Catalogue.sensitivityPlot(sensitivities),
    "fanChart" -> Catalogue
      .fanChart(ensemble, observed = Some(IArray.tabulate(6)(p => 102.0 + p * 5.0))),
    "shrinkagePlot" -> Catalogue.shrinkagePlot(
      IArray("a", "b", "c"),
      IArray(1.0, 3.0, 2.0),
      IArray(1.5, 2.5, 2.0),
      2.0
    )
  )

  pureTest("every plot in the catalogue produces a specification valid against the schema") {
    val failures = catalogue.flatMap { (name, plot) =>
      invalid(VegaLite.spec(plot)).take(2).map(message => s"$name: $message")
    }
    check(catalogue.length == 17, s"${catalogue.length} plots in the catalogue")
      .and(check(failures.isEmpty, s"invalid specifications: ${failures.take(4).mkString("; ")}"))
  }

  pureTest("a grid of plots, and an empty plot, are valid too") {
    val grid = VegaLite.concat(catalogue.take(4).map(_._2), columns = 2)
    check(invalid(grid).isEmpty, s"the grid is invalid: ${invalid(grid).take(2)}")
      .and {
        val empty = VegaLite.spec(Plot.empty)
        check(invalid(empty).isEmpty, s"an empty plot is invalid: ${invalid(empty).take(2)}")
      }
      .and {
        val faceted = VegaLite.spec(
          Catalogue.trankPlot(chains, "mu").copy(facet = Some(Facet("group", 3)))
        )
        check(invalid(faceted).isEmpty, s"a faceted plot is invalid: ${invalid(faceted).take(2)}")
      }
  }

  pureTest("every plot in the catalogue names both of its axes") {
    // §5.14 makes axis titles mandatory: a figure without them is a bug, not a style choice.
    val missing = catalogue.filter { (_, plot) => plot.xTitle.isEmpty || plot.yTitle.isEmpty }
    check(missing.isEmpty, s"these plots have an unnamed axis: ${missing.map(_._1)}")
      .and(check(catalogue.forall((_, p) => p.title.isDefined), "a plot has no title"))
  }

  // --- the contents -----------------------------------------------------------------------------

  pureTest("a line layer carries exactly the numbers it was given") {
    val plot = Plot(
      List(Layer.Line(IArray(0.0, 1.0, 2.0), IArray(3.0, 4.0, 5.0))),
      xTitle = Some("iteration"),
      yTitle = Some("mu")
    )
    val spec = VegaLite.spec(plot)
    val rows = spec.hcursor.downField("data").downField("values").as[List[Map[String, Json]]]
    check(
      rows.map(_.length) == Right(3),
      s"the layer has ${rows.map(_.length)} rows"
    )
      .and(
        check(
          rows.map(_.map(r => r("x").asNumber.flatMap(_.toBigDecimal).map(_.toDouble))) ==
            Right(List(Some(0.0), Some(1.0), Some(2.0))),
          s"the x values are ${rows.map(_.map(_("x")))}"
        )
      )
      .and(
        check(
          rows.map(_.map(r => r("y").asNumber.flatMap(_.toBigDecimal).map(_.toDouble))) ==
            Right(List(Some(3.0), Some(4.0), Some(5.0))),
          "the y values are wrong"
        )
      )
      // The axis titles are on the encoding, which is what Vega-Lite draws.
      .and(
        check(
          spec.hcursor.downField("encoding").downField("x").downField("title").as[String] ==
            Right("iteration"),
          "the x axis has no title in the specification"
        )
      )
      .and(
        check(
          spec.hcursor.downField("mark").downField("type").as[String] == Right("line"),
          "not a line"
        )
      )
  }

  pureTest("divergent transitions are marked on exactly the right points") {
    // The claim a trace plot makes and the one worth checking: the marked points are the diverging
    // iterations of the diverging chain, and no others.
    val flags = IArray(
      IArray(false, false, true, false),
      IArray(false, true, false, false)
    )
    val two = IArray(IArray(1.0, 2.0, 3.0, 4.0), IArray(5.0, 6.0, 7.0, 8.0))
    val plot = Catalogue.tracePlot(two, "mu", Some(flags))
    val marks = plot.layers.collect { case p: Layer.Points => p }
    check(marks.length == 2, s"${marks.length} layers of marked points for two diverging chains")
      .and(
        check(
          marks(0).x.toList == List(2.0),
          s"the first chain's marks are at ${marks(0).x.toList}"
        )
      )
      .and(check(marks(0).y.toList == List(3.0), "the first chain's mark is at the wrong value"))
      .and(
        check(
          marks(1).x.toList == List(1.0),
          s"the second chain's marks are at ${marks(1).x.toList}"
        )
      )
      .and(check(marks(1).y.toList == List(6.0), "the second chain's mark is at the wrong value"))
      // And with no divergences there are no marks at all, rather than an empty layer.
      .and(
        check(
          Catalogue.tracePlot(two, "mu").layers.forall {
            case _: Layer.Points => false
            case _ => true
          },
          "a trace plot with no divergences still has a layer of marks"
        )
      )
  }

  pureTest("an interval layer carries the axis titles on every mark it is made of") {
    // An `Interval` compiles to a stack of marks, so the titles have to reach each of them: a
    // spec whose rule is titled and whose point is not renders with an axis named by accident.
    val spec = VegaLite.spec(
      Catalogue.intervalPlot(List(("mu", 0.5, 0.1, 0.9), ("tau", 1.5, 1.1, 1.9)))
    )
    val marks = spec.hcursor.downField("layer").values.toList.flatten
    val titled = marks.filter { mark =>
      mark.hcursor.downField("encoding").downField("x").downField("title").as[String] == Right(
        "value"
      ) &&
      mark.hcursor.downField("encoding").downField("y").downField("title").as[String] == Right(
        "parameter"
      )
    }
    check(marks.nonEmpty, "the interval compiled to no marks at all")
      .and(
        check(titled.length == marks.length, s"${marks.length - titled.length} marks are untitled")
      )
  }

  pureTest("the Pareto plot marks the observations above the threshold, and draws both lines") {
    val plot = Catalogue.paretoKPlot(looResult, threshold = 0.7)
    val marks = plot.layers.collect { case p: Layer.Points => p }.head
    val rules = plot.layers.collect { case r: Layer.Rule => r }
    check(
      marks.highlight.map(_.toList) == Some(
        List(false, false, true, false, true, false, false, false)
      ),
      s"the flagged observations are ${marks.highlight.map(_.toList)}"
    )
      .and(
        check(rules.map(_.value).toSet == Set(0.7, 0.5), s"the rules are at ${rules.map(_.value)}")
      )
      .and(check(rules.forall(_.axis == Axis.Y), "a threshold was drawn along the wrong axis"))
  }

  pureTest("the fan chart draws its bands widest first, so the narrow one stays visible") {
    val plot = Catalogue.fanChart(ensemble, masses = List(0.5, 0.9))
    val ribbons = plot.layers.collect { case r: Layer.Ribbon => r }
    check(ribbons.length == 2, s"${ribbons.length} bands")
      // The 90 % band is drawn first and is wider everywhere than the 50 % one on top of it.
      .and(
        check(
          (0 until ribbons.head.x.length).forall(i =>
            ribbons.head.upper(i) - ribbons.head.lower(i) >=
              ribbons(1).upper(i) - ribbons(1).lower(i)
          ),
          "the bands are not in order of width, so the inner one is hidden"
        )
      )
      .and(check(plot.layers.collect { case l: Layer.Line => l }.length == 1, "no median line"))
  }

  pureTest("the escape hatch edits the specification and changes nothing else") {
    val plot = Catalogue.densityPlot(chains, "mu")
    val edited = VegaLite.raw(plot)(json =>
      json.deepMerge(io.circe.Json.obj("background" -> io.circe.Json.fromString("#111111")))
    )
    check(
      edited.hcursor.downField("background").as[String] == Right("#111111"),
      "the edit did not take"
    )
      .and(
        check(invalid(edited).isEmpty, s"the edited spec is invalid: ${invalid(edited).take(2)}")
      )
      .and(
        check(
          edited.mapObject(_.remove("background")) == VegaLite.spec(plot),
          "the edit changed something other than what it edited"
        )
      )
  }

  pureTest("a page pins its versions and reports errors where a reader can see them") {
    val page = Html.page(List(VegaLite.spec(catalogue.head._2)), "A title & <b>")
    check(page.contains("vega-lite@6"), "the Vega-Lite version is not pinned")
      .and(check(page.contains("vega-embed@6"), "the embed version is not pinned"))
      .and(check(!page.contains("@latest"), "a dependency is loaded as 'latest'"))
      .and(check(page.contains("A title &amp; &lt;b&gt;"), "the title is not escaped"))
      .and(check(page.contains("id=\"errors\""), "there is nowhere for an error to appear"))
      .and(check(page.contains("figure-0"), "the figure has no container"))
  }

  pureTest("a report carries a heading and a caption per figure, and escapes both") {
    val figures = List(
      Figure("Trace & drift", "Two chains over <iterations>.", VegaLite.spec(catalogue.head._2)),
      Figure("Density", "The pooled posterior.", VegaLite.spec(catalogue(1)._2))
    )
    val page =
      Html.report(figures, "The catalogue", "Every figure on invented data & nothing else.")
    check(page.contains("<h2>Trace &amp; drift</h2>"), "the heading is missing or unescaped")
      .and(
        check(page.contains("Two chains over &lt;iterations&gt;."), "the caption is not escaped")
      )
      .and(check(page.contains("figure-0") && page.contains("figure-1"), "a container is missing"))
      .and(check(page.contains("invented data &amp; nothing"), "the opening text is not escaped"))
      // The plain form is the same renderer with nothing to say, so it must carry no empty markup.
      .and {
        val plain = Html.page(List(VegaLite.spec(catalogue.head._2)), "Plain")
        val furniture = plain.contains("<h2>") || plain.contains("<p class=\"caption\"") ||
          plain.contains("<p class=\"intro\"")
        check(!furniture, "the plain page has furniture nobody asked for")
      }
  }

  pureTest("an embedded figure carries its alternative text, and nothing can break out of it") {
    val spec = VegaLite.spec(catalogue.head._2)
    val fragment =
      Html.embed("figure-trace", spec, "Two chains of mu, overlapping, no divergent transition.")
    check(fragment.contains("id=\"figure-trace\""), "the container has no identifier")
      .and(
        check(
          fragment.contains("aria-label=\"Two chains of mu"),
          "there is no label for a screen reader"
        )
      )
      // The text is *in* the container, so it is what a reader sees until the figure draws — and
      // for ever if the script never runs.
      .and(
        check(
          fragment.contains(">Two chains of mu, overlapping, no divergent transition.<"),
          "no visible fallback"
        )
      )
      .and(check(fragment.contains("vegaEmbed('#figure-trace'"), "nothing draws the figure"))
      .and {
        val hostile =
          Html.embed("x\" onload=\"evil()", spec, "a <script>alert(1)</script> caption & more")
        check(
          !hostile.contains("onload=\"evil()") && !hostile.contains("<script>alert(1)"),
          "an identifier or a caption can break out of the markup"
        )
      }
      // The scripts are separate, because mdoc writes the page and laika renders it.
      .and(
        check(
          Html.scripts.contains("vega-lite@6") && !fragment.contains("<script src"),
          "the scripts are not separate"
        )
      )
  }

  pureTest("superposition keeps the layers of both plots and the left one's identity") {
    // The laws say `empty` is an identity; they cannot say what superposition *is*, because the
    // implementation short-circuits on `empty` and a mutated identity would short-circuit too.
    val left = Plot(
      List(Layer.Line(IArray(0.0), IArray(1.0))),
      Some("left"),
      Some("x"),
      Some("y"),
      None,
      300,
      200
    )
    val right =
      Plot(List(Layer.Rule(0.0, Axis.Y)), Some("right"), Some("u"), Some("v"), None, 500, 400)
    val combined = Plot.monoid.combine(left, right)
    check(combined.layers == left.layers ++ right.layers, s"the layers are ${combined.layers}")
      .and(check(combined.title == Some("left"), "the right plot's title won"))
      .and(
        check(combined.xTitle == Some("x") && combined.yTitle == Some("y"), "an axis was renamed")
      )
      .and(check((combined.width, combined.height) == (300, 200), "the right plot's size won"))
      .and(check(Plot.empty.layers.isEmpty, "the empty plot draws something"))
  }

  // --- the laws ---------------------------------------------------------------------------------

  private given Arbitrary[Plot] = Arbitrary(
    for
      layers <- Gen.listOfN(
        2,
        Gen.oneOf(
          Gen.const(Layer.Line(IArray(0.0, 1.0), IArray(1.0, 2.0))),
          Gen.const(Layer.Rule(0.5, Axis.X)),
          Gen.const(Layer.Text(0.0, 0.0, "x"))
        )
      )
      take <- Gen.choose(0, 2)
      title <- Gen.option(Gen.oneOf("a", "b"))
      width <- Gen.oneOf(300, 400)
    yield
      val chosen = layers.take(take)
      if chosen.isEmpty && title.isEmpty && width == 400 then Plot.empty
      else Plot(chosen, title, Some("x"), Some("y"), None, width, 250)
  )

  private given cats.Eq[Plot] = cats.Eq.fromUniversalEquals

  checkAll("Plot.MonoidLaws", MonoidTests[Plot].monoid)
