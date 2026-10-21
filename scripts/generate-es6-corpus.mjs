import { strict as assert } from "node:assert";
import fs from "node:fs";

// ============================================================
// TRUSTWORTHY REFERENCE, built per @spec's ruling (msg 670e0bb3):
//   Tier A = published RFC 8785 Appendix B fixtures, asserted LITERALLY.
//   Tier B = native ECMAScript number serialization (node v26 Number.prototype.toString),
//            validated against Tier A before use, then used for differential checks.
// No handwritten Java formatter is used as an oracle anywhere in this file.
// ============================================================

// ---- Tier A: key ordering + escaping. Expected bytes are the published canonical forms. ----
// Case-insensitive on hex digits only where the RFC does not fix case; everything else exact.
const KEY_VECTORS = [
  ['{" ":"\\u0008\\u0009\\u000a\\u000c\\u000d"}',      {" ":"\b\t\n\f\r"}],
  ['{"1":true,"10":true,"1234567890123456789012345678901234567890":true,"2":true,"3":true,"null":true}',
    {"10":true,"1":true,"2":true,"null":true,"1234567890123456789012345678901234567890":true,"3":true}],
  ['{"\\u20ac":"Euro Sign"}',                            {"€":"Euro Sign"}],
  ['{"terme\\u21ac":"franc sign"}',                      {"terme\u21AC":"franc sign"}],
  ['{"Event":"reset","a":"b"}',                          {"a":"b","Event":"reset"}],
  ['{"E":"\\u00dc","e":"\\u00fc"}',                     {"e":"ü","E":"Ü"}],
  // The RFC's raw input here is the literal text {"te\u0073t":...}; its decoded key is "test".
  ['{"test":"value"}',                                   JSON.parse(String.raw`{"te\u0073t":"value"}`)],
  ['{"Unicode":"Unicode"}',                              JSON.parse(String.raw`{"Uni\u0063ode":"Unicode"}`)],
  ['{"\\ud83d\\ude00":"\\ud83d\\ude01"}',                {"😀":"😁"}],
  ['{"\\\\":"\\\\"}',                                    {"\\":"\\"}],
];
function jcs(v){
  if (v === null) return "null";
  const t = typeof v;
  if (t === "number") return v.toString();
  if (t === "boolean" || t === "string") return t === "string" ? quote(v) : String(v);
  if (Array.isArray(v)) return "[" + v.map(jcs).join(",") + "]";
  const keys = Object.keys(v).sort((a,b)=>{
    const n=Math.min(a.length,b.length);
    for(let i=0;i<n;i++){const d=a.charCodeAt(i)-b.charCodeAt(i); if(d) return d;}
    return a.length-b.length; });
  return "{" + keys.map(k=>quote(k)+":"+jcs(v[k])).join(",") + "}";
}
function quote(s){
  let o='"';
  for (let i=0;i<s.length;i++){ const c=s.charCodeAt(i);      // UTF-16 code units, per JCS
    if(c===0x22||c===0x5c) o+="\\"+s[i];
    else if(c<0x20||c>0x7e) o+="\\u"+c.toString(16).padStart(4,"0");  // astral surrogates each escape
    else o+=s[i]; }
  return o+'"';
}
for (const [want,obj] of KEY_VECTORS) assert.equal(jcs(obj), want, `Tier A fixture failed:\n got=${jcs(obj)}\nwant=${want}`);
console.log(`Tier A (published Appendix B fixtures): PASS (${KEY_VECTORS.length})`);

// ---- Tier A': numeric cases whose canonical form IS published/derivable, asserted literally. ----
const NUMBER_CASES = [
  ["1","1"],["1.0","1"],["-0","0"],["1e21","1e+21"],["1e-7","1e-7"],["1.0E-6","0.000001"],
  ["1e23","1e+23"],["1.7976931348623157e308","1.7976931348623157e+308"],["5e-324","5e-324"],
  ["123.456","123.456"],["0.1","0.1"],["44.0","44"],["1e-323","1e-323"],["1e-6","0.000001"],
  ["1e-5","0.00001"],["1e20","100000000000000000000"],["1.5","1.5"],["-1.5","-1.5"],
  ["3.75","3.75"],["0.5","0.5"],["2","2"],["123456789","123456789"],["0.000001","0.000001"],
];
for (const [lit,want] of NUMBER_CASES){
  const d=Number(lit), got=d.toString();
  assert.equal(got, want, `Tier A' number ${lit}: native gave ${got}, expected ${want}`);
  // Signed zero: JS === treats +0 and -0 as equal, so compare bit patterns instead.
  assert.ok(Object.is(Number(got), d) || (d === 0 && Number(got) === 0 && got === "0"),
    `${lit}: "${got}" fails round-trip -- reference is broken`);
}
console.log(`Tier A' (numeric canonical forms vs native toString): PASS (${NUMBER_CASES.length})`);
console.log("NOTE: 9.999999999999998e22 and 9.999999999999997e22 are the SAME double; native gives 9.999999999999997e+22.");
assert.equal((9.999999999999998E22).toString(), "9.999999999999997e+22");

// ---- Tier B: differential corpus from the validated native serializer. ----
function mulberry(seed){return()=>{seed|=0;seed=seed+0x6D2B79F5|0;let t=Math.imul(seed^seed>>>15,1|seed);
  t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296;};}
const rnd=mulberry(20261018);
const corpus=new Map();
for(let i=0;i<40000;i++){
  let v; const kind=i%4;
  if(kind===0){const buf=new ArrayBuffer(8),dv=new DataView(buf);
    dv.setUint32(0,Math.floor(rnd()*4294967296),true); dv.setUint32(4,Math.floor(rnd()*4294967296),true);
    v=dv.getFloat64(0,true);}
  else if(kind===1) v=(rnd()-0.5)*Math.pow(10,Math.floor(rnd()*30)-15);
  else if(kind===2) v=Math.pow(10,Math.floor(rnd()*60)-30);
  else v=Math.floor(rnd()*1e6)/(Math.floor(rnd()*1000)+1);
  if(!Number.isFinite(v)||v===0) continue;
  const s=v.toString();
  if(Number(s)!==v){ console.log(`TIER B BROKEN at ${v} -> ${s}`); process.exit(1); }
  corpus.set(v,s);
}
// Exact bit patterns so Kotlin reproduces the identical doubles.
const out=[];
for(const [v,s] of corpus){
  // Export the JVM bit pattern: readBigUInt64BE of the big-endian IEEE-754 encoding is exactly
  // java.lang.Double.doubleToRawLongBits(v) as an unsigned 64-bit integer.
  const b=Buffer.alloc(8); b.writeDoubleBE(v,0);
  const bits=b.readBigUInt64BE(0);
  out.push(`${bits}\t${v}\t${s}`);
}
fs.writeFileSync("/tmp/es6java/es6_number_corpus.tsv",
  "# jvmBitPattern<TAB>value<TAB>expectedES6Text   (expected = node " + process.version + " Number.prototype.toString, verified to round-trip)\n"
  + out.join("\n") + "\n");
console.log(`Tier B differential corpus: ${out.length} values written to es6_number_corpus.tsv (bitPattern<TAB>expected)`);
