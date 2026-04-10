//! Vulnerability scanning via the OSV (Open Source Vulnerabilities) API.
//! Uses ecosystem "crates.io" for Cargo dependencies.

use serde::{Deserialize, Serialize};

use crate::cargo_parser;
use crate::models::*;

const OSV_API: &str = "https://api.osv.dev/v1/query";

// --- OSV API request/response structures ---

#[derive(Serialize)]
struct OsvQuery {
    package: OsvPackage,
    version: String,
}

#[derive(Serialize)]
struct OsvPackage {
    name: String,
    ecosystem: String,
}

#[derive(Deserialize)]
struct OsvResponse {
    #[serde(default)]
    vulns: Vec<OsvVuln>,
}

#[derive(Deserialize)]
struct OsvVuln {
    id: String,
    #[serde(default)]
    summary: String,
    #[serde(default)]
    severity: Vec<OsvSeverity>,
    #[serde(default)]
    aliases: Vec<String>,
    #[serde(default)]
    references: Vec<OsvReference>,
}

#[derive(Deserialize)]
struct OsvSeverity {
    #[serde(rename = "type")]
    _type: String,
    score: String,
}

#[derive(Deserialize)]
struct OsvReference {
    #[serde(rename = "type")]
    _type: String,
    url: String,
}

// --- Public API ---

/// Run vulnerability audit against OSV for all dependencies in a Cargo.toml.
pub fn audit_manifest(manifest_path: &std::path::Path) -> AuditResponse {
    let deps = match cargo_parser::parse_cargo_toml(manifest_path) {
        Ok(d) => d,
        Err(e) => {
            return AuditResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                packages: vec![],
            };
        }
    };

    let mut packages = Vec::new();

    for dep in &deps {
        let version = cargo_parser::extract_pinned_version(&dep.specifier)
            .unwrap_or_else(|| dep.specifier.clone());

        if version.is_empty() {
            continue;
        }

        let vulns = match query_osv(&dep.name, &version) {
            Ok(v) => v,
            Err(_) => vec![],
        };

        if !vulns.is_empty() {
            packages.push(PackageAudit {
                package: dep.name.clone(),
                version,
                vulnerabilities: vulns,
            });
        }
    }

    AuditResponse {
        schema_version: SCHEMA_VERSION.into(),
        timestamp_utc: now_utc_iso(),
        status: "ok".into(),
        manifest_path: manifest_path.display().to_string(),
        packages,
    }
}

/// Query OSV API for vulnerabilities of a specific package+version.
fn query_osv(package: &str, version: &str) -> Result<Vec<Vulnerability>, String> {
    let query = OsvQuery {
        package: OsvPackage {
            name: package.to_string(),
            ecosystem: "crates.io".to_string(),
        },
        version: version.to_string(),
    };

    let body = serde_json::to_string(&query).map_err(|e| e.to_string())?;

    let resp: OsvResponse = ureq::post(OSV_API)
        .header("Content-Type", "application/json")
        .send(body.as_bytes())
        .map_err(|e| format!("OSV query failed for {package}@{version}: {e}"))?
        .into_body()
        .read_json()
        .map_err(|e| format!("Failed to parse OSV response for {package}@{version}: {e}"))?;

    let vulns = resp
        .vulns
        .into_iter()
        .map(|v| {
            let severity = v
                .severity
                .first()
                .map(|s| s.score.clone())
                .unwrap_or_default();

            Vulnerability {
                id: v.id,
                summary: v.summary,
                severity,
                aliases: v.aliases,
                references: v
                    .references
                    .into_iter()
                    .map(|r| VulnerabilityReference { url: r.url })
                    .collect(),
            }
        })
        .collect();

    Ok(vulns)
}
