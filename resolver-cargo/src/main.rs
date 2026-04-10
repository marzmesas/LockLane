mod cargo_parser;
mod cargo_resolver;
mod crates_io;
mod models;
mod osv;

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
        match crates_io::fetch_crate_metadata(&dep.name) {
            Ok(meta) => {
                packages.insert(
                    dep.name.clone(),
                    PackageLinks {
                        changelog_url: meta.get("changelog_url").and_then(|v| v.clone()),
                        home_page: meta.get("home_page").and_then(|v| v.clone()),
                    },
                );
            }
            Err(_) => {
                packages.insert(
                    dep.name.clone(),
                    PackageLinks {
                        changelog_url: None,
                        home_page: None,
                    },
                );
            }
        }
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
            manifest, json_out, ..
        } => cmd_stub("plan", manifest, json_out.as_ref()),
        Commands::Simulate {
            manifest, json_out, ..
        } => cmd_stub("simulate", manifest, json_out.as_ref()),
        Commands::Audit {
            manifest, json_out, ..
        } => cmd_audit(manifest, json_out.as_ref()),
        Commands::Enrich {
            manifest, json_out, ..
        } => cmd_enrich(manifest, json_out.as_ref()),
        Commands::Apply {
            manifest, json_out, ..
        } => cmd_stub("apply", manifest, json_out.as_ref()),
        Commands::VerifyPlan {
            manifest, json_out, ..
        } => cmd_stub("verify-plan", manifest, json_out.as_ref()),
    }
}
