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

package bayes4s.examples

import bayes4s.*
import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.circe.Json
import weaver.SimpleIOSuite

/** The demonstration page, checked without a browser.
  *
  * The page published with the manual is built by this same function, under heavier settings, so
  * what the suite checks is the page: every figure a real fit produced, validated against
  * Vega-Lite's own schema. The schema is the independent reference — it is Vega-Lite's, committed
  * under `testkit`, not anything this library believes about itself.
  *
  * What a browser adds on top of this is in `scripts/smoke-plots.mjs`, which CI runs: a
  * specification can be valid and still throw, and only a renderer can say.
  *
  * The two claims about the models themselves — that `pointwise` sums to `logLikelihood`, and that
  * a prior draw survives the round trip through the unconstrained space — are the obligations
  * `PointwiseLikelihood` and simulation-based calibration carry. The figures would still be drawn
  * if either were broken, and the page would be quietly wrong.
  */
object CatalogueDemoSuite extends SimpleIOSuite with Approx:

  private lazy val drawn = CatalogueDemo.figures(CatalogueDemo.quick)

  private lazy val validator =
    Fixtures.vegaLiteSchema.map { text =>
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(text)
    }

  private def invalid(spec: Json): List[String] =
    validator match
      case Left(error) => List(error)
      case Right(schema) =>
        import scala.jdk.CollectionConverters.*
        schema.validate(spec.noSpaces, InputFormat.JSON).asScala.toList.map(_.toString)

  pureTest("every figure of the page is a valid Vega-Lite specification") {
    drawn match
      case Left(error) => failure(s"the page could not be built: $error")
      case Right(figures) =>
        val broken = figures.filter(f => invalid(f.spec).nonEmpty)
        check(figures.length == 16, s"the page has ${figures.length} figures and not 16")
          .and(
            check(
              broken.isEmpty,
              s"invalid: ${broken.map(f => s"${f.heading}: ${invalid(f.spec).take(1)}").mkString("; ")}"
            )
          )
          .and(check(figures.forall(_.heading.nonEmpty), "a figure has no heading"))
          .and(check(figures.forall(_.caption.nonEmpty), "a figure has no caption"))
          // A figure with no data would validate and show nothing, which is the failure a schema
          // cannot see.
          .and {
            val empty = figures.filter(f => !f.spec.noSpaces.contains("\"values\":[{"))
            check(empty.isEmpty, s"no data in: ${empty.map(_.heading).mkString("; ")}")
          }
  }

  pureTest("the page is a function of its settings and of nothing else") {
    val again = CatalogueDemo.figures(CatalogueDemo.quick)
    val first = drawn.map(fs => Html.report(fs, CatalogueDemo.title, CatalogueDemo.intro))
    val second = again.map(fs => Html.report(fs, CatalogueDemo.title, CatalogueDemo.intro))
    check(first == second, "two runs of the same settings gave different pages")
      .and(check(first.isRight, "the page could not be built"))
  }

  pureTest("the page names every figure it draws") {
    drawn match
      case Left(error) => failure(s"the page could not be built: $error")
      case Right(figures) =>
        val page = Html.report(figures, CatalogueDemo.title, CatalogueDemo.intro)
        val missing = figures.filter(f => !page.contains(f.heading))
        check(missing.isEmpty, s"headings missing from the page: ${missing.map(_.heading)}")
          .and(check(page.contains("vega-lite@6"), "the Vega-Lite version is not pinned"))
          .and(check(page.contains("id=\"errors\""), "the smoke test has nothing to read"))
  }

  pureTest("the pointwise likelihood of each model sums to its likelihood") {
    val world = FictionalWorld.simulate(CatalogueDemo.quick.world).runA(Seed(5L)).value
    val panel = CityMonths.of(world, CatalogueDemo.quick.fitted)
    val anchor = math.log(panel.y.sum.toDouble / panel.rows)
    val varying = CatalogueDemo.varying(panel, anchor)
    val pooled = CatalogueDemo.pooled(panel, anchor)
    val byCity = ByCity(anchor, 0.3, 12.0, IArray(0.4, -0.2, 0.1, -0.6))
    val oneRate = OneRate(anchor, 12.0)
    closeRel(
      varying.pointwise(byCity).sum,
      varying.logLikelihood(byCity),
      1e-12,
      "the multilevel model's parts do not sum to its whole"
    ).and(
      closeRel(
        pooled.pointwise(oneRate).sum,
        pooled.logLikelihood(oneRate),
        1e-12,
        "the pooled model's parts do not sum to its whole"
      )
    )
  }

  pureTest("a draw from the prior survives the round trip calibration puts it through") {
    val world = FictionalWorld.simulate(CatalogueDemo.quick.world).runA(Seed(6L)).value
    val panel = CityMonths.of(world, CatalogueDemo.quick.fitted)
    val anchor = math.log(panel.y.sum.toDouble / panel.rows)
    val generative = CatalogueDemo.generative(panel, anchor)
    val drawnPrior = generative.generative.samplePrior[Rand].runA(Seed(7L)).value
    val named = generative.generative.named(drawnPrior)
    Model.unconstrain(generative.model, named) match
      case Left(error) => failure(s"the prior draw does not fit the model's own layout: $error")
      case Right(point) =>
        val back = Model.constrain(generative.model, point)
        closeRel(back.mu, drawnPrior.mu, 1e-12, "mu did not survive")
          .and(closeRel(back.tau, drawnPrior.tau, 1e-12, "tau did not survive"))
          .and(
            closeRel(
              back.dispersion,
              drawnPrior.dispersion,
              1e-12,
              "the dispersion did not survive"
            )
          )
          .and(
            check(
              (0 until panel.cities).forall(c => math.abs(back.z(c) - drawnPrior.z(c)) < 1e-12),
              "a city's standardised effect did not survive"
            )
          )
  }
