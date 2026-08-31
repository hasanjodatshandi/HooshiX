import { describe, expect, it } from 'vitest';
import {
  canonicalContact,
  canonicalEmail,
  canonicalName,
  canonicalPhone,
  normalizedCurrentPassword,
  normalizedNewPassword,
  recoveryCode,
  totpCode,
  verificationCode,
} from './userInput';

describe('user input validation', () => {
  it('canonicalizes valid contact and name values', () => {
    expect(canonicalEmail('  Person@EXAMPLE.COM ')).toBe('Person@example.com');
    expect(canonicalPhone(' +989121234567 ')).toBe('+989121234567');
    expect(canonicalContact('EMAIL', 'a@EXAMPLE.COM')).toBe('a@example.com');
    expect(canonicalContact('PHONE', '+12025550123')).toBe('+12025550123');
    expect(canonicalName('  Alice  ')).toBe('Alice');
    expect(canonicalName(' ', false)).toBeUndefined();
  });

  it.each([
    '',
    'missing-at.example.com',
    'a@@example.com',
    '.a@example.com',
    'a@localhost',
    `a@${'x'.repeat(254)}.com`,
    `${'x'.repeat(65)}@example.com`,
    'a b@example.com',
  ])('rejects invalid email %j', (value) => {
    expect(() => canonicalEmail(value)).toThrow('Invalid email format');
  });

  it.each(['09121234567', '+012345678', '+123', '+1234567890123456'])
    ('rejects invalid phone %j', (value) => {
      expect(() => canonicalPhone(value)).toThrow('Invalid phone number format');
    });

  it('normalizes password and one-time proof formats without weakening bounds', () => {
    expect(normalizedNewPassword('long-enough-password')).toBe('long-enough-password');
    expect(normalizedCurrentPassword('current')).toBe('current');
    expect(verificationCode(' 12345678 ')).toBe('12345678');
    expect(totpCode(' 123456 ')).toBe('123456');
    expect(recoveryCode('abcd-efgh-jklm-npqr')).toBe('ABCD-EFGH-JKLM-NPQR');
  });

  it('rejects invalid names, passwords, and proof values', () => {
    expect(() => canonicalName('')).toThrow('Invalid name format');
    expect(() => canonicalName(`x${String.fromCharCode(0)}`)).toThrow('Invalid name format');
    expect(() => canonicalName('x'.repeat(121))).toThrow('Invalid name format');
    expect(() => normalizedNewPassword('too-short')).toThrow('Invalid password format');
    expect(() => normalizedNewPassword('x'.repeat(129))).toThrow('Invalid password format');
    expect(() => normalizedCurrentPassword('')).toThrow('Invalid password format');
    expect(() => verificationCode('1234567a')).toThrow('Invalid verification code format');
    expect(() => totpCode('12345')).toThrow('Invalid TOTP code format');
    expect(() => recoveryCode('ABCD-EFGH-JKLM-NP01')).toThrow('Invalid recovery code format');
  });
});
