# 12. Plots

A figure in this library is a **value**. `Plot` is a small algebra of layers, `VegaLite.spec`
compiles one to a Vega-Lite specification, and only the `effect` module writes anything to disk.
Nothing here renders, measures text, or opens a window, which is what makes a figure testable: the
statistics behind it are compared against ArviZ, and the specification itself is validated against
Vega-Lite's own schema. No image is ever compared.

```scala mdoc:silent
import bayes4s.*
import cats.syntax.all.*

/** Two chains of fake samples from the posterior, enough to draw something. */
val chains: IArray[IArray[Double]] =
  IArray.tabulate(2)(c => IArray.tabulate(400)(i => 0.4 * math.sin(i * 0.07 + c) + 0.03 * (i % 7)))
```

## The catalogue

The catalogue is finite on purpose. It is not a grammar of graphics — it is the list of pictures a
Bayesian workflow actually needs, each built from the types the rest of the library already
produces.

```scala mdoc
val trace = Catalogue.tracePlot(chains, "mu")
trace.layers.length
trace.title
```

| Figure | What it answers |
|---|---|
| `tracePlot` | do the chains overlap, and where were the divergent transitions? |
| `trankPlot` | do the chains agree about the middle of the posterior? |
| `autocorrelationPlot` | how far apart are two samples before they stop repeating each other? |
| `energyPlot` | is the sampler exploring the tails? |
| `pairsPlot` | is there a funnel, and are the divergent transitions in its neck? |
| `densityPlot` | what does one parameter's posterior look like, chain by chain? |
| `intervalPlot` | the summary table, as a picture |
| `compareModelsPlot` | which model predicts better, and by how much against its own error? |
| `paretoKPlot` | which observations the cross-validation cannot be trusted on |
| `predictivePlot` | does data simulated from the fit look like the data? |
| `pitPlot` | is the predictive distribution calibrated? |
| `sbcPlot` | is the whole generative model, sampler included, calibrated? |
| `sensitivityPlot` | is the posterior led by the prior or by the data? |
| `fanChart` | the forecast, with compatibility bands and what actually happened |
| `shrinkagePlot` | what partial pooling did to each cluster |

## Layers add up

`Plot` is a monoid: adding two figures concatenates their layers and keeps the first one's titles
and size. That is all the composition most figures need, and it means a figure can be assembled
from pieces rather than configured through options.

```scala mdoc
val withZero = trace |+| Plot(List(Layer.Rule(0.0, Axis.Y)))
withZero.layers.length
(Plot.empty |+| trace) == trace
```

## The statistics are the part worth testing

Three statistics stand behind the catalogue, and each is compared against ArviZ rather than
against anything this library believes about itself.

```scala mdoc
val pooled = IArray.from(chains.toList.flatMap(_.toList))
Density.bandwidth(pooled, Bandwidth.Silverman)
Density.bandwidth(pooled, Bandwidth.Scott)
```

`Bandwidth.Isj` is Botev's diffusion estimator, which is what ArviZ uses by default and what you
want for a posterior with two modes. `Density.gaussian` can be reflected at a boundary, so the
mass of a positive parameter does not leak below zero:

```scala mdoc
val grid = Density.grid(pooled, 8, Density.bandwidth(pooled, Bandwidth.Silverman))
Density.gaussian(pooled, 0.1, grid, reflectAt = Some(grid(0))).length
```

The band in `sbcPlot`, `pitPlot` and the rank figures is the **simultaneous** one of Säilynoja,
Bürkner and Vehtari: a pointwise interval is crossed by about one point in twenty even when
everything is right, and a reader cannot help reading a crossing as a failure.

```scala mdoc
val points = IArray.tabulate(4)(i => (i + 1.0) / 5.0)
val band = Ecdf.simultaneousBand(200, points, points, 0.95, Seed(1L), 500)
IArray.tabulate(4)(i => (band.lower(i) * 100).round / 100.0).toList
```

## When the catalogue is nearly right

Rather than grow options until the catalogue becomes a second grammar, the specification is handed
over as JSON. Vega-Lite's own documentation then applies, and nothing here has to pretend to cover
it.

```scala mdoc
import io.circe.Json

val edited = VegaLite.raw(trace)(_.deepMerge(Json.obj("background" -> Json.fromString("#fafafa"))))
edited.hcursor.downField("background").as[String]
```

Several figures side by side are one specification too:

```scala mdoc
VegaLite.concat(List(trace, Catalogue.densityPlot(chains, "mu")), columns = 2)
  .hcursor
  .downField("concat")
  .values
  .map(_.size)
```

A figure can also be split into panels by a field of its own data, with `Plot.facet`. The data of
a faceted specification is hoisted to the top level — Vega-Lite's facet operator reads it there —
and each layer filters itself back out, which `VegaLite.spec` does for you.

## Writing them down

Nothing above touched the file system. Writing is `PlotFile`, in the `effect` module:

```scala mdoc:silent
import cats.effect.IO
import fs2.io.file.Path

/** A page of figures, and one specification on its own. */
def publish(directory: Path): IO[Unit] =
  val figures = List(
    Figure("A trace", "Two chains over 400 iterations.", VegaLite.spec(trace)),
    Figure("A density", "The same samples, pooled.", VegaLite.spec(Catalogue.densityPlot(chains, "mu")))
  )
  PlotFile[IO].writeReport(directory / "report.html", figures, "A short report", "") *>
    PlotFile[IO].writeJson(directory / "trace.vl.json", trace)
```

`Html.page` and `Html.report` pin the versions of vega, vega-lite and vega-embed they load. A page
that asked for the latest would render differently next year than it does today, and a figure that
changes silently is worse than one that breaks.

## An image, when you need one

A specification is the portable thing; an image is a rendering of it. The conversion is a separate
tool, deliberately outside this library:

```sh
# one PNG, from a specification this library wrote
vl-convert vl2png --input trace.vl.json --output trace.png --scale 2
```

## The demonstration page

Every figure of the catalogue, drawn on the invented world of `examples.FictionalWorld` — a
lending library in four invented cities whose membership applications arrive at a drifting rate:

```sh
sbt 'examples/runMain bayes4s.examples.CatalogueDemo target/catalogue.html'
```

The page is built by a pure function of its settings, so the suite validates every figure of the
real page against the committed Vega-Lite schema. CI then builds the same page under reduced
settings and opens it in Chromium (`scripts/smoke-plots.mjs`): a specification can be valid and
still throw when Vega runs it, and only a renderer can say. What the smoke test asserts is that
every figure drew something and that nothing was written to the console, thrown, or reported into
the page's own error box — never that the pixels match.
