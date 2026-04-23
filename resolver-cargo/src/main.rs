mod cargo_lock;
mod cargo_parser;
mod cargo_resolver;
mod crates_io;
mod models;
mod osv;
mod applier;
mod planner;
mod simulator;
mod verifier;

use std::path::PathBuf;

use clap::{Parser, Subcommand};

use models::*;

#[derive(Parser)]
#[command(name = "locklane-cargo", about = "LockLane Cargo.toml resolver backend")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Generate baseline dependency view
    Baseline {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long, default_value = "cargo")]
        resolver: String,
        #[arg(long)]
        json_out: Option<PathBuf>,
        #[arg(long)]
        exclude_newer: Option<String>,
    },
    /// Compose upgrade plan for all pinned dependencies
    Plan {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long, default_value = "cargo")]
        resolver: String,
        #[arg(long)]
        json_out: Option<PathBuf>,
        #[arg(long, default_value_t = 120)]
        timeout: u64,
        #[arg(long)]
        exclude_newer: Option<String>,
    },
    /// Simulate one candidate update
    Simulate {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long, default_value = "cargo")]
        resolver: String,
        #[arg(long)]
        json_out: Option<PathBuf>,
        #[arg(long)]
        package: String,
        #[arg(long)]
        target_version: String,
        #[arg(long, default_value_t = 120)]
        timeout: u64,
        #[arg(long)]
        exclude_newer: Option<String>,
    },
    /// Scan dependencies for known vulnerabilities via OSV
    Audit {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long, default_value = "cargo")]
        resolver: String,
        #[arg(long)]
        json_out: Option<PathBuf>,
    },
    /// Fetch changelog and project URLs from crates.io
    Enrich {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long, default_value = "cargo")]
        resolver: String,
        #[arg(long)]
        json_out: Option<PathBuf>,
    },
    /// Apply an upgrade plan to the manifest
    Apply {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long, default_value = "cargo")]
        resolver: String,
        #[arg(long)]
        json_out: Option<PathBuf>,
        #[arg(long)]
        plan_json: PathBuf,
        #[arg(long)]
        output: Option<PathBuf>,
        #[arg(long)]
        dry_run: bool,
    },
    /// Verify an upgrade plan
    VerifyPlan {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long, default_value = "cargo")]
        resolver: String,
        #[arg(long)]
        json_out: Option<PathBuf>,
        #[arg(long)]
        plan_json: PathBuf,
        #[arg(long)]
        command: Option<String>,
        #[arg(long, default_value_t = 120)]
        timeout: u64,
    },
}

fn write_json(payload: &impl serde::Serialize, json_out: Option<&PathBuf>) {
    let encoded = serde_json::to_string_pretty(payload).expect("JSON serialization failed");
    if let Some(path) = json_out {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(path, format!("{encoded}\n"));
    }
    println!("{encoded}");
}

fn cmd_baseline(manifest: &PathBuf, json_out: Option<&PathBuf>) {
    let manifest_path = manifest.canonicalize().unwrap_or_else(|_| manifest.clone());

    let deps = match cargo_parser::parse_cargo_toml(&manifest_path) {
        Ok(d) => d,
        Err(e) => {
            let resp = BaselineResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                resolver: "cargo".into(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                dependencies: vec![],
                tooling: cargo_resolver::tooling_availability(),
                resolution: None,
                cache_key: None,
                error: Some(e),
            };
            write_json(&resp, json_out);
            return;
        }
    };

    let resolution = match cargo_resolver::resolve(&manifest_path) {
        Ok(graph) => Some(graph),
        Err(e) => {
            let resp = BaselineResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                resolver: "cargo".into(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                dependencies: deps,
                tooling: cargo_resolver::tooling_availability(),
                resolution: None,
                cache_key: None,
                error: Some(e),
            };
            write_json(&resp, json_out);
            return;
        }
    };

    let resp = BaselineResponse {
        schema_version: SCHEMA_VERSION.into(),
        timestamp_utc: now_utc_iso(),
        resolver: "cargo".into(),
        status: "ok".into(),
        manifest_path: manifest_path.display().to_string(),
        dependencies: deps,
        tooling: cargo_resolver::tooling_availability(),
        resolution,
        cache_key: None,
        error: None,
    };
    write_json(&resp, json_out);
}

fn cmd_plan(manifest: &PathBuf, exclude_newer: Option<&str>, json_out: Option<&PathBuf>) {
    let manifest_path = manifest.canonicalize().unwrap_or_else(|_| manifest.clone());
    let resp = planner::compose_upgrade_plan(&manifest_path, exclude_newer);
    write_json(&resp, json_out);
}

fn cmd_simulate(
    manifest: &PathBuf,
    package: &str,
    target_version: &str,
    json_out: Option<&PathBuf>,
) {
    let manifest_path = manifest.canonicalize().unwrap_or_else(|_| manifest.clone());
    let sim = simulator::simulate_candidate(&manifest_path, package, target_version);

    let resp = SimulateResponse {
        schema_version: SCHEMA_VERSION.into(),
        timestamp_utc: now_utc_iso(),
        resolver: "cargo".into(),
        status: "ok".into(),
        manifest_path: manifest_path.display().to_string(),
        candidate: SimulateCandidate {
            package: package.into(),
            target_version: target_version.into(),
        },
        result: sim.classification.into(),
        explanation: sim.explanation,
        conflict_chain: sim.conflict_chain,
        raw_logs: Some(RawLogs {
            stdout: sim.stdout,
            stderr: sim.stderr,
        }),
    };
    write_json(&resp, json_out);
}

fn cmd_audit(manifest: &PathBuf, json_out: Option<&PathBuf>) {
    let manifest_path = manifest.canonicalize().unwrap_or_else(|_| manifest.clone());
    let resp = osv::audit_manifest(&manifest_path);
    write_json(&resp, json_out);
}

fn cmd_enrich(manifest: &PathBuf, json_out: Option<&PathBuf>) {
    let manifest_path = manifest.canonicalize().unwrap_or_else(|_| manifest.clone());

    let deps = match cargo_parser::parse_cargo_toml(&manifest_path) {
        Ok(d) => d,
        Err(e) => {
            let resp = EnrichResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                packages: std::collections::HashMap::new(),
            };
            write_json(&resp, json_out);
            return;
        }
    };

    let mut packages = std::collections::HashMap::new();
    for dep in &deps {
        let meta = crates_io::fetch_crate_metadata(&dep.name).ok();
        let dates = crates_io::fetch_versions_with_dates(&dep.name).ok();

        let current_ver = cargo_parser::extract_pinned_version(&dep.specifier)
            .unwrap_or_else(|| dep.specifier.clone());

        let (current_date, latest_ver, latest_date) = if let Some(ref versions) = dates {
            let cur_date = versions.iter()
                .find(|(v, _)| *v == current_ver)
                .and_then(|(_, d)| d.clone());
            // Find latest stable version
            let latest = versions.iter()
                .filter(|(v, _)| semver::Version::parse(v).map(|sv| sv.pre.is_empty()).unwrap_or(false))
                .max_by(|(a, _), (b, _)| {
                    semver::Version::parse(a).unwrap_or(semver::Version::new(0, 0, 0))
                        .cmp(&semver::Version::parse(b).unwrap_or(semver::Version::new(0, 0, 0)))
                });
            let (lv, ld) = latest.map(|(v, d)| (Some(v.clone()), d.clone())).unwrap_or((None, None));
            (cur_date, lv, ld)
        } else {
            (None, None, None)
        };

        packages.insert(
            dep.name.clone(),
            PackageLinks {
                changelog_url: meta.as_ref().and_then(|m| m.get("changelog_url").and_then(|v| v.clone())),
                home_page: meta.as_ref().and_then(|m| m.get("home_page").and_then(|v| v.clone())),
                current_version_date: current_date,
                latest_version: latest_ver,
                latest_version_date: latest_date,
            },
        );
    }

    let resp = EnrichResponse {
        schema_version: SCHEMA_VERSION.into(),
        timestamp_utc: now_utc_iso(),
        status: "ok".into(),
        manifest_path: manifest_path.display().to_string(),
        packages,
    };
    write_json(&resp, json_out);
}

fn cmd_apply(
    manifest: &PathBuf,
    plan_json: &PathBuf,
    output: Option<&PathBuf>,
    dry_run: bool,
    json_out: Option<&PathBuf>,
) {
    let manifest_path = manifest.canonicalize().unwrap_or_else(|_| manifest.clone());

    let plan_data: serde_json::Value = match std::fs::read_to_string(plan_json)
        .map_err(|e| e.to_string())
        .and_then(|s| serde_json::from_str(&s).map_err(|e| e.to_string()))
    {
        Ok(d) => d,
        Err(e) => {
            let resp = ApplyResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                plan_path: plan_json.display().to_string(),
                dry_run,
                apply: None,
            };
            write_json(&resp, json_out);
            return;
        }
    };

    match applier::apply_plan(&manifest_path, &plan_data, output.map(|p| p.as_path()), dry_run) {
        Ok(data) => {
            let resp = ApplyResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "ok".into(),
                manifest_path: manifest_path.display().to_string(),
                plan_path: plan_json.display().to_string(),
                dry_run,
                apply: Some(data),
            };
            write_json(&resp, json_out);
        }
        Err(e) => {
            let resp = ApplyResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                plan_path: plan_json.display().to_string(),
                dry_run,
                apply: None,
            };
            write_json(&resp, json_out);
        }
    }
}

fn cmd_verify_plan(
    manifest: &PathBuf,
    plan_json: &PathBuf,
    command: Option<&str>,
    json_out: Option<&PathBuf>,
) {
    let manifest_path = manifest.canonicalize().unwrap_or_else(|_| manifest.clone());

    let plan_data: serde_json::Value = match std::fs::read_to_string(plan_json)
        .map_err(|e| e.to_string())
        .and_then(|s| serde_json::from_str(&s).map_err(|e| e.to_string()))
    {
        Ok(d) => d,
        Err(e) => {
            let resp = VerifyResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                plan_path: plan_json.display().to_string(),
                resolver: "cargo".into(),
                verification: None,
            };
            write_json(&resp, json_out);
            return;
        }
    };

    let mut resp = verifier::verify_plan(&manifest_path, &plan_data, command);
    resp.plan_path = plan_json.display().to_string();
    write_json(&resp, json_out);
}

#[allow(dead_code)]
fn cmd_stub(command: &str, manifest: &PathBuf, json_out: Option<&PathBuf>) {
    let resp = serde_json::json!({
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "resolver": "cargo",
        "status": "error",
        "manifest_path": manifest.display().to_string(),
        "error": format!("Command '{command}' is not yet implemented for Cargo"),
    });
    write_json(&resp, json_out);
}

fn main() {
    let cli = Cli::parse();

    match &cli.command {
        Commands::Baseline {
            manifest, json_out, ..
        } => cmd_baseline(manifest, json_out.as_ref()),
        Commands::Plan {
            manifest,
            json_out,
            exclude_newer,
            ..
        } => cmd_plan(manifest, exclude_newer.as_deref(), json_out.as_ref()),
        Commands::Simulate {
            manifest,
            json_out,
            package,
            target_version,
            ..
        } => cmd_simulate(manifest, package, target_version, json_out.as_ref()),
        Commands::Audit {
            manifest, json_out, ..
        } => cmd_audit(manifest, json_out.as_ref()),
        Commands::Enrich {
            manifest, json_out, ..
        } => cmd_enrich(manifest, json_out.as_ref()),
        Commands::Apply {
            manifest,
            json_out,
            plan_json,
            output,
            dry_run,
            ..
        } => cmd_apply(manifest, plan_json, output.as_ref(), *dry_run, json_out.as_ref()),
        Commands::VerifyPlan {
            manifest,
            json_out,
            plan_json,
            command,
            ..
        } => cmd_verify_plan(manifest, plan_json, command.as_deref(), json_out.as_ref()),
    }
}
