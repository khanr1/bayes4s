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

import cats.effect.Concurrent
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.text

/** Plots written to disk: the only part of drawing one that is an effect.
  *
  * A plot is a value and its specification is JSON; both are made in the `plot` module, where
  * they can be compared and tested. This writes them, and does nothing else — no rendering, no
  * browser, no image. An SVG or a PNG is `vl-convert`'s job, and the manual says how.
  * **Example.** The only part of drawing a figure that is an effect:
  *
  * ```scala
  * //{
  * import bayes4s.syntax.{*, given}
  * import cats.effect.IO
  * import fs2.io.file.Path
  * final case class Mean[A](mu: A, sigma: A)
  * val observed = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4)
  * val model: Model[Mean] = new Model[Mean]:
  *   def params: ParamSpec[Mean] =
  *     (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
  *       .mapN[Mean]([A] => (mu: A, sigma: A) => Mean(mu, sigma))
  *   def logPrior[A: Real](p: Mean[A]): A =
  *     LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
  *       LogDensity.halfNormal(p.sigma, 5.0.lift[A])
  *   def logLikelihood[A: Real](p: Mean[A]): A =
  *     Real[A].normalSum(observed, Real[A].filled(observed.length)(p.mu), p.sigma)
  * //}
  * val plot = Catalogue.tracePlot(IArray(IArray.tabulate(50)(i => i * 0.1)), "mu")
  *
  * val written: IO[Unit] = fs2.io.file.Files[IO].tempDirectory.use { directory =>
  *   // A page a browser renders when it is opened.
  *   PlotFile[IO].writeHtml(directory / "figures.html", List(plot), "Two chains") *>
  *     // One specification, for a notebook, an editor, or `vl-convert`.
  *     PlotFile[IO].writeJson(directory / "trace.vl.json", plot)
  * }
  * ```
  */
trait PlotFile[F[_]]:

  /** A page of figures, rendered by a browser when it is opened. */
  def writeHtml(path: Path, plots: List[Plot], title: String): F[Unit]

  /** A page of figures with headings and captions: a demonstration, or a report. */
  def writeReport(path: Path, figures: List[Figure], title: String, intro: String): F[Unit]

  /** One specification, for a notebook, an editor, or `vl-convert`. */
  def writeJson(path: Path, plot: Plot): F[Unit]

object PlotFile:

  def apply[F[_]: Files: Concurrent]: PlotFile[F] = new PlotFile[F]:

    def writeHtml(path: Path, plots: List[Plot], title: String): F[Unit] =
      write(path, Html.page(plots.map(VegaLite.spec), title))

    def writeReport(path: Path, figures: List[Figure], title: String, intro: String): F[Unit] =
      write(path, Html.report(figures, title, intro))

    def writeJson(path: Path, plot: Plot): F[Unit] =
      write(path, VegaLite.spec(plot).spaces2)

    private def write(path: Path, content: String): F[Unit] =
      Stream
        .emit(content)
        .through(text.utf8.encode)
        .through(Files[F].writeAll(path))
        .compile
        .drain
