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