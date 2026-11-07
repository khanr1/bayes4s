# Appendix B. Errors and contracts

Nothing in the core throws. A predictable failure is a `BayesError` inside an `Either`, and the
`effect` module raises those in `F` when a boundary demands it. They carry no stack trace, so
creating one costs nothing and a sampler can afford to reject a proposal by returning one.

## The contract, in one table

| What happened | What you get |
|---|---|
| A parameter is outside its domain: `sigma <= 0`, a probability above one, a `NaN`, an infinity | `Left(BayesError.InvalidArgument)` from the smart constructor, naming the parameter |
| An observation is outside the support: a negative count, a proportion above one | `-Inf` from `logPdf` or `logMass` |
| An argument is `NaN` | `NaN`, propagated as IEEE arithmetic does |
| A numeric function is asked for a value it cannot have: a quantile at p = 2, a quantile of nothing | `NaN` |
| A search does not converge, or cannot evaluate its function | `Left`, never a plausible wrong number |

## The errors themselves

```scala mdoc
import bayes4s.*

Gamma.of(-1.0, 2.0)
```

**`InvalidArgument(where, name, value, rule)`** — the one you will meet. It names the place, the
parameter, the value and the rule, so the message can be shown to whoever supplied the number.

```scala mdoc
Gamma.of(-1.0, 2.0).fold(_.message, _.toString)
```

**`DimensionMismatch(where, expected, obtained)`** — two things that had to be the same length were
not.

```scala mdoc
Fft.forward(IArray(1.0, 2.0), IArray(0.0)).fold(_.message, _ => "transformed")
```

**`NumericalOverflow(where)`** — an iteration gave up. The searches use it when a budget runs out,
which is a failure to answer rather than a wrong answer.

```scala mdoc
Optimize.brentRoot(x => x * x - 2.0, 0.0, 2.0, tolerance = 1e-16, maxIterations = 1)
  .fold(_.message, root => s"found $root")
```

**`UnknownParameter(name)`** — a draw or a model was asked for a parameter it does not have.
`Model.unconstrain` returns it when the values you handed it are missing one of the model's blocks.

**`DuplicateParameter(name)`** — two blocks of a `ParamSpec` claim the same name. It is checked by
`ParamSpec.validated`, and `Model.unconstrain` checks it before reading anything: a duplicate name
would make a parameter ambiguous both there and in a CmdStan file, and nothing downstream could
tell.

**`Malformed(source, detail)`** — an external input could not be read: a CSV row, a JSON document.

## What to do with one

Not `.get`. The point of returning a value is that the caller decides, and `for` comprehensions
make the happy path read as if nothing could go wrong:

```scala mdoc
val model = for
  rate <- Gamma.of(2.0, 0.5)
  counts <- GammaPoisson.update(rate, IArray(3, 5, 2), 3.0)
  predictive <- GammaPoisson.predictive(counts)
yield predictive.mean

model.fold(error => s"could not build the model: ${error.message}", mean => s"mean $mean")
```

Inside `effect`, the same errors are raised in `F` through `MonadThrow`, so a stream of samples
fails
the way the rest of your program fails. Nothing is caught and ignored.
