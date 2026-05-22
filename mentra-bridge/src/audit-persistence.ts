import { appendFileSync, readFileSync, existsSync } from 'fs';
import { AuditReport } from './system-auditor';

export class AuditPersistence {
  constructor(private readonly logPath: string = './audit.log') {}

  append(report: AuditReport): void {
    appendFileSync(this.logPath, JSON.stringify(report) + '\n', 'utf8');
  }

  readAll(): AuditReport[] {
    if (!existsSync(this.logPath)) return [];
    return readFileSync(this.logPath, 'utf8')
      .split('\n')
      .filter((line) => line.trim().length > 0)
      .map((line) => JSON.parse(line) as AuditReport);
  }

  latest(): AuditReport | null {
    const all = this.readAll();
    return all.length > 0 ? all[all.length - 1] : null;
  }
}
