"""Reference posterior for a correlated varying-intercept, varying-slope model on fictional data.

posteriordb has no reference posterior with correlated group effects, so this script makes one with
an independent implementation (NumPyro, long NUTS run, 8 chains). The data are simulated here from
known parameters with a fixed seed; nothing comes from any real dataset.

Model (non-centered, Cholesky):
  y[i] ~ Normal(alpha[g[i]] + beta[g[i]] * x[i], sigma)
  (alpha[j], beta[j]) = mu + diag(tau) * L_Omega * z[j],   z[j] ~ Normal(0, I)
  mu ~ Normal(0, 5), tau ~ HalfNormal(1), L_Omega ~ LKJCholesky(2), sigma ~ HalfNormal(1)

Usage: python -I build_varying_effects_reference.py <out dir>
"""
import json, sys, pathlib, time
import numpy as np
import jax, jax.numpy as jnp
import numpyro, numpyro.distributions as dist
from numpyro.infer import MCMC, NUTS
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import stan_analyze_reference as S

numpyro.set_host_device_count(1)
OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)

# ---- fictional data ----
rng = np.random.default_rng(424242)
J, n_per = 12, rng.integers(3, 25, size=12)               # unbalanced groups, some very small
true = {"mu": [1.0, -0.5], "tau": [0.8, 0.4], "rho": 0.6, "sigma": 0.7}
Om = np.array([[1, true["rho"]], [true["rho"], 1]]); Sig = np.diag(true["tau"]) @ Om @ np.diag(true["tau"])
ab = rng.multivariate_normal(true["mu"], Sig, size=J)
g = np.repeat(np.arange(J), n_per); x = rng.normal(0, 1, size=len(g))
y = ab[g, 0] + ab[g, 1] * x + rng.normal(0, true["sigma"], size=len(g))

def model(g, x, y=None):
    mu = numpyro.sample("mu", dist.Normal(0, 5).expand([2]))
    tau = numpyro.sample("tau", dist.HalfNormal(1).expand([2]))
    L = numpyro.sample("L_Omega", dist.LKJCholesky(2, concentration=2.0))
    sigma = numpyro.sample("sigma", dist.HalfNormal(1))
    z = numpyro.sample("z", dist.Normal(0, 1).expand([J, 2]))
    eff = mu + (z @ L.T) * tau
    numpyro.deterministic("rho", L[1, 0])
    numpyro.sample("y", dist.Normal(eff[g, 0] + eff[g, 1] * x, sigma), obs=y)

t0 = time.time()
mcmc = MCMC(NUTS(model, target_accept_prob=0.95), num_warmup=2000, num_samples=4000, num_chains=8,
            chain_method="sequential", progress_bar=False)
mcmc.run(jax.random.PRNGKey(20261105), g=jnp.array(g), x=jnp.array(x), y=jnp.array(y))
s = mcmc.get_samples(group_by_chain=True)
div = int(np.asarray(mcmc.get_extra_fields(group_by_chain=True)["diverging"]).sum())
params = {"mu[1]": s["mu"][..., 0], "mu[2]": s["mu"][..., 1], "tau[1]": s["tau"][..., 0],
          "tau[2]": s["tau"][..., 1], "rho": s["rho"], "sigma": s["sigma"]}
summary = {}
for k, v in params.items():
    x2 = np.asarray(v).T                                   # (draws, chains)
    b, t = S.split_rank_normalized_rhat(x2); eb, et = S.split_rank_normalized_ess(x2)
    flat = x2.ravel()
    summary[k] = {"mean": float(flat.mean()), "sd": float(flat.std(ddof=1)),
                  "q05": float(np.quantile(flat, .05)), "q50": float(np.quantile(flat, .5)),
                  "q95": float(np.quantile(flat, .95)), "rhat": float(max(b, t)), "ess_bulk": float(eb),
                  "mcse_mean": float(S.mcse_mean(x2)), "mcse_sd": float(S.mcse_sd(x2))}
assert div == 0 and all(v["rhat"] < 1.005 and v["ess_bulk"] > 4000 for v in summary.values()), (div, summary)
(OUT / "data.json").write_text(json.dumps({"J": J, "N": int(len(g)), "group": (g + 1).tolist(),
                                           "x": x.tolist(), "y": y.tolist()}, indent=1))
(OUT / "model.stan").write_text("""data {
  int<lower=1> J; int<lower=1> N;
  array[N] int<lower=1, upper=J> group; vector[N] x; vector[N] y;
}
parameters {
  vector[2] mu; vector<lower=0>[2] tau; cholesky_factor_corr[2] L_Omega;
  real<lower=0> sigma; matrix[J, 2] z;
}
transformed parameters {
  matrix[J, 2] eff = rep_matrix(mu', J) + (z * L_Omega') * diag_matrix(tau);
}
model {
  mu ~ normal(0, 5); tau ~ normal(0, 1); L_Omega ~ lkj_corr_cholesky(2); sigma ~ normal(0, 1);
  to_vector(z) ~ std_normal();
  y ~ normal(eff[group, 1] + eff[group, 2] .* x, sigma);
}
generated quantities { real rho = L_Omega[2, 1]; }
""")
(OUT / "reference_summary.json").write_text(json.dumps({
    "posterior": "fictional-varying_intercept_slope_correlated",
    "why": "correlated varying effects: LKJ prior, Cholesky correlation transform, gather by group",
    "inference": {"tool": f"NumPyro {numpyro.__version__}, JAX {jax.__version__}", "chains": 8,
                  "warmup": 2000, "draws_per_chain": 4000, "target_accept": 0.95, "divergences": div},
    "true_parameters_used_to_simulate": true,
    "parameters": summary}, indent=1))
print("divergences", div, "seconds", round(time.time() - t0))
for k, v in summary.items():
    print(f"{k:7s} mean={v['mean']:.3f} sd={v['sd']:.3f} rhat={v['rhat']:.4f} ess={v['ess_bulk']:.0f}")
