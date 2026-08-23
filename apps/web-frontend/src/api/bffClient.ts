import type { AcceptedResponse, ConfirmRequest, ConfirmedResponse, RegisterRequest, ResendRequest } from './generated/registration';
import { BffProblemError } from '../errors/problem';

function requestId(): string {
  return crypto.randomUUID();
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'content-type': 'application/json',
      'x-request-id': requestId(),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    if (problem?.code) throw new BffProblemError(problem);
    throw new Error('bff request failed');
  }
  return response.json() as Promise<T>;
}


export type SessionResponse = { csrfToken: string; mode: string };

export async function bootstrapSession(): Promise<SessionResponse> {
  return fetch('/api/v1/auth/session/bootstrap', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'x-request-id': requestId() },
  }).then(async (r) => {
    if (!r.ok) throw new Error('session bootstrap failed');
    return r.json();
  });
}

export async function login(body: { contact: string; password: string }): Promise<SessionResponse> {
  await bootstrapSession();
  return fetch('/api/v1/auth/local', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'content-type': 'application/json',
      'x-request-id': requestId(),
      'x-hooshix-client-ip': 'browser',
    },
    body: JSON.stringify({ channel: 'EMAIL', ...body }),
  }).then(async (r) => {
    if (!r.ok) throw new Error('login failed');
    return r.json();
  });
}


export type TenantChoice = { tenantId: string; membershipId: string; name: string; slug: string };
export type TenantList = { tenants: TenantChoice[]; suggestedMembershipId: string | null };
export type TenantSelectionResponse = { csrfToken: string; tenantId: string; membershipId: string; mode: string };

export async function listTenants(): Promise<TenantList> {
  const r = await fetch('/api/v1/identity/tenants', { credentials: 'same-origin' });
  if (!r.ok) throw new Error('tenant list failed');
  return r.json();
}

export async function selectTenant(membershipId: string): Promise<TenantSelectionResponse> {
  return post<TenantSelectionResponse>('/api/v1/identity/tenant-selection', { membershipId });
}

export const bffClient = {
  login,
  listTenants,
  selectTenant,
  register: (body: RegisterRequest) => post<AcceptedResponse>('/api/v1/identity/registration', body),
  resend: (body: ResendRequest) => post<AcceptedResponse>('/api/v1/identity/registration/resend', body),
  confirm: (body: ConfirmRequest) => post<ConfirmedResponse>('/api/v1/identity/registration/confirm', body),
};
