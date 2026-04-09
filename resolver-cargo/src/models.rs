//! Typed data models for resolver JSON responses.
//! Field names match the Python backend / Kotlin plugin exactly.

use chrono::Utc;
use serde::Serialize;

pub const SCHEMA_VERSION: &str = "0.6.0";

pub fn now_utc_iso() -> String {
    Utc::now().to_rfc3339()
}

#[derive(Debug, Clone, Serialize)]
pub struct ToolAvailability {
    pub available: bool,
    pub binary: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ParsedDependency {
    pub name: String,
    pub specifier: String,
    pub raw_line: String,
    pub line_number: usize,
}

#[derive(Debug, Clone, Serialize)]
pub struct ResolvedPackage {
    pub name: String,
    pub version: String,
    pub is_direct: bool,
    pub required_by: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct DependencyGraph {
    pub packages: Vec<ResolvedPackage>,
    pub resolver_tool: String,
    pub resolver_version: String,
    pub python_version: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct CacheKey {
    pub interpreter_path: String,
    pub python_version: String,
    pub manifest_sha256: String,
}

// --- Baseline ---

#[derive(Debug, Clone, Serialize)]
pub struct BaselineResponse {
    pub schema_version: String,
    pub timestamp_utc: String,
    pub resolver: String,
    pub status: String,
    pub manifest_path: String,
    pub dependencies: Vec<ParsedDependency>,
    pub tooling: std::collections::HashMap<String, ToolAvailability>,
    pub resolution: Option<DependencyGraph>,
    pub cache_key: Option<CacheKey>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

// --- Plan ---

#[derive(Debug, Clone, Serialize)]
pub struct SafeUpdate {
    pub package: String,
    pub from_version: String,
    pub to_version: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ConflictLink {
    pub package: String,
    pub constraint: String,
    pub required_by: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ConflictChain {
    pub summary: String,
    pub links: Vec<ConflictLink>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BlockedUpdate {
    pub package: String,
    pub target_version: String,
    pub reason: String,
    pub conflict_chain: Option<ConflictChain>,
}

#[derive(Debug, Clone, Serialize)]
pub struct InconclusiveUpdate {
    pub package: String,
    pub target_version: String,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct OrderedStep {
    pub step: usize,
    pub description: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct PlanResponse {
    pub schema_version: String,
    pub timestamp_utc: String,
    pub status: String,
    pub manifest_path: String,
    pub resolver: String,
    pub safe_updates: Vec<SafeUpdate>,
    pub blocked_updates: Vec<BlockedUpdate>,
    pub inconclusive_updates: Vec<InconclusiveUpdate>,
    pub ordered_steps: Vec<OrderedStep>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

// --- Audit ---

#[derive(Debug, Clone, Serialize)]
pub struct VulnerabilityReference {
    pub url: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Vulnerability {
    pub id: String,
    pub summary: String,
    pub severity: String,
    pub aliases: Vec<String>,
    pub references: Vec<VulnerabilityReference>,
}

#[derive(Debug, Clone, Serialize)]
pub struct PackageAudit {
    pub package: String,
    pub version: String,
    pub vulnerabilities: Vec<Vulnerability>,
}

#[derive(Debug, Clone, Serialize)]
pub struct AuditResponse {
    pub schema_version: String,
    pub timestamp_utc: String,
    pub status: String,
    pub manifest_path: String,
    pub packages: Vec<PackageAudit>,
}

// --- Enrich ---

#[derive(Debug, Clone, Serialize)]
pub struct PackageLinks {
    pub changelog_url: Option<String>,
    pub home_page: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct EnrichResponse {
    pub schema_version: String,
    pub timestamp_utc: String,
    pub status: String,
    pub manifest_path: String,
    pub packages: std::collections::HashMap<String, PackageLinks>,
}

// --- Simulate ---

#[derive(Debug, Clone, Serialize)]
pub struct SimulateCandidate {
    pub package: String,
    pub target_version: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct RawLogs {
    pub stdout: String,
    pub stderr: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct SimulateResponse {
    pub schema_version: String,
    pub timestamp_utc: String,
    pub resolver: String,
    pub status: String,
    pub manifest_path: String,
    pub candidate: SimulateCandidate,
    pub result: String,
    pub explanation: String,
    pub conflict_chain: Option<ConflictChain>,
    pub raw_logs: Option<RawLogs>,
}

// --- Apply ---

#[derive(Debug, Clone, Serialize)]
pub struct RollbackArtifact {
    pub schema_version: String,
    pub created_utc: String,
    pub manifest_path: String,
    pub original_content: String,
    pub reverse_updates: Vec<SafeUpdate>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ApplyData {
    pub applied: bool,
    pub manifest_path: String,
    pub output_path: Option<String>,
    pub patch_preview: String,
    pub updates_applied: Vec<SafeUpdate>,
    pub rollback: Option<RollbackArtifact>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ApplyResponse {
    pub schema_version: String,
    pub timestamp_utc: String,
    pub status: String,
    pub manifest_path: String,
    pub plan_path: String,
    pub dry_run: bool,
    pub apply: Option<ApplyData>,
}

// --- Verify ---

#[derive(Debug, Clone, Serialize)]
pub struct VerificationStep {
    pub name: String,
    pub command: String,
    pub passed: bool,
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub duration_seconds: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Verification {
    pub passed: bool,
    pub steps: Vec<VerificationStep>,
    pub summary: String,
    pub venv_path: String,
    pub modified_manifest_path: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct VerifyResponse {
    pub schema_version: String,
    pub timestamp_utc: String,
    pub status: String,
    pub manifest_path: String,
    pub plan_path: String,
    pub resolver: String,
    pub verification: Option<Verification>,
}
