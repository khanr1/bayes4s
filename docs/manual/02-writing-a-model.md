# 2. Writing a model

A model here is a value. It says what its parameters are, what you believed before the data, and
what the data says — and nothing else: no global registry, no side effect, no order of declaration
to get wrong. This chapter writes one, the eight schools of Rubin (1981), and then writes it a
second way to show what that buys.

## The data

Eight schools each ran a coaching programme and each reported an estimated effect and a standard
error for it.

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.{*, given}

val y = IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0)
val sigma = IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
val schools = y.length
```

## The parameters

A `ParamSpec` describes a block of parameters — its name, its size and what it is allowed to be —
and knows how to read that block out of the vector a sampler moves in. Specs combine with `mapN`:

```scala mdoc:silent
/** One level above the schools: a common effect, how much they vary, and each school's own. */
final case class Schools[A](mu: A, tau: A, theta: IArray[A])

val parameters: ParamSpec[Schools] =
  (
    ParamSpec.real("mu"),
    ParamSpec.positive("tau"),
    ParamSpec.vector("theta", schools)
  ).mapN[Schools](
    [A] => (mu: A, tau: A, theta: IArray[A]) => Schools(mu, tau, theta)
  )
```

The `[A] => ...` wrapper is not decoration. A spec reads its parameters for **any** scalar — plain
`Double` when you want a value, a tape entry when the sampler wants a gradient — so the function
that assembles them has to work for any scalar too. Scala cannot infer that from
`Schools.apply` alone, so it is written out once per model.

The spec knows its own shape, and it knows the names CmdStan would use:

```scala mdoc
parameters.unconstrainedSize
parameters.names
```

Ten unconstrained reals for ten parameters: `tau` is positive, which costs a transform but not a
dimension. Had there been a simplex or a correlation factor in there, the two numbers would differ.

## The prior and the likelihood

They are separate methods, and that separation is not tidiness: the prior alone is a model you can
sample from, which is what a prior predictive simulation and simulation-based calibration need.

```scala mdoc:silent
val centred: Model[Schools] = new Model[Schools]:
  def params: ParamSpec[Schools] = parameters

  def logPrior[A: Real](p: Schools[A]): A =
    LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
      LogDensity.halfCauchy(p.tau, 5.0.lift[A]) +
      LogDensity.normal(p.theta, p.mu, p.tau)

  def logLikelihood[A: Real](p: Schools[A]): A =
    Real[A].normalSum(y, p.theta, Real[A].vectorOf(schools)(j => sigma(j).lift[A]))
```

Both are written on the space the model is about: `tau` is a positive number here, not a logarithm.
Nothing in the model mentions a transform.

## What the sampler sees

`Model.target` is the density on the unconstrained space, with the log Jacobian of every transform
added. That addition happens here, once, and it is the reason a transform **returns** its log
Jacobian instead of adding it to something:

```scala mdoc
val at = IArray(0.5, 1.0, 0.2, -0.3, 0.1, 0.4, -0.2, 0.0, 0.6, -0.1)
Reverse.value(Model.target(centred), at)
```

and its gradient comes from the same text, with no derivative written by hand:

```scala mdoc
Reverse.valueAndGradient(Model.target(centred), at).gradient.toList.take(3)
```

A point and its named values convert both ways, which is what reading a fit back from a file
amounts to:

```scala mdoc
val named = Model.values(centred, at)
named("tau")
Model.unconstrain(centred, named).map(_.toList.take(2))
```

`unconstrain` returns an `Either` because it is genuinely partial: a negative `tau` has no
antecedent at all, and it says so rather than returning a number.

```scala mdoc
Model.unconstrain(centred, named.updated("tau", IArray(-1.0)))
```

## The same model, written the other way

The centred form above puts each school's effect directly in the parameter vector, so when `tau` is
small the eight `theta` are squeezed into a narrow band that the sampler has to crawl through. The
non-centred form parameterises the **standardised** effect instead and reconstructs the rest:

```scala mdoc:silent
final case class Standardised[A](mu: A, tau: A, z: IArray[A])

def effects[A: Real](p: Standardised[A]): IArray[A] =
  Real[A].vectorOf(p.z.length)(j => p.mu + p.tau * p.z(j))

val nonCentred: Model[Standardised] = new Model[Standardised]:
  def params: ParamSpec[Standardised] =
    (
      ParamSpec.real("mu"),
      ParamSpec.positive("tau"),
      ParamSpec.vector("z", schools)
    ).mapN[Standardised](
      [A] => (mu: A, tau: A, z: IArray[A]) => Standardised(mu, tau, z)
    )

  def logPrior[A: Real](p: Standardised[A]): A =
    LogDensity.normal(p.mu, 0.0.lift[A], 5.0.lift[A]) +
      LogDensity.halfCauchy(p.tau, 5.0.lift[A]) +
      LogDensity.normal(p.z, 0.0.lift[A], 1.0.lift[A])

  def logLikelihood[A: Real](p: Standardised[A]): A =
    Real[A].normalSum(y, effects(p), Real[A].vectorOf(schools)(j => sigma(j).lift[A]))

  /** `theta` is still what the model is about; it is just not a parameter any more. */
  override def derived(p: Standardised[Double]): Map[String, IArray[Double]] =
    Map("theta" -> effects[Double](p))
```

The two describe the same posterior in different coordinates, and that is checkable rather than
hoped for. The change of variables `theta = mu + tau z` has Jacobian `tau` per school, so the two
log densities differ by exactly `8 log(tau)`:

```scala mdoc
val mu = 4.0
val tau = 3.0
val z = IArray(0.4, -0.2, 0.1, 0.7, -0.5, 0.0, 0.3, -0.1)
val theta = IArray.tabulate(8)(j => mu + tau * z(j))

centred.logDensity(Schools(mu, tau, theta)) + 8.0 * math.log(tau)
nonCentred.logDensity(Standardised(mu, tau, z))
```

Which of the two to fit is a question about the sampler, not about the model, and
[chapter 4](04-diagnosing.md) answers it with diagnostics rather than taste. What matters here is
that
writing the second cost no new density and no new derivative.

## The prior on its own

```scala mdoc
val prior = Model.priorOnly(nonCentred)
prior.logLikelihood(Standardised(mu, tau, z))
```

Zero, exactly: the same parameters, the same prior, no data. That is the model a prior predictive
simulation fits, and the one simulation-based calibration samples from.

## Where the generative part comes in

A model can also carry a program that **generates** data — draw parameters from the prior, then
data from the parameters — which is what parameter recovery and simulation-based calibration are
built on. It is optional: a posterior needs only what is above. `Generative` and
`GenerativeModel` are in the API now and the chapter that uses them in earnest is
[chapter 5](05-checking-a-model.md), with the sampler.

One warning that belongs here. A generative model says the same thing twice — a density and a
simulation — and nothing in the types makes them the same prior. A disagreement would invalidate
every calibration built on it without failing anything, so the library's own suites assert the two
against each other, and yours should too.
