"""Check that a CSV this library renders is read by Stan's own tooling.

    python3 check_cmdstan_csv.py <directory of rendered files> [<directory of CmdStan files>]

The plan's criterion for phase 5 is "CSV read by `arviz.from_cmdstan`". ArviZ 1.x no longer has
that function — the CmdStan reader moved out to `cmdstanpy`, and `arviz_base.from_cmdstanpy`
converts its result — so the check is done through both, which is stricter than the original: the
file first has to satisfy CmdStan's own parser, and only then ArviZ's.

Needs `cmdstanpy` and `arviz-base`; no CmdStan installation and no compiler, since nothing is
sampled here. The findings this script produced are in docs/validation.md: a file with no trailing
timing block, or with `num_warmup = 0` while the adaptation is engaged, is refused outright.
"""
import glob
import sys

import numpy as np
from cmdstanpy import from_csv
import arviz_base as azb
import arviz as az

rendered = sorted(glob.glob(sys.argv[1] + "/*.csv"))
if not rendered:
    sys.exit(f"no CSV in {sys.argv[1]}")
print("rendered files:", [p.split("/")[-1] for p in rendered])

fit = from_csv(rendered, method="sample")
print("cmdstanpy read:", fit.chains, "chains,", fit.num_draws_sampling, "draws,",
      len(fit.column_names), "columns")

idata = azb.from_cmdstanpy(fit)
print("arviz groups:", list(idata.groups))
for name in idata.posterior.data_vars:
    print(f"  {name}: ess {float(az.ess(idata, var_names=[name])[name]):.1f}, "
          f"rhat {float(az.rhat(idata, var_names=[name])[name]):.6f}")

if len(sys.argv) > 2:
    originals = sorted(glob.glob(sys.argv[2] + "/bern*.csv"))
    reference = from_csv(originals, method="sample")
    for name in reference.stan_variables():
        ours = np.sort(np.ravel(fit.stan_variable(name)))
        theirs = np.sort(np.ravel(reference.stan_variable(name)))
        same = ours.shape == theirs.shape and np.array_equal(ours, theirs)
        print(f"  {name}: identical to the original to the last bit: {same}")
        if not same:
            sys.exit(f"{name} changed in the round trip")
print("ok")
