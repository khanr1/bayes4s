"""Copy a selection of posteriordb reference posteriors (stan-dev/posteriordb; data and draws are
CC-BY 4.0 / models BSD-3, see the repository) and summarise their reference draws.

Usage: python -I build_posteriordb_fixtures.py <posteriordb checkout> <out dir>
"""
import json, shutil, sys, zipfile, pathlib
import numpy as np
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import stan_analyze_reference as S

DB = pathlib.Path(sys.argv[1]) / "posterior_database"; OUT = pathlib.Path(sys.argv[2])
SELECTION = {   # posterior -> why it is useful for this library
    "eight_schools-eight_schools_noncentered": "hierarchical, non-centered; positive scale (tau)",
    "arK-arK": "autoregression, like the AR(1) latent rate",
    "arma-arma11": "ARMA(1,1), persistence parameter in (-1, 1)",
    "garch-garch11": "bounded and positive parameters, recursive likelihood",
    "kidiq-kidscore_momiq": "plain linear regression, easy baseline",
    "earnings-logearn_height": "regression on a log scale",
    "gp_pois_regr-gp_pois_regr": "Poisson likelihood with a latent vector",
    "dogs-dogs": "Bernoulli/logistic likelihood with cumulative covariates",
    "low_dim_gauss_mix-low_dim_gauss_mix": "mixture with a unit-interval weight",
}
index = []
for name, why in SELECTION.items():
    post = json.loads((DB / "posteriors" / f"{name}.json").read_text())
    d = OUT / name; d.mkdir(parents=True, exist_ok=True)
    shutil.copy(DB / "models/stan" / f"{post['model_name']}.stan", d / "model.stan")
    with zipfile.ZipFile(DB / "data/data" / f"{post['data_name']}.json.zip") as z:
        (d / "data.json").write_bytes(z.read(z.namelist()[0]))
    info = json.loads((DB / "reference_posteriors/draws/info" / f"{name}.info.json").read_text())
    with zipfile.ZipFile(DB / "reference_posteriors/draws/draws" / f"{name}.json.zip") as z:
        chains = json.loads(z.read(z.namelist()[0]))        # list of chains, each {param: [draws]}
    params = list(chains[0].keys())
    summary = {}
    for p in params:
        x = np.array([c[p] for c in chains], float).T      # (draws, chains)
        flat = x.ravel()
        bulk, tail = S.split_rank_normalized_ess(x)
        sd = float(flat.std(ddof=1))
        summary[p] = {"mean": float(flat.mean()), "sd": sd,
                      "q05": float(np.quantile(flat, 0.05)), "q50": float(np.quantile(flat, 0.5)),
                      "q95": float(np.quantile(flat, 0.95)),
                      "ess_bulk": float(bulk), "mcse_mean": float(S.mcse_mean(x)), "mcse_sd": float(S.mcse_sd(x))}
    (d / "reference_summary.json").write_text(json.dumps({
        "posterior": name, "why": why, "model": post["model_name"], "data": post["data_name"],
        "reference_info": {k: info.get(k) for k in ("inference", "diagnostics", "checks_made", "comments") if k in info},
        "chains": len(chains), "draws_per_chain": len(chains[0][params[0]]), "parameters": summary}, indent=1))
    index.append({"posterior": name, "why": why, "n_parameters": len(params)})
    print(f"{name:45s} params={len(params):3d} chains={len(chains)} draws={len(chains[0][params[0]])}")
(OUT / "index.json").write_text(json.dumps({
    "provenance": "github.com/stan-dev/posteriordb, reference posteriors (10 chains x 1000 thinned draws "
                  "of long Stan runs). Summaries computed with tools/stan_analyze_reference.py.",
    "acceptance_rule": "for each parameter: |mean_scala - mean_ref| <= 4 * sqrt(mcse_scala^2 + mcse_ref^2), "
                       "and the same for sd with mcse_sd",
    "posteriors": index}, indent=1))
