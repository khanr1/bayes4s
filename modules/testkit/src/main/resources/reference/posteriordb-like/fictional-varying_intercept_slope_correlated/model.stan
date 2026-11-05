data {
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
