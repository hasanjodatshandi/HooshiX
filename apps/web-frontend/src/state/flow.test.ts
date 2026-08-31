import { describe, expect, it } from 'vitest';
import { canEnterStage, type FlowStage } from './flow';

describe('flow stage guards', () => {
  it.each([
    ['registration', false, false, null, true],
    ['verification', false, false, null, false],
    ['verification', true, false, null, true],
    ['authenticated', false, false, null, false],
    ['authenticated', false, true, null, true],
    ['tenant-selection', false, true, null, true],
    ['application', false, true, null, false],
    ['application', false, true, 'tenant', true],
  ] as const)(
    '%s contact=%s authenticated=%s tenant=%s -> %s',
    (stage, hasContact, authenticated, tenantId, allowed) => {
      expect(canEnterStage(stage as FlowStage, {
        contact: hasContact ? 'a@example.com' : '',
        authenticated,
        tenantId,
      })).toBe(allowed);
    },
  );
});
