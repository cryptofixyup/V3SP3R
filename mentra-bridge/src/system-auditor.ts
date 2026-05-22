import { z } from 'zod';
import { createHash } from 'crypto';

const AuditStateSchema = z.object({
  auditId: z.string().uuid(),
  timestamp: z.number().int().positive(),
  cve202631431Remediated: z.boolean(),
  pamDOORaSevered: z.boolean(),
  circuitBreakerTripped: z.boolean(),
  activeEdgeBlocks: z.number().int().nonnegative(),
  vectorDBConnected: z.boolean(),
});

export type AuditState = z.infer<typeof AuditStateSchema>;

const AuditReportSchema = z.object({
  state: AuditStateSchema,
  chainOfCustodyHash: z.string().length(64),
  lockdownEnforced: z.boolean(),
  coolingOffExpiration: z.number().int().positive(),
});

export type AuditReport = z.infer<typeof AuditReportSchema>;

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  const sorted = Object.keys(value as object)
    .sort()
    .map((k) => JSON.stringify(k) + ':' + canonicalJson((value as Record<string, unknown>)[k]));
  return '{' + sorted.join(',') + '}';
}

export class SystemAuditor {
  private readonly executionTime: number;
  private readonly coolingOffPeriodMs = 48 * 60 * 60 * 1000;

  constructor() {
    this.executionTime = Date.now();
  }

  public executeFinalAudit(currentState: unknown): AuditReport {
    const validatedState = AuditStateSchema.parse(currentState);

    if (!validatedState.cve202631431Remediated || !validatedState.pamDOORaSevered) {
      throw new Error('CRITICAL FAILURE: Base vulnerabilities remain unpatched. Audit aborted.');
    }

    const hash = createHash('sha256').update(canonicalJson(validatedState)).digest('hex');
    const coolingOffExpiration = this.executionTime + this.coolingOffPeriodMs;
    const lockdownEnforced =
      validatedState.circuitBreakerTripped && validatedState.activeEdgeBlocks > 0;

    return AuditReportSchema.parse({
      state: validatedState,
      chainOfCustodyHash: hash,
      lockdownEnforced,
      coolingOffExpiration,
    });
  }
}
