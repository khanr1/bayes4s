# bayes4s-rethinking

An optional façade that gives this library's functions the names used by the R package
`rethinking`, which accompanies Richard McElreath's *Statistical Rethinking*. A reader working
through the book can call `quap`, `precis`, `PI`, `HPDI`, `WAIC` and `compare` and get the same
quantities, computed by the rest of this library.

It contains **no statistics of its own.** Every function here calls a module below it and returns
the core's own types — `Draws`, `Summary`, `LooResult` — rather than parallel ones. What it adds is
the vocabulary and the book's defaults, chief among them the 89 % interval, which applies in this
module only: the core always takes the mass explicitly.

## On the R package

This module is **not connected with the package or its author**, and is not endorsed by either.

The R package is distributed under the GPL. Reproducing the **names** of its functions and the
**parameterisations its documentation states** does not copy code. Copying or translating its
source would make this module a derived work under the GPL, which is incompatible with the licence
this library is published under.

The rule followed here, without exception:

- implemented from the package's published documentation and from the papers it cites, never from
  its source code, which has not been read;
- none of its text, none of its examples, and none of its datasets is reproduced — the reference
  values this module is tested against were computed independently with NumPy and SciPy, and the
  examples use the invented world this repository generates itself.

`scripts/check-rethinking-origin.py` enforces the mechanical floor under that rule in CI: no R
syntax and no R files in the module, none of the package's dataset names anywhere in the
repository, and a stated provenance in the façade's own documentation. A script cannot verify that
the rule was followed; the reviewer does that, and `docs/validation.md` records it.

This is not legal advice. Before any public release, confirm it with a lawyer.

## What is here and what is not

Implemented: `quap`, `ulam`, `precis`, `extractSamples`, `extractPrior`, `link`, `sim`, `ensemble`,
`PI`, `HPDI`, `WAIC`, `PSIS`, `compare`, `coeftab`, `standardize`, `invLogit`, `logit`,
`logSumExp`, and the densities `dgampois`, `dbetabinom`, `dordlogit`, `dzipois`, `dstudent`,
`dmvnorm2`, `dlkjcorr`. The figures `dens`, `shade`, `postcheck`, `traceplot`, `trankplot` and the
`precis` plot are in `Figures`, built on the `plot` module.

Deliberately absent: the `alist`/`flist` formula language — models are written with this library's
own `Model` API, which is type-checked — and `drawdag`, since a causal diagram is not a statistical
figure and this library draws none.
