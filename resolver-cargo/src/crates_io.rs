//! crates.io API client: version enumeration and upload date filtering.

use std::collections::HashMap;

use chrono::{DateTime, Duration, Utc};
use semver::Version;
use serde::Deserialize;

const USER_AGENT: &str = "locklane-cargo/0.1.0 (https://github.com/marzmesas/LockLane)";

#[derive(Debug)]
pub struct CratesIoError(pub String);

impl std::fmt::Display for CratesIoError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

// --- crates.io API response structures ---

#[derive(Deserialize)]
struct CrateResponse {
    versions: Vec<CrateVersion>,
}

#[derive(Deserialize)]
struct CrateVersion {
    num: String,
    #[serde(default)]
    yanked: bool,
    created_at: Option<String>,
}

// --- Public API ---

/// Fetch all non-yanked version strings for a crate.
pub fn fetch_versions(crate_name: &str) -> Result<Vec<String>, CratesIoError> {
    let url = format!("https://crates.io/api/v1/crates/{crate_name}/versions");
    let resp: CrateResponse = ureq::get(&url)
        .header("User-Agent", USER_AGENT)
        .call()
        .map_err(|e| CratesIoError(format!("Failed to fetch {crate_name}: {e}")))?
        .into_body()
        .read_json()
        .map_err(|e| CratesIoError(format!("Failed to parse response for {crate_name}: {e}")))?;

    Ok(resp
        .versions
        .into_iter()
        .filter(|v| !v.yanked)
        .map(|v| v.num)
        .collect())
}

/// Fetch versions with their upload timestamps.
/// Returns (version_string, upload_time_iso_or_None).
pub fn fetch_versions_with_dates(
    crate_name: &str,
) -> Result<Vec<(String, Option<String>)>, CratesIoError> {
    let url = format!("https://crates.io/api/v1/crates/{crate_name}/versions");
    let resp: CrateResponse = ureq::get(&url)
        .header("User-Agent", USER_AGENT)
        .call()
        .map_err(|e| CratesIoError(format!("Failed to fetch {crate_name}: {e}")))?
        .into_body()
        .read_json()
        .map_err(|e| CratesIoError(format!("Failed to parse response for {crate_name}: {e}")))?;

    Ok(resp
        .versions
        .into_iter()
        .filter(|v| !v.yanked)
        .map(|v| (v.num, v.created_at))
        .collect())
}

/// Candidate lists grouped by bump level.
#[derive(Debug, Default)]
pub struct UpgradeCandidates {
    pub patch: Vec<String>,
    pub minor: Vec<String>,
    pub major: Vec<String>,
}

/// Enumerate upgrade candidates grouped by bump level (patch, minor, major).
///
/// If `exclude_newer` is set, versions uploaded after the cutoff are filtered out.
/// Only stable semver versions are included (no pre-releases).
pub fn enumerate_upgrade_candidates(
    crate_name: &str,
    current_version: &str,
    exclude_newer: Option<&str>,
) -> Result<UpgradeCandidates, CratesIoError> {
    let current = match Version::parse(current_version) {
        Ok(v) => v,
        Err(_) => return Ok(UpgradeCandidates::default()),
    };

    let cutoff = exclude_newer.and_then(|s| parse_exclude_newer(s).ok());

    // Fetch with dates if we need filtering, plain versions otherwise
    let versions: Vec<String> = if cutoff.is_some() {
        let with_dates = fetch_versions_with_dates(crate_name)?;
        let cutoff = cutoff.unwrap();
        with_dates
            .into_iter()
            .filter(|(_, created)| {
                if let Some(ts) = created {
                    match DateTime::parse_from_rfc3339(ts) {
                        Ok(dt) => dt.with_timezone(&Utc) <= cutoff,
                        Err(_) => true, // can't parse → keep
                    }
                } else {
                    true // no date → keep
                }
            })
            .map(|(v, _)| v)
            .collect()
    } else {
        fetch_versions(crate_name)?
    };

    let mut patch: Vec<(Version, String)> = Vec::new();
    let mut minor: Vec<(Version, String)> = Vec::new();
    let mut major: Vec<(Version, String)> = Vec::new();

    for v_str in &versions {
        let v = match Version::parse(v_str) {
            Ok(v) if v.pre.is_empty() => v, // skip pre-releases
            _ => continue,
        };

        if v <= current {
            continue;
        }

        if v.major == current.major && v.minor == current.minor && v.patch > current.patch {
            patch.push((v, v_str.clone()));
        } else if v.major == current.major && v.minor > current.minor {
            minor.push((v, v_str.clone()));
        } else if v.major > current.major {
            major.push((v, v_str.clone()));
        }
    }

    patch.sort_by(|a, b| a.0.cmp(&b.0));
    minor.sort_by(|a, b| a.0.cmp(&b.0));
    major.sort_by(|a, b| a.0.cmp(&b.0));

    Ok(UpgradeCandidates {
        patch: patch.into_iter().map(|(_, s)| s).collect(),
        minor: minor.into_iter().map(|(_, s)| s).collect(),
        major: major.into_iter().map(|(_, s)| s).collect(),
    })
}

/// Parse an exclude-newer value into a UTC datetime.
///
/// Supports ISO 8601 timestamps and simple duration strings.
pub fn parse_exclude_newer(value: &str) -> Result<DateTime<Utc>, String> {
    // Try RFC 3339 / ISO 8601
    if let Ok(dt) = DateTime::parse_from_rfc3339(value) {
        return Ok(dt.with_timezone(&Utc));
    }
    // Try date-only: 2026-01-15
    if let Ok(dt) = chrono::NaiveDate::parse_from_str(value, "%Y-%m-%d") {
        let dt = dt
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_utc();
        return Ok(dt);
    }

    // Try duration: "N days", "N weeks", "N hours"
    let parts: Vec<&str> = value.trim().splitn(2, ' ').collect();
    if parts.len() == 2 {
        if let Ok(amount) = parts[0].parse::<i64>() {
            let unit = parts[1].trim_end_matches('s').to_lowercase();
            let delta = match unit.as_str() {
                "day" => Some(Duration::days(amount)),
                "week" => Some(Duration::weeks(amount)),
                "hour" => Some(Duration::hours(amount)),
                _ => None,
            };
            if let Some(d) = delta {
                return Ok(Utc::now() - d);
            }
        }
    }

    Err(format!("Cannot parse exclude-newer value: {value:?}"))
}

/// Build a metadata map for enrichment (repository, homepage, changelog).
pub fn fetch_crate_metadata(
    crate_name: &str,
) -> Result<HashMap<String, Option<String>>, CratesIoError> {
    #[derive(Deserialize)]
    struct CrateInfoResponse {
        #[serde(rename = "crate")]
        krate: CrateInfo,
    }
    #[derive(Deserialize)]
    struct CrateInfo {
        repository: Option<String>,
        homepage: Option<String>,
    }

    let url = format!("https://crates.io/api/v1/crates/{crate_name}");
    let resp: CrateInfoResponse = ureq::get(&url)
        .header("User-Agent", USER_AGENT)
        .call()
        .map_err(|e| CratesIoError(format!("Failed to fetch {crate_name}: {e}")))?
        .into_body()
        .read_json()
        .map_err(|e| CratesIoError(format!("Failed to parse {crate_name}: {e}")))?;

    let mut result = HashMap::new();
    result.insert("home_page".into(), resp.krate.homepage.or(resp.krate.repository.clone()));

    // Heuristic: changelog is often at {repo}/blob/main/CHANGELOG.md
    let changelog = resp.krate.repository.as_ref().and_then(|repo| {
        if repo.contains("github.com") {
            Some(format!("{repo}/blob/main/CHANGELOG.md"))
        } else {
            None
        }
    });
    result.insert("changelog_url".into(), changelog);

    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_exclude_newer_date() {
        let dt = parse_exclude_newer("2026-01-15").unwrap();
        assert_eq!(dt.format("%Y-%m-%d").to_string(), "2026-01-15");
    }

    #[test]
    fn test_parse_exclude_newer_duration() {
        let dt = parse_exclude_newer("7 days").unwrap();
        let expected = Utc::now() - Duration::days(7);
        assert!((dt - expected).num_seconds().abs() < 2);
    }

    #[test]
    fn test_parse_exclude_newer_invalid() {
        assert!(parse_exclude_newer("foobar").is_err());
    }
}
