import { SystemAuditor } from '../system-auditor';

const BASE_STATE = {
  auditId: '123e4567-e89b-12d3-a456-426614174000',
  timestamp: 1700000000,
  cve202631431Remediated: true,
  pamDOORaSevered: true,
  circuitBreakerTripped: true,
  activeEdgeBlocks: 3,
  vectorDBConnected: true,
};

describe('SystemAuditor', () => {
  let auditor: SystemAuditor;

  beforeEach(() => {
    auditor = new SystemAuditor();
  });

  describe('valid state', () => {
    it('returns a valid AuditReport', () => {
      const report = auditor.executeFinalAudit(BASE_STATE);
      expect(report.state).toMatchObject(BASE_STATE);
      expect(report.lockdownEnforced).toBe(true);
      expect(report.chainOfCustodyHash).toHaveLength(64);
      expect(report.coolingOffExpiration).toBeGreaterThan(Date.now());
    });

    it('coolingOffExpiration is ~48h after construction', () => {
      const before = Date.now();
      const a = new SystemAuditor();
      const report = a.executeFinalAudit(BASE_STATE);
      const expected = before + 48 * 60 * 60 * 1000;
      expect(report.coolingOffExpiration).toBeGreaterThanOrEqual(expected);
      expect(report.coolingOffExpiration).toBeLessThan(expected + 1000);
    });
  });

  describe('lockdownEnforced derivation', () => {
    it('is false when circuitBreaker is off', () => {
      const report = auditor.executeFinalAudit({ ...BASE_STATE, circuitBreakerTripped: false });
      expect(report.lockdownEnforced).toBe(false);
    });

    it('is false when activeEdgeBlocks is 0', () => {
      const report = auditor.executeFinalAudit({ ...BASE_STATE, activeEdgeBlocks: 0 });
      expect(report.lockdownEnforced).toBe(false);
    });

    it('is false when vectorDB is disconnected', () => {
      const report = auditor.executeFinalAudit({ ...BASE_STATE, vectorDBConnected: false });
      expect(report.lockdownEnforced).toBe(false);
    });

    it('requires all three conditions for true', () => {
      const report = auditor.executeFinalAudit({
        ...BASE_STATE,
        circuitBreakerTripped: true,
        activeEdgeBlocks: 1,
        vectorDBConnected: true,
      });
      expect(report.lockdownEnforced).toBe(true);
    });
  });

  describe('critical failure guard', () => {
    it('throws when CVE is not remediated', () => {
      expect(() =>
        auditor.executeFinalAudit({ ...BASE_STATE, cve202631431Remediated: false })
      ).toThrow('CRITICAL FAILURE');
    });

    it('throws when PAM DOORA is not severed', () => {
      expect(() =>
        auditor.executeFinalAudit({ ...BASE_STATE, pamDOORaSevered: false })
      ).toThrow('CRITICAL FAILURE');
    });

    it('throws when both are unpatched', () => {
      expect(() =>
        auditor.executeFinalAudit({ ...BASE_STATE, cve202631431Remediated: false, pamDOORaSevered: false })
      ).toThrow('CRITICAL FAILURE');
    });
  });

  describe('chain-of-custody hash', () => {
    it('is 64 hex characters', () => {
      const { chainOfCustodyHash } = auditor.executeFinalAudit(BASE_STATE);
      expect(chainOfCustodyHash).toMatch(/^[a-f0-9]{64}$/);
    });

    it('is stable for identical inputs', () => {
      const h1 = auditor.executeFinalAudit(BASE_STATE).chainOfCustodyHash;
      const h2 = auditor.executeFinalAudit(BASE_STATE).chainOfCustodyHash;
      expect(h1).toBe(h2);
    });

    it('changes when state changes', () => {
      const h1 = auditor.executeFinalAudit(BASE_STATE).chainOfCustodyHash;
      const h2 = auditor.executeFinalAudit({ ...BASE_STATE, activeEdgeBlocks: 9 }).chainOfCustodyHash;
      expect(h1).not.toBe(h2);
    });

    it('is insertion-order independent (canonical)', () => {
      const reordered = {
        vectorDBConnected: BASE_STATE.vectorDBConnected,
        timestamp: BASE_STATE.timestamp,
        pamDOORaSevered: BASE_STATE.pamDOORaSevered,
        activeEdgeBlocks: BASE_STATE.activeEdgeBlocks,
        cve202631431Remediated: BASE_STATE.cve202631431Remediated,
        circuitBreakerTripped: BASE_STATE.circuitBreakerTripped,
        auditId: BASE_STATE.auditId,
      };
      const h1 = auditor.executeFinalAudit(BASE_STATE).chainOfCustodyHash;
      const h2 = auditor.executeFinalAudit(reordered).chainOfCustodyHash;
      expect(h1).toBe(h2);
    });
  });

  describe('Zod validation', () => {
    it('throws on invalid UUID', () => {
      expect(() =>
        auditor.executeFinalAudit({ ...BASE_STATE, auditId: 'not-a-uuid' })
      ).toThrow();
    });

    it('throws on negative timestamp', () => {
      expect(() =>
        auditor.executeFinalAudit({ ...BASE_STATE, timestamp: -1 })
      ).toThrow();
    });

    it('throws on negative activeEdgeBlocks', () => {
      expect(() =>
        auditor.executeFinalAudit({ ...BASE_STATE, activeEdgeBlocks: -1 })
      ).toThrow();
    });

    it('throws on missing required field', () => {
      const { auditId: _omit, ...withoutId } = BASE_STATE;
      expect(() => auditor.executeFinalAudit(withoutId)).toThrow();
    });
  });
});
