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


export type Profile = { id: string; firstName: string; lastName: string; fatherName?: string };
export type Contact = { id: string; type: string; value: string; verified: boolean; primary: boolean };

export async function getProfile(): Promise<Profile> {
  const r = await fetch('/api/v1/identity/profile', { credentials: 'same-origin' });
  if (!r.ok) throw new Error('profile failed');
  return r.json();
}

export async function getContacts(): Promise<Contact[]> {
  const r = await fetch('/api/v1/identity/contacts', { credentials: 'same-origin' });
  if (!r.ok) throw new Error('contacts failed');
  return r.json();
}

export async function verifyContact(id: string, code: string): Promise<{verified:boolean}> {
  return post<{verified:boolean}>(`/api/v1/identity/contacts/${id}/verify`, { code });
}

export async function setPrimaryContact(id: string): Promise<{accepted:boolean}> {
  return post<{accepted:boolean}>(`/api/v1/identity/contacts/${id}/primary`, {});
}

export async function removeContact(id: string): Promise<{accepted:boolean}> {
  const r = await fetch(`/api/v1/identity/contacts/${id}`, { method: 'DELETE', credentials: 'same-origin' });
  if (!r.ok) throw new Error('remove contact failed');
  return r.json();
}

export async function addContact(body: {type: string; value: string}): Promise<{id:string}> {
  return post<{id:string}>('/api/v1/identity/contacts', body);
}

export async function changePassword(body: {refreshCredential:string; currentPassword:string; newPassword:string}): Promise<{accepted:boolean}> {
  return post<{accepted:boolean}>('/api/v1/password/change', body);
}
export async function requestPasswordRecovery(contact:string): Promise<{accepted:boolean}> {
  return post<{accepted:boolean}>('/api/v1/password/recovery/request', {contact});
}
export async function confirmPasswordRecovery(body:{contact:string; code:string; newPassword:string}): Promise<{accepted:boolean}> {
  return post<{accepted:boolean}>('/api/v1/password/recovery/confirm', body);
}

export const bffClient = {
  login,
  listTenants,
  selectTenant,
  register: (body: RegisterRequest) => post<AcceptedResponse>('/api/v1/identity/registration', body),
  resend: (body: ResendRequest) => post<AcceptedResponse>('/api/v1/identity/registration/resend', body),
  confirm: (body: ConfirmRequest) => post<ConfirmedResponse>('/api/v1/identity/registration/confirm', body),
  getProfile,
  getContacts,
  addContact,
  verifyContact,
  setPrimaryContact,
  removeContact,
  changePassword,
  requestPasswordRecovery,
  confirmPasswordRecovery,
};
