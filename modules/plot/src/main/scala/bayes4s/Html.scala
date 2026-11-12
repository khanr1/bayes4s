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

/** One figure on a page: what it is, what it says, and the specification that draws it. */
final case class Figure(heading: String, caption: String, spec: Json)

/** A page that renders specifications in a browser, as a string.
  *
  * Pure: it builds the HTML and nothing writes it. The versions of vega, vega-lite and vega-embed
  * are **pinned** — a page that loaded "latest" would render differently next year than it does in
  * the manual today, and a figure that silently changes is worse than one that breaks.
  * **Example.** A page of figures, and the two pieces for putting one figure into a page somebody
  * else is building — which is how this manual's figures are made:
  *
  * ```scala
  * val plot = Catalogue.tracePlot(IArray(IArray.tabulate(50)(i => i * 0.1)), "mu")
  *
  * // A whole page, with the vega versions pinned and an error box a smoke test can read.
  * val page: String = Html.page(List(VegaLite.spec(plot)), "Two chains")
  *
  * // With a heading and a caption per figure.
  * val reported: String =
  *   Html.report(List(Figure("A trace", "Two chains of mu.", VegaLite.spec(plot))), "A report", "")
  *
  * // Or the pieces: the scripts once near the top of a page, then one fragment per figure. The
  * // alternative text is required — a figure drawn by a script is invisible until it draws, and
  * // invisible for ever to a reader who cannot see it.
  * val scripts: String = Html.scripts
  * val fragment: String =
  *   Html.embed("fig-trace", VegaLite.spec(plot), "Two chains of mu, overlapping, no divergences.")
  * ```
  */
object Html:

  val vegaVersion: String = "5"
  val vegaLiteVersion: String = "6"
  val vegaEmbedVersion: String = "6"

  /** The script tags a page needs before any figure of it will draw.
    *
    * A manual page is rendered by something else — mdoc writes the markdown, laika renders the
    * HTML — so the scripts cannot come from [[page]]. This is them on their own, to be emitted
    * once near the top of such a page.
    */
  def scripts: String =
    s"""<script src="https://cdn.jsdelivr.net/npm/vega@$vegaVersion"></script>
<script src="https://cdn.jsdelivr.net/npm/vega-lite@$vegaLiteVersion"></script>
<script src="https://cdn.jsdelivr.net/npm/vega-embed@$vegaEmbedVersion"></script>"""

  /** One figure, as a fragment for a page somebody else is building.
    *
    * `alt` is not decoration: a figure drawn by a script is invisible to a reader who cannot see
    * it, and until it draws it is invisible to everyone. The text is what stands in for it — it is
    * rendered in the container, so it is also what a reader sees if the script never runs. Vega
    * replaces the container's contents when it draws, which is what clears it; removing the text
    * here as well deletes the figure, and the browser smoke test is what found that.
    *
    * The identifier has to be unique within the page, and the caller owns it: this knows nothing
    * about what else is on the page.
    */
  def embed(id: String, spec: Json, alt: String): String =
    s"""<div class="bayes4s-figure" id="${escapeAttribute(
        id
      )}" role="img" aria-label="${escapeAttribute(
        alt
      )}">${escape(alt)}</div>
<script>
  vegaEmbed('#${escapeAttribute(id)}', ${spec.noSpaces}, {actions: false}).catch(function (error) {
    document.getElementById('${escapeAttribute(id)}').textContent = 'figure failed: ' + error;
  });
</script>"""

  /** One page, one figure per specification, each with its own container. */
  def page(specs: List[Json], title: String): String =
    report(specs.map(spec => Figure("", "", spec)), title, "")

  /** A page of figures with headings and captions: what a demonstration or a report wants.
    *
    * The same renderer as [[page]], so the two cannot drift apart in what they pin, what they
    * escape, or how they report a failure.
    */
  def report(figures: List[Figure], title: String, intro: String): String =
    val sections = figures.zipWithIndex
      .map { (figure, i) =>
        val heading =
          if figure.heading.isEmpty then "" else s"\n  <h2>${escape(figure.heading)}</h2>"
        val caption =
          if figure.caption.isEmpty then ""
          else s"""\n  <p class="caption">${escape(figure.caption)}</p>"""
        s"""<section>$heading
  <div class="figure" id="figure-$i"></div>$caption
</section>"""
      }
      .mkString("\n")
    val embeds = figures.zipWithIndex
      .map { (figure, i) =>
        s"""    vegaEmbed('#figure-$i', ${figure.spec.noSpaces}, {actions: true}).catch(report);"""
      }
      .mkString("\n")
    val opening = if intro.isEmpty then "" else s"\n<p class=\"intro\">${escape(intro)}</p>"
    s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>${escape(title)}</title>
<script src="https://cdn.jsdelivr.net/npm/vega@$vegaVersion"></script>
<script src="https://cdn.jsdelivr.net/npm/vega-lite@$vegaLiteVersion"></script>
<script src="https://cdn.jsdelivr.net/npm/vega-embed@$vegaEmbedVersion"></script>
<style>
  body { font-family: system-ui, sans-serif; margin: 2rem; max-width: 60rem; }
  .figure { margin: 1rem 0; }
  .caption { color: #333; max-width: 42rem; }
  .intro { color: #333; max-width: 42rem; }
  h2 { font-size: 1.1rem; margin-top: 2.5rem; }
  #errors { color: #D55E00; font-family: monospace; white-space: pre-wrap; }
</style>
</head>
<body>
<h1>${escape(title)}</h1>$opening
<div id="errors"></div>
$sections
<script>
  // Every failure is written into the page as well as to the console, so that a smoke test can
  // see it without reading the console and a reader can see it without opening one.
  function report(error) {
    document.getElementById('errors').textContent += error + '\\n';
  }
  window.addEventListener('error', function (e) { report(e.message); });
$embeds
</script>
</body>
</html>
"""

  private def escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  /** The same, plus the quote that would end an attribute. */
  private def escapeAttribute(text: String): String =
    escape(text).replace("\"", "&quot;")
