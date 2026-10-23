#!/usr/bin/env node
/**
 * Worker-intake parity probe.
 *
 * Reads records produced by the ANDROID builder (as JSON, one per line, each with an id) and runs
 * each through the CLOUD WORKER'S OWN validator (`record_schema.ts`, imported directly from the
 * cloud worktree). This is the shared authority: it is the code that will actually accept or refuse
 * the upload, so agreement with it is evidence about the wire, whereas agreement with the Android
 * builder proves only that Android agrees with itself.
 *
 * Usage: node probe_worker_intake.mjs <cloud-worker-root> <records.jsonl>
 * Output: one line per record: "<id>\tACCEPT" or "<id>\tREJECT <code> <path>"
 */
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const [workerRoot, recordsPath] = process.argv.slice(2);
if (!workerRoot || !recordsPath) {
  console.error("usage: probe_worker_intake.mjs <cloud-worker-root> <records.jsonl>");
  process.exit(2);
}

// PROVENANCE: report the exact Worker revision loaded, so a receipt names its authority rather than
// "some checkout". Repo-wide cleanliness: the schema module imports within the package, so a dirty
// file anywhere in the closure can change what this probe measures.
const { execFileSync } = await import("node:child_process");
function provenance(root) {
  try {
    const rev = execFileSync("git", ["-C", root, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
    const dirty = execFileSync("git", ["-C", root, "status", "--porcelain"], { encoding: "utf8" }).trim();
    return `${rev}${dirty.length > 0 ? " DIRTY" : " CLEAN"}`;
  } catch {
    return "UNKNOWN (not a git checkout)";
  }
}
console.log(`# worker-revision\t${provenance(workerRoot)}`);

// Authority pins: each entry names an exact, CLEAN revision and the single behaviour scope it is
// approved for. Printing the full set lets a receipt show which authorities exist and which one the
// run actually measured, instead of a bare revision string whose purpose lives only in a comment.
// An UNKNOWN unpinned checkout is reported as such; it matches no pin and no test accepts it.
const PINS = [
  ["d8fe823cbe43ebb310d632b482312c7b282ef018", "pre-delta intake guard and record schema fixtures"],
  ["c569b319e19b5971ada44a0f53245630688c1305", "delta736d3180 strict intake: observation_unavailable with state unknown"],
];
for (const [revision, purpose] of PINS) console.log(`# authority-pin\t${revision}\t${purpose}`);

const moduleUrl = pathToFileURL(`${workerRoot}/src/contributions/record_schema.ts`).href;
const { validateContributionRecord } = await import(moduleUrl);

const lines = readFileSync(recordsPath, "utf8").split("\n").filter((line) => line.trim().length > 0);
for (const line of lines) {
  const { id, record } = JSON.parse(line);
  try {
    validateContributionRecord(record);
    console.log(`${id}\tACCEPT`);
  } catch (error) {
    // The Worker's error carries a stable code and path, never record content.
    console.log(`${id}\tREJECT ${error.code ?? "unknown"} ${error.path ?? "?"}`);
  }
}