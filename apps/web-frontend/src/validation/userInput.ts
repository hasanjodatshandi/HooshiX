const EMAIL_LOCAL = /^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$/;
const E164 = /^\+[1-9][0-9]{7,14}$/;
const CONTROL_OR_WHITESPACE = /[\p{Cc}\p{Cf}\p{Z}]/u;
const UTF8 = new TextEncoder();

export function canonicalEmail(raw: string): string {
  const value = raw.trim().normalize('NFC');
  if (!value || value.length > 254 || CONTROL_OR_WHITESPACE.test(value)) invalid('email');
  const at = value.indexOf('@');
  if (at <= 0 || at !== value.lastIndexOf('@') || at === value.length - 1) invalid('email');
  const local = value.slice(0, at);
  if (local.length > 64 || !EMAIL_LOCAL.test(local)) invalid('email');
  let domain: string;
  try {
    domain = new URL(`http://${value.slice(at + 1)}`).hostname.toLowerCase();
  } catch {
    invalid('email');
  }
  if (!domain || domain.length > 253 || !domain.includes('.') || local.length + domain.length + 1 > 254) {
    invalid('email');
  }
  return `${local}@${domain}`;
}

export function canonicalPhone(raw: string): string {
  const value = raw.trim().normalize('NFC');
  if (!E164.test(value)) invalid('phone number');
  return value;
}

export function canonicalContact(type: 'EMAIL' | 'PHONE', raw: string): string {
  return type === 'EMAIL' ? canonicalEmail(raw) : canonicalPhone(raw);
}

export function canonicalName(raw: string, required = true): string | undefined {
  const value = raw.trim().normalize('NFC');
  if ((!value && required) || Array.from(value).length > 120 || /\p{Cc}/u.test(value)) {
    invalid('name');
  }
  return value || undefined;
}

export function normalizedNewPassword(raw: string): string {
  const value = raw.normalize('NFC');
  const codePoints = Array.from(value).length;
  if (codePoints < 12 || codePoints > 128 || UTF8.encode(value).length > 4096) invalid('password');
  return value;
}

export function normalizedCurrentPassword(raw: string): string {
  const value = raw.normalize('NFC');
  if (!value || UTF8.encode(value).length > 4096) invalid('password');
  return value;
}

export function verificationCode(raw: string): string {
  const value = raw.trim();
  if (!/^[0-9]{8}$/.test(value)) invalid('verification code');
  return value;
}

function invalid(field: string): never {
  throw new Error(`Invalid ${field} format`);
}
