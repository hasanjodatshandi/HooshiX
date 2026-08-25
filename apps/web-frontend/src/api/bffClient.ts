import type { components } from './generated/schema';
import { BffProblemError } from '../errors/problem';

type Schemas = components['schemas'];

export type AcceptedResponse = Schemas['AcceptedResponse'];
export type ConfirmRequest = Schemas['ConfirmRequest'];
export type ConfirmedResponse = Schemas['ConfirmedResponse'];
export type RegisterRequest = Schemas['RegisterRequest'];
export type ResendRequest = Schemas['ResendRequest'];
export type SessionResponse = Schemas['SessionResponse'];
export type TenantChoice = Schemas['TenantChoice'];
export type TenantList = Schemas['TenantListResponse'];
export type TenantSelectionResponse = Schemas['TenantSelectionResponse'];
export type Profile = Schemas['ProfileResponse'];
export type Contact = Schemas['ContactResponse'];
export type PasswordChangedResponse = Schemas['PasswordChangedResponse'];

type LocalLoginRequest = Schemas['LocalLoginRequest'];
type SelectTenantRequest = Schemas['SelectTenantRequest'];
type UpdateProfileRequest = Schemas['UpdateProfileRequest'];
type VerifyContactRequest = Schemas['VerifyContactRequest'];
type AddContactRequest = Schemas['AddContactRequest'];
type ChangePasswordRequest = Schemas['ChangePasswordRequest'];
type PasswordRecoveryRequest = Schemas['PasswordRecoveryRequest'];
type PasswordRecoveryConfirmRequest = Schemas['PasswordRecoveryConfirmRequest'];

function requestId(): string {
  return crypto.randomUUID();
}

let csrfToken: string | null = null;

function rememberCsrf(response: SessionResponse): SessionResponse {
  csrfToken = response.csrfToken;
  return response;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'content-type': 'application/json',
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    if (response.status === 401) csrfToken = null;
    const problem = await response.json().catch(() => null);
    if (problem?.code) throw new BffProblemError(problem);
    throw new Error('bff request failed');
  }
  return response.json() as Promise<T>;
}

async function postWithoutBody<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
  });
  if (!response.ok) {
    if (response.status === 401) csrfToken = null;
    const problem = await response.json().catch(() => null);
    if (problem?.code) throw new BffProblemError(problem);
    throw new Error('bff request failed');
  }
  return response.json() as Promise<T>;
}


export async function bootstrapSession(): Promise<SessionResponse> {
  return fetch('/api/v1/auth/session/bootstrap', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'x-request-id': requestId() },
  }).then(async (r) => {
    if (!r.ok) throw new Error('session bootstrap failed');
    return rememberCsrf(await r.json() as SessionResponse);
  });
}

export async function login(
  body: Pick<LocalLoginRequest, 'contact' | 'password'>,
): Promise<SessionResponse> {
  if (!csrfToken) await bootstrapSession();
  return fetch('/api/v1/auth/local', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'content-type': 'application/json',
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
    body: JSON.stringify({ channel: 'EMAIL', ...body }),
  }).then(async (r) => {
    if (!r.ok) {
      if (r.status === 401) csrfToken = null;
      throw new Error('login failed');
    }
    return rememberCsrf(await r.json() as SessionResponse);
  });
}


export async function listTenants(): Promise<TenantList> {
  const r = await fetch('/api/v1/identity/tenants', { credentials: 'same-origin' });
  if (!r.ok) throw new Error('tenant list failed');
  return r.json();
}

export async function selectTenant(membershipId: string): Promise<TenantSelectionResponse> {
  const body: SelectTenantRequest = { membershipId };
  const response = await post<TenantSelectionResponse>('/api/v1/identity/tenant-selection', body);
  csrfToken = response.csrfToken;
  return response;
}


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

export async function updateProfile(body: UpdateProfileRequest): Promise<AcceptedResponse> {
  const response = await fetch('/api/v1/identity/profile', {
    method: 'PUT',
    credentials: 'same-origin',
    headers: {
      'content-type': 'application/json',
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error('profile update failed');
  return response.json();
}

export async function verifyContact(id: string, code: string): Promise<Schemas['VerifiedResponse']> {
  const body: VerifyContactRequest = { code };
  return post<Schemas['VerifiedResponse']>(`/api/v1/identity/contacts/${id}/verify`, body);
}

export async function resendContactVerification(id: string): Promise<AcceptedResponse> {
  return postWithoutBody<AcceptedResponse>(`/api/v1/identity/contacts/${id}/resend`);
}

export async function setPrimaryContact(id: string): Promise<AcceptedResponse> {
  return postWithoutBody<AcceptedResponse>(`/api/v1/identity/contacts/${id}/primary`);
}

export async function removeContact(id: string): Promise<AcceptedResponse> {
  const r = await fetch(`/api/v1/identity/contacts/${id}`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: {
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
  });
  if (!r.ok) throw new Error('remove contact failed');
  return r.json();
}

export async function addContact(body: AddContactRequest): Promise<Schemas['CreatedContactResponse']> {
  return post<Schemas['CreatedContactResponse']>('/api/v1/identity/contacts', body);
}

export async function changePassword(body: ChangePasswordRequest): Promise<PasswordChangedResponse> {
  const response = await post<PasswordChangedResponse>('/api/v1/password/change', body);
  csrfToken = response.csrfToken;
  return response;
}
export async function requestPasswordRecovery(contact: string): Promise<AcceptedResponse> {
  const body: PasswordRecoveryRequest = { channel: 'EMAIL', contact };
  return post<AcceptedResponse>('/api/v1/password/recovery/request', body);
}
export async function confirmPasswordRecovery(
  input: Omit<PasswordRecoveryConfirmRequest, 'channel'>,
): Promise<AcceptedResponse> {
  const body: PasswordRecoveryConfirmRequest = { channel: 'EMAIL', ...input };
  return post<AcceptedResponse>('/api/v1/password/recovery/confirm', body);
}

export const bffClient = {
  login,
  listTenants,
  selectTenant,
  register: (body: RegisterRequest) => post<AcceptedResponse>('/api/v1/identity/registration', body),
  resend: (body: ResendRequest) => post<AcceptedResponse>('/api/v1/identity/registration/resend', body),
  confirm: (body: ConfirmRequest) => post<ConfirmedResponse>('/api/v1/identity/registration/confirm', body),
  getProfile,
  updateProfile,
  getContacts,
  addContact,
  resendContactVerification,
  verifyContact,
  setPrimaryContact,
  removeContact,
  changePassword,
  requestPasswordRecovery,
  confirmPasswordRecovery,
};
