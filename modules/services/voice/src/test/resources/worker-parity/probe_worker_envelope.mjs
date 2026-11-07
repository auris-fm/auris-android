#!/usr/bin/env node
/**
 * TRANSPORT-BYTES PARITY PROBE.
 *
 * Unlike probe_worker_intake_e2e.mjs, this probe does NOT rebuild an envelope that resembles the
 * client's. It is handed a file containing the EXACT request body the Android transport emitted (and
 * the Content-Type carrying its boundary) and feeds those bytes, unchanged, to the Cloud Worker's real
 * multipart intake (`intakeContributionUpload`). That is the only form of parity that can catch the
 * class of bug where client and server each "validate" but disagree about the bytes on the wire.
 *
 * Usage: node probe_worker_envelope.mjs <cloud-worker-root> <cases.jsonl>
 *   Each case line: {"id": "...", "bodyBase64": "...", "contentType": "multipart/form-data; boundary=..."}
 * Output, one line per case:
 *   <id>\tACCEPT\t<recordSha256>\t<audioSha256>\t<byteCount>\t<sampleCount>
 *   <id>\tREJECT\t<code>\t<path>
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { execFileSync } from "node:child_process";

const [workerRoot, casesPath] = process.argv.slice(2);
if (!workerRoot || !casesPath) {
  console.error("usage: probe_worker_envelope.mjs <cloud-worker-root> <cases.jsonl>");
  process.exit(2);
}

// PROVENANCE: the exact Worker revision, and DIRTY reported rather than concealed - an uncommitted
// local patch changes intake behaviour and would make a parity receipt worthless.
function provenance(root) {
  try {
    const rev = execFileSync("git", ["-C", root, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
    const tree = execFileSync("git", ["-C", root, "rev-parse", "HEAD^{tree}"], { encoding: "utf8" }).trim();
    const dirty = execFileSync("git", ["-C", root, "status", "--porcelain"], { encoding: "utf8" }).trim();
    return `${rev}\t${tree}\t${dirty.length > 0 ? "DIRTY" : "CLEAN"}`;
  } catch {
    return "UNKNOWN\tUNKNOWN\tUNKNOWN";
  }
}
console.log(`# worker-revision\t${provenance(workerRoot)}`);

const { intakeContributionUpload } = await import(
  pathToFileURL(`${workerRoot}/src/contributions/stream_intake.ts`).href
);

/** A ByteSource over one in-memory buffer, returning at most `max` bytes per read. */
function sourceOf(bytes) {
  let offset = 0;
  return {
    async read(max) {
      if (offset >= bytes.length) return new Uint8Array(0);
      const end = Math.min(offset + max, bytes.length);
      const chunk = bytes.subarray(offset, end);
      offset = end;
      return chunk;
    },
  };
}

/** A sink that retains the bytes it is given, so the probe can report what the Worker stored. */
function memorySink() {
  const parts = [];
  return {
    async open() {},
    async write(_key, bytes) {
      parts.push(new Uint8Array(bytes));
    },
    async commit() {},
    async abort() {},
    bytes() {
      const total = parts.reduce((n, p) => n + p.length, 0);
      const out = new Uint8Array(total);
      let at = 0;
      for (const p of parts) {
        out.set(p, at);
        at += p.length;
      }
      return out;
    },
  };
}

const cases = readFileSync(casesPath, "utf8")
  .split("\n")
  .filter((line) => line.trim().length > 0);

for (const line of cases) {
  const case_ = JSON.parse(line);
  const body = Uint8Array.from(Buffer.from(case_.bodyBase64, "base64"));
  const boundary = /boundary=([^;]+)/.exec(case_.contentType ?? "")?.[1];
  if (!boundary) {
    console.log(`${case_.id}\tREJECT\tmissing_boundary\t-`);
    continue;
  }
  try {
    const result = await intakeContributionUpload({
      source: sourceOf(body),
      boundary,
      limits: {
        maxMetadataBytes: 64 * 1024,
        maxAudioBytes: 32 * 1024 * 1024,
        maxRetainedBytes: 8 * 1024,
        maxTotalBytes: 34 * 1024 * 1024,
      },
      sink: memorySink(),
      declaredContentLength: body.length,
    });
    console.log(
      `${case_.id}\tACCEPT\t${result.recordSha256}\t${result.audio.sha256}\t${result.audio.byteCount}\t${result.audio.sampleCount}`,
    );
  } catch (error) {
    console.log(`${case_.id}\tREJECT\t${error.code ?? error.name ?? "unknown"}\t${error.path ?? "-"}`);
  }
}
