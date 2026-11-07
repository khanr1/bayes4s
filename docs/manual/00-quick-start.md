# 0. Quick start

One page: add the dependency, write a model, fit it, look at the answer.

## The dependency

```scala
libraryDependencies += "io.github.bayes4s" %% "bayes4s-inference" % "<version>"
```

Nothing is published yet, so for now this chapter is read against a local build.

## A model

The smallest thing worth fitting: the mean of a Gaussian whose scale is also unknown.

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.*

val observed = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4, 4.7, 5.1)

final case class Params[A](mu: A, sigma: A)

val model: Model[Params] = new Model[Params]:
  def params: ParamSpec[Params] =
    (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
      .mapN[Params]([A] => (mu: A, sigma: A) => Params(mu, sigma))

  def logPrior[A: Real](p: Params[A]): A =
    LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
      LogDensity.halfNormal(p.sigma, 5.0.lift[A])

  def logLikelihood[A: Real](p: Params[A]): A =
    Real[A].normalSum(observed, Real[A].filled(observed.length)(p.mu), p.sigma)
```

Two blocks of parameters, a prior for each, and a likelihood. `sigma` is declared positive and the
model never mentions a logarithm: the transform and its log Jacobian are the library's business,
not yours.

### What you are looking at

That block has six pieces of this library's vocabulary in it, and you do not need to understand any
of them to run it. Here is what each one is, so that nothing above is a mystery:

- **`Params[A]`** — your parameters, as a case class with a type parameter. The `A` is the kind of
  number: plain `Double` when you want a value, and something else when the sampler wants a
  gradient. That is why it is `Params[A]` and not `Params`.
- **`Model[Params]`** — three methods and nothing else: `params` says what the parameters are
  called and what shape they have, `logPrior` is what you believed before the data, `logLikelihood`
  is what the data says. Separate, because the prior on its own is a model you can simulate from.
- **`ParamSpec.real` and `ParamSpec.positive`** — one parameter block each. `.mapN` glues them into
  your case class, in the order they are written.
- **`[A: Real]`** — "for any kind of number that can do arithmetic". You write the density once and
  the library reads it three ways: as a value, as a gradient, and as an exact check in its own
  tests.
- **`0.0.lift[A]`** — a plain `Double` turned into that kind of number. Constants need it;
  parameters already are one.
- **`runA(Seed(…)).value`** — the sampler is a *description* of a computation until you give it a
  seed. This is the line that runs it, and the same seed always gives the same chains.

[Chapter 1](01-concepts.md) takes all six slowly, and [appendix E](appendix-e-every-knob.md) lists
every constructor, constraint and setting there is. If the *statistics* rather than the code is what
feels assumed, [the ideas page](the-ideas.md) is the one to read first.

## The fit

```scala mdoc:silent
val fitted = Mcmc.runNuts(model, RunSettings()).runA(Seed(20261105L)).value
```

`RunSettings()` is Stan's defaults: four chains, a thousand warmup iterations, a thousand kept
samples, the no-U-turn sampler with a diagonal mass matrix. The result is an `Either`, because a
fit can fail for reasons worth reporting rather than hiding.

`runA(Seed(...)).value` is there because the whole sampler is a **function of its seed**: the same
seed gives the same chains, on any machine, in any order. Nothing is run until you ask.

## The answer

```scala mdoc
fitted.map(chains => Summary.table(model, chains))
```

Six columns are the answer and three are whether to believe it. `mean` and `sd` are the posterior's
location and spread; `5%`, `50%` and `95%` are the **[compatibility interval](glossary.md)** and the
median — this manual's word for what you may have met as a credible interval, and the  <!-- term-ok --><!-- naming the term the glossary replaces -->
[glossary](glossary.md) is the full list of such swaps. `Rhat` compares the chains with each other
and must be below about 1.01; `ESS_bulk` and `ESS_tail` are how many independent samples these
correlated ones are worth — the **effective number of samples** — in the middle of the distribution
and in its tails; `MCSE` is the error of the reported mean that comes from having sampled rather
than integrated. Chapter 4 is the whole story; this is enough to read a fit.

`lp__` is the log density itself, which is not a parameter but is the single most useful column for
spotting a chain that went somewhere else.

If you want the numbers rather than the table, `Model.constrain` turns a point the sampler moved in
into the parameters you wrote, and `Model.values` does the same by name:

```scala mdoc
fitted.map(chains => Model.values(model, chains.head.draws.head))
```

A picture of the same thing, which is the first look a fit deserves:

```scala mdoc:passthrough
println(Html.scripts)
println(
  fitted
    .map { chains =>
      val mu = IArray.from(
        chains.map(c => IArray.from(c.draws.map(q => Model.constrain(model, q).mu)))
      )
      Html.embed(
        "fig-00-trace",
        VegaLite.spec(Catalogue.tracePlot(mu, "mu")),
        "Four chains of mu over a thousand iterations each. They overlap completely and show no " +
          "trend, which is what a converged fit looks like."
      )
    }
    .getOrElse("<p>the fit failed, so there is no figure</p>")
)
```

Four chains drawn on top of each other with no trend and no chain sitting apart from the others:
that is convergence, and it is visible before any statistic is computed. What is worth checking
even here is that nothing went wrong:

```scala mdoc
fitted.map { chains =>
  val transitions = chains.flatMap(_.transitions)
  (
    transitions.count(_.divergent),
    transitions.map(_.treeDepth).max,
    chains.map(_.tuning.stepSize)
  )
}
```

No divergent transitions, a tree depth well under the maximum of ten, and four step sizes that
each chain found for itself. If the first number were not zero, chapter 4 is where to go.

That is the whole loop: write the model, fit it, read the table, look at the trace, check the
transitions. Everything else in this manual is a better model, a better check, or a better
picture.

## Where next

[Chapter 1](01-concepts.md) is why any of this is values rather than objects;
[chapter 2](02-writing-a-model.md) writes a real model, with a multilevel structure and two
parameterisations; [chapter 3](03-sampling.md) is about the sampler itself, including the four
chains in parallel in `IO`; [chapter 4](04-diagnosing.md) is the table above, column by column, and
what to do when one of them is bad; [chapter 12](12-plots.md) is every figure in this manual.
