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
val summary = fitted.map { chains =>
  val all = chains.flatMap(_.draws).map(q => Model.constrain(model, q))
  val mus = all.map(_.mu)
  val sigmas = all.map(_.sigma)
  def moments(xs: List[Double]) =
    val mean = xs.sum / xs.length
    val sd = math.sqrt(xs.map(x => (x - mean) * (x - mean)).sum / (xs.length - 1))
    (mean, sd)
  (moments(mus), moments(sigmas))
}
```

`Model.constrain` turns a point the sampler moved in into the parameters you wrote, and
`Model.values` does the same by name when you want a table:

```scala mdoc
fitted.map(chains => Model.values(model, chains.head.draws.head))
```

A real summary — the effective number of samples, Rhat, the compatibility intervals — arrives with
the diagnostics in chapter 4. What is worth checking even here is that nothing went wrong:

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

## Where next

[Chapter 1](01-concepts.md) is why any of this is values rather than objects;
[chapter 2](02-writing-a-model.md) writes a real model, with a multilevel structure and two
parameterisations; [chapter 3](03-sampling.md) is about the sampler itself.
