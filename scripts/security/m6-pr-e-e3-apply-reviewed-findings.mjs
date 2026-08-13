#!/usr/bin/env node
import { createHash } from 'node:crypto';

const ALLOWED = new Set(['APPLICABLE','NOT_APPLICABLE','UNREACHABLE','MITIGATED','ACCEPTED_WITH_EXPIRY','UNRESOLVED','EVIDENCE_UNAVAILABLE']);
const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const canonical=v=>JSON.stringify(stable(v));
const sha256=v=>createHash('sha256').update(v).digest('hex');

export function applyReviewedFindings(intake, review) {
  if (!intake || !review) throw new Error('intake and review required');
  if (review.repository !== intake.repository) throw new Error('review repository mismatch');
  if (!/^[0-9a-f]{40}$/.test(review.reviewBasisHead || '')) throw new Error('review basis Head must be exact SHA');
  if (!/^[0-9a-f]{64}$/.test(review.reviewBasisIntakeCanonicalSha256 || '')) throw new Error('review basis intake digest must be SHA-256');
  const byId=new Map(intake.decisions.map(f=>[`${f.sourceClass}:${f.findingId}`,f]));
  const seen=new Set();
  const reviewed=new Map();
  for (const r of review.reviewedFindings || []) {
    const key=`${r.sourceClass}:${r.findingId}`;
    if (seen.has(key)) throw new Error(`duplicate reviewed finding ${key}`);
    seen.add(key);
    const original=byId.get(key);
    if (!original) throw new Error(`reviewed finding absent from intake ${key}`);
    if (original.disposition !== 'UNRESOLVED') throw new Error(`review can only advance UNRESOLVED intake ${key}`);
    if (!ALLOWED.has(r.disposition)) throw new Error(`invalid reviewed disposition ${r.disposition}`);
    if (r.severityBand !== original.severityBand) throw new Error(`severity band mutation prohibited ${key}`);
    if ((r.exception ?? null) !== null) throw new Error(`E3-I2 does not authorize exceptions ${key}`);
    if (r.disposition === 'NOT_APPLICABLE' && r.exploitPreconditionsSatisfied !== false) {
      throw new Error(`NOT_APPLICABLE requires positive absent-precondition evidence ${key}`);
    }
    reviewed.set(key,r);
  }
  const decisions=intake.decisions.map(f=>{
    const r=reviewed.get(`${f.sourceClass}:${f.findingId}`);
    return r ? stable({...f,disposition:r.disposition,exploitPreconditions:r.exploitPreconditions,mitigations:r.mitigations,reviewEvidence:{
      sourceBlobSha:r.sourceBlobSha,sourcePath:r.sourcePath,evidenceCode:r.evidenceCode,evidence:r.evidence,
      exploitPreconditionsSatisfied:r.exploitPreconditionsSatisfied
    }}) : f;
  });
  const dispositionCounts={};
  for(const f of decisions) dispositionCounts[f.disposition]=(dispositionCounts[f.disposition]||0)+1;
  const payload=stable({
    schemaVersion:'M6_PR_E_E3_I2_TRIAGE_V1',
    repository:intake.repository,
    commitSha:intake.commitSha,
    sourceIntakeCanonicalSha256:intake.contentSha256,
    reviewBasisHead:review.reviewBasisHead,
    reviewBasisIntakeCanonicalSha256:review.reviewBasisIntakeCanonicalSha256,
    reviewedFindingCount:reviewed.size,
    decisions,
    summary:{
      dispositionCounts,
      resolvedCount:decisions.length-(dispositionCounts.UNRESOLVED||0),
      unresolvedCount:dispositionCounts.UNRESOLVED||0,
      releaseBlocked:true,
      reasonCodes:['AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE','E3_SCANNER_FINDINGS_UNRESOLVED']
    }
  });
  return stable({...payload,contentSha256:sha256(canonical(payload))});
}
export const canonicalReviewedTriage=canonical;
