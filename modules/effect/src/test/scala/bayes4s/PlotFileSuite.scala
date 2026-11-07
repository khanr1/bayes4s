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
import cats.effect.IO
import fs2.io.file.Files
import weaver.SimpleIOSuite

/** Plots on disk.
  *
  * There is almost nothing to this module and that is the point: everything worth testing about a
  * plot — its data, its encodings, its validity against the schema — is tested in `plot`, where it
  * is a value. What is left here is that the bytes reach the file and that the file is what a
  * browser will open.
  */
object PlotFileSuite extends SimpleIOSuite with Approx:

  private val chains = IArray.tabulate(2)(c => IArray.tabulate(40)(i => math.sin(c + i * 0.4)))
  private val plots = List(
    Catalogue.tracePlot(chains, "mu"),
    Catalogue.densityPlot(chains, "mu")
  )

  test("a page holds one figure per plot, and the versions it pins") {
    Files[IO].tempDirectory.use { directory =>
      val path = directory / "figures.html"
      for
        _ <- PlotFile[IO].writeHtml(path, plots, "Two figures")
        written <- Files[IO].readUtf8(path).compile.string
      yield check(
        written.contains("figure-0") && written.contains("figure-1"),
        "a figure is missing"
      )
        .and(check(written.contains("vega-lite@6"), "the Vega-Lite version is not pinned"))
        .and(check(written.contains("Two figures"), "the title is missing"))
        // The specifications are inlined, so the page needs nothing else to render.
        .and(check(written.contains("\"values\":"), "the data is not in the page"))
        .and(check(!written.contains("http://localhost"), "the page refers to something local"))
    }
  }

  test("a report reaches the file with its headings and captions") {
    Files[IO].tempDirectory.use { directory =>
      val path = directory / "report.html"
      val figures = plots.zipWithIndex.map { (plot, i) =>
        Figure(s"Figure $i", s"What figure $i says.", VegaLite.spec(plot))
      }
      for
        _ <- PlotFile[IO].writeReport(path, figures, "A demonstration", "Invented data.")
        written <- Files[IO].readUtf8(path).compile.string
      yield check(written.contains("<h2>Figure 0</h2>"), "a heading is missing")
        .and(check(written.contains("What figure 1 says."), "a caption is missing"))
        .and(check(written.contains("Invented data."), "the opening text is missing"))
        .and(
          check(
            written == Html.report(figures, "A demonstration", "Invented data."),
            "not the page"
          )
        )
    }
  }

  test("a specification is written as readable JSON that parses back") {
    Files[IO].tempDirectory.use { directory =>
      val path = directory / "trace.json"
      for
        _ <- PlotFile[IO].writeJson(path, plots.head)
        written <- Files[IO].readUtf8(path).compile.string
      yield
        val parsed = io.circe.parser.parse(written)
        check(parsed.isRight, s"the file does not parse: ${parsed.left.map(_.getMessage)}")
          .and(
            check(parsed == Right(VegaLite.spec(plots.head)), "the file is not the specification")
          )
          // Indented, because a specification in a repository is read and edited by people.
          .and(check(written.contains("\n  "), "the JSON is not indented"))
    }
  }

  test("writing into a directory that does not exist is an error of F") {
    Files[IO].tempDirectory.use { directory =>
      PlotFile[IO]
        .writeJson(directory / "absent" / "trace.json", plots.head)
        .attempt
        .map(attempted => check(attempted.isLeft, "writing into a missing directory succeeded"))
    }
  }
