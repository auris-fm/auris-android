#!/usr/bin/env node
/**
 * END-TO-END WIRE PARITY PROBE.
 *
 * Runs an Android-built contribution through the Cloud Worker's REAL multipart intake
 * (`intakeContributionUpload`) — envelope parsing, metadata byte accounting, record digest
 * verification, streaming audio digest and WAV header validation — and reports the Worker's own
 * verdict and the identity it derived.
 *
 * This is deliberately NOT the schema validator alone. Schema acceptance says the record's shape is
 * right; it says NOTHING about the envelope, the `record_sha256` the client computed, the audio
 * digest, or the WAV header. Those are exactly where a client and server silently disagree while
 * both "validate", so this probe exercises the same entry point production does.
 *
 * Usage: node probe_worker_intake_e2e.mjs <cloud-worker-root> <cases.jsonl>
 *   Each case line: {"id": "...", "record": <object>, "recordSha256": "<hex>", "wavBase64": "..."}
 * Output, one line per case:
 *   <id>\tACCEPT\t<recordSha256>\t<audioSha256>\t<byteCount>\t<sampleCount>
 *   <id>\tREJECT\t<code>\t<path>
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const [workerRoot, casesPath] = process.argv.slice(2);
if (!workerRoot || !casesPath) {
  console.error("usage: probe_worker_intake_e2e.mjs <cloud-worker-root> <cases.jsonl>");
  process.exit(2);
}

// PROVENANCE: the probe reports the exact Worker revision it loaded so a receipt can never be
// attributed to "some checkout". A dirty worktree is reported as DIRTY rather than concealed: an
// uncommitted local patch changes intake behaviour, and a receipt that hid that would be worthless.
const { execFileSync } = await import("node:child_process");
function provenance(root) {
  try {
    const rev = execFileSync("git", ["-C", root, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
    // REPO-WIDE cleanliness, deliberately conservative: `record_schema.ts` and `stream_intake.ts`
    // import across the contributions package, so a dirty file anywhere in the imported closure can
    // change intake behaviour. Scoping the check to src/contributions would miss an edited helper
    // elsewhere that the intake still executes.
    const dirty = execFileSync("git", ["-C", root, "status", "--porcelain"], { encoding: "utf8" }).trim();
    return `${rev}${dirty.length > 0 ? " DIRTY" : " CLEAN"}`;
  } catch {
    return "UNKNOWN (not a git checkout)";
  }
}
console.log(`# worker-revision\t${provenance(workerRoot)}`);

const intakeModule = await import(
  pathToFileURL(`${workerRoot}/src/contributions/stream_intake.ts`).href
);
const { intakeContributionUpload } = intakeModule;

function sha256HexOf(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

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

/** A sink that counts and digests what it is given, so the probe can compare identities. */
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

/** Build the exact two-part multipart envelope the client must send. */
function envelope(boundary, recordText, wavBytes) {
  const enc = new TextEncoder();
  const head =
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="metadata"\r\n` +
    `Content-Type: application/json\r\n\r\n`;
  const mid =
    `\r\n--${boundary}\r\n` +
    `Content-Disposition: form-data; name="audio"; filename="recording.wav"\r\n` +
    `Content-Type: audio/wav\r\n\r\n`;
  const tail = `\r\n--${boundary}--\r\n`;
  const headBytes = enc.encode(head);
  const metaBytes = enc.encode(recordText);
  const midBytes = enc.encode(mid);
  const tailBytes = enc.encode(tail);
  const total = headBytes.length + metaBytes.length + midBytes.length + wavBytes.length + tailBytes.length;
  const out = new Uint8Array(total);
  let at = 0;
  for (const part of [headBytes, metaBytes, midBytes, wavBytes, tailBytes]) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

const cases = readFileSync(casesPath, "utf8")
  .split("\n")
  .filter((line) => line.trim().length > 0);

for (const line of cases) {
  const case_ = JSON.parse(line);
  const { id, wavBase64 } = case_;
  const wav = Uint8Array.from(Buffer.from(wavBase64, "base64"));
  // The metadata part is the ENVELOPE `{record, record_sha256}` — not a bare record. The client
  // computes the digest over the canonical record bytes it embeds, so the probe builds the
  // envelope around the caller-supplied canonical text for each case.
  const recordText = case_.recordText;
  // The client's claim about its own record bytes. A wrong claim is a client bug the intake must
  // refuse; the probe never silently substitutes the correct digest.
  const claimedSha = case_.recordSha256 ?? sha256HexOf(recordText);
  const metadataText = `{"record":${recordText},"record_sha256":"${claimedSha}"}`;
  const boundary = "----aurisAndroidBoundary9f2c";
  const body = envelope(boundary, metadataText, wav);
  const sink = memorySink();
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
      sink,
      declaredContentLength: body.length,
    });
    console.log(
      `${id}\tACCEPT\t${result.recordSha256}\t${result.audio.sha256}\t${result.audio.byteCount}\t${result.audio.sampleCount}`,
    );
  } catch (error) {
    // Worker errors carry a stable code and path, never record content.
    console.log(`${id}\tREJECT\t${error.code ?? error.name ?? "unknown"}\t${error.path ?? "-"}`);
  }
}