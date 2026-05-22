import { mkdtempSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { AuditPersistence } from '../audit-persistence';
import type { AuditReport } from '../system-auditor';

function makeReport(overrides: Partial<AuditReport> = {}): AuditReport {
  return {
    state: {
      auditId: '123e4567-e89b-12d3-a456-426614174000',
      timestamp: 1700000000,
      cve202631431Remediated: true,
      pamDOORaSevered: true,
      circuitBreakerTripped: true,
      activeEdgeBlocks: 3,
      vectorDBConnected: true,
    },
    chainOfCustodyHash: 'a'.repeat(64),
    lockdownEnforced: true,
    coolingOffExpiration: 1700000000 + 48 * 60 * 60 * 1000,
    ...overrides,
  };
}

describe('AuditPersistence', () => {
  let tmpDir: string;
  let logPath: string;
  let persistence: AuditPersistence;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'vesper-audit-'));
    logPath = join(tmpDir, 'audit.log');
    persistence = new AuditPersistence(logPath);
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  describe('readAll', () => {
    it('returns empty array when log file does not exist', () => {
      expect(persistence.readAll()).toEqual([]);
    });

    it('returns all appended reports in order', () => {
      const r1 = makeReport({ lockdownEnforced: true });
      const r2 = makeReport({ lockdownEnforced: false });
      persistence.append(r1);
      persistence.append(r2);
      const all = persistence.readAll();
      expect(all).toHaveLength(2);
      expect(all[0].lockdownEnforced).toBe(true);
      expect(all[1].lockdownEnforced).toBe(false);
    });

    it('round-trips report data without loss', () => {
      const report = makeReport();
      persistence.append(report);
      const [loaded] = persistence.readAll();
      expect(loaded).toEqual(report);
    });
  });

  describe('append', () => {
    it('writes each report as a separate line', () => {
      persistence.append(makeReport());
      persistence.append(makeReport());
      persistence.append(makeReport());
      expect(persistence.readAll()).toHaveLength(3);
    });

    it('persists across separate AuditPersistence instances', () => {
      persistence.append(makeReport({ lockdownEnforced: true }));
      const other = new AuditPersistence(logPath);
      const all = other.readAll();
      expect(all).toHaveLength(1);
      expect(all[0].lockdownEnforced).toBe(true);
    });
  });

  describe('latest', () => {
    it('returns null when log file does not exist', () => {
      expect(persistence.latest()).toBeNull();
    });

    it('returns the last appended report', () => {
      persistence.append(makeReport({ lockdownEnforced: true }));
      persistence.append(makeReport({ lockdownEnforced: false }));
      expect(persistence.latest()?.lockdownEnforced).toBe(false);
    });

    it('returns the only report when there is one', () => {
      const report = makeReport();
      persistence.append(report);
      expect(persistence.latest()).toEqual(report);
    });
  });
});
