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
export type MfaProof = Schemas['MfaProofRequest'];
export type MfaStatus = Schemas['MfaStatusResponse'];
export type TotpEnrollment = Schemas['TotpEnrollmentResponse'];
export type RecoveryCodes = Schemas['RecoveryCodesResponse'];
export type OidcStartResponse = Schemas['OidcStartResponse'];
export type ExternalIdentityStatus = Schemas['ExternalIdentityStatusResponse'];
export type SessionState = Schemas['SessionStateResponse'];
export type TenantLifecycleResult = Schemas['TenantLifecycleResultResponse'];
export type InvitationList = Schemas['InvitationListResponse'];
export type InvitationSummary = Schemas['InvitationSummaryResponse'];
export type InvitationState = Schemas['InvitationStateResponse'];
export type InvitationCreated = Schemas['InvitationCreatedResponse'];
export type AcceptedInvitation = Schemas['AcceptedInvitationResponse'];

type LocalLoginRequest = Schemas['LocalLoginRequest'];
type SelectTenantRequest = Schemas['SelectTenantRequest'];
type UpdateProfileRequest = Schemas['UpdateProfileRequest'];
type VerifyContactRequest = Schemas['VerifyContactRequest'];
type AddContactRequest = Schemas['AddContactRequest'];
type ChangePasswordRequest = Schemas['ChangePasswordRequest'];
type PasswordRecoveryRequest = Schemas['PasswordRecoveryRequest'];
type PasswordRecoveryConfirmRequest = Schemas['PasswordRecoveryConfirmRequest'];
type StartTotpEnrollmentRequest = Schemas['StartTotpEnrollmentRequest'];
type ConfirmTotpEnrollmentRequest = Schemas['ConfirmTotpEnrollmentRequest'];
type OidcStartRequest = Schemas['OidcStartRequest'];

function requestId(): string {
  return crypto.randomUUID();
}

let csrfToken: string | null = null;

function rememberCsrf(response: SessionResponse): SessionResponse {
  csrfToken = response.csrfToken;
  return response;
}

async function ensureCsrf(): Promise<void> {
  if (csrfToken) return;
  const recover = () => fetch('/api/v1/auth/session/csrf', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'x-request-id': requestId() },
    });
  let response = await recover();
  if (response.status === 401) response = await recover();
  if (!response.ok) return problem(response, 'CSRF recovery failed');
  rememberCsrf(await response.json() as SessionResponse);
}

async function problem(response: Response, fallback: string): Promise<never> {
  if (response.status === 401) csrfToken = null;
  const value = await response.json().catch(() => null);
  if (value?.code) throw new BffProblemError(value);
  throw new Error(fallback);
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
    return problem(response, 'bff request failed');
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
    return problem(response, 'bff request failed');
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
  await ensureCsrf();
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
    if (!r.ok) return problem(r, 'login failed');
    return rememberCsrf(await r.json() as SessionResponse);
  });
}

export async function getSessionState(): Promise<SessionState> {
  const response = await fetch('/api/v1/auth/session', { credentials: 'same-origin' });
  if (!response.ok) return problem(response, 'session state failed');
  return response.json() as Promise<SessionState>;
}

export async function startGoogleLogin(): Promise<OidcStartResponse> {
  await ensureCsrf();
  const body: OidcStartRequest = { returnTarget: '/oidc/complete' };
  return post<OidcStartResponse>('/api/v1/auth/oidc/google/start', body);
}

export async function getExternalIdentityStatus(): Promise<ExternalIdentityStatus> {
  const response = await fetch('/api/v1/identity/external-identities', {
    credentials: 'same-origin',
    headers: { 'x-request-id': requestId() },
  });
  if (!response.ok) return problem(response, 'external identity status failed');
  return response.json() as Promise<ExternalIdentityStatus>;
}

export async function startGoogleLink(): Promise<OidcStartResponse> {
  await ensureCsrf();
  const body: OidcStartRequest = { returnTarget: '/security/external-identities' };
  return post<OidcStartResponse>('/api/v1/identity/external-identities/google/start', body);
}

export async function unlinkGoogleIdentity(): Promise<void> {
  await ensureCsrf();
  const response = await fetch('/api/v1/identity/external-identities/google', {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: {
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
  });
  if (!response.ok) return problem(response, 'external identity unlink failed');
  csrfToken = null;
}

export async function completeMfaAuthentication(body: MfaProof): Promise<SessionResponse> {
  await ensureCsrf();
  return rememberCsrf(await post<SessionResponse>('/api/v1/auth/mfa/complete', body));
}

export async function getMfaStatus(): Promise<MfaStatus> {
  const response = await fetch('/api/v1/identity/mfa', {
    credentials: 'same-origin',
    headers: { 'x-request-id': requestId() },
  });
  if (!response.ok) return problem(response, 'MFA status failed');
  return response.json() as Promise<MfaStatus>;
}

export async function startTotpEnrollment(
  currentProof?: MfaProof,
): Promise<TotpEnrollment> {
  await ensureCsrf();
  const body: StartTotpEnrollmentRequest = currentProof ? { currentProof } : {};
  return post<TotpEnrollment>('/api/v1/identity/mfa/totp/enrollment', body);
}

export async function confirmTotpEnrollment(
  body: ConfirmTotpEnrollmentRequest,
): Promise<RecoveryCodes> {
  await ensureCsrf();
  return post<RecoveryCodes>('/api/v1/identity/mfa/totp/enrollment/confirm', body);
}

export async function disableTotp(body: MfaProof): Promise<void> {
  await ensureCsrf();
  const response = await fetch('/api/v1/identity/mfa/totp', {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: {
      'content-type': 'application/json',
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) return problem(response, 'disable MFA failed');
}

export async function rotateRecoveryCodes(body: MfaProof): Promise<RecoveryCodes> {
  await ensureCsrf();
  return post<RecoveryCodes>('/api/v1/identity/mfa/recovery-codes/rotate', body);
}


export async function listTenants(): Promise<TenantList> {
  const r = await fetch('/api/v1/identity/tenants', { credentials: 'same-origin' });
  if (!r.ok) throw new Error('tenant list failed');
  return r.json();
}

export async function selectTenant(membershipId: string): Promise<TenantSelectionResponse> {
  await ensureCsrf();
  const body: SelectTenantRequest = { membershipId };
  const response = await post<TenantSelectionResponse>('/api/v1/identity/tenant-selection', body);
  csrfToken = response.csrfToken;
  return response;
}

async function tenantLifecycle(
  tenantId: string,
  operation: 'suspend' | 'resume' | 'restore',
): Promise<TenantLifecycleResult> {
  await ensureCsrf();
  return postWithoutBody<TenantLifecycleResult>(
    `/api/v1/identity/tenants/${tenantId}/${operation}`,
  );
}

export async function deleteTenant(tenantId: string): Promise<TenantLifecycleResult> {
  await ensureCsrf();
  const response = await fetch(`/api/v1/identity/tenants/${tenantId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: {
      'x-request-id': requestId(),
      ...(csrfToken ? { 'x-csrf-token': csrfToken } : {}),
    },
  });
  if (!response.ok) return problem(response, 'tenant deletion failed');
  const result = await response.json() as TenantLifecycleResult;
  if (result.csrfToken) csrfToken = result.csrfToken;
  return result;
}

async function invitationList(path: string): Promise<InvitationList> {
  const response = await fetch(path, { credentials: 'same-origin' });
  if (!response.ok) return problem(response, 'invitation list failed');
  return response.json() as Promise<InvitationList>;
}

export const listReceivedInvitations = () =>
  invitationList('/api/v1/identity/invitations/received');

export const listTenantInvitations = () => invitationList('/api/v1/identity/invitations');

async function invitationMutation<T>(invitationId: string, operation: string): Promise<T> {
  await ensureCsrf();
  return postWithoutBody<T>(`/api/v1/identity/invitations/${invitationId}/${operation}`);
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
  await ensureCsrf();
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
  await ensureCsrf();
  const body: VerifyContactRequest = { code };
  return post<Schemas['VerifiedResponse']>(`/api/v1/identity/contacts/${id}/verify`, body);
}

export async function resendContactVerification(id: string): Promise<AcceptedResponse> {
  await ensureCsrf();
  return postWithoutBody<AcceptedResponse>(`/api/v1/identity/contacts/${id}/resend`);
}

export async function setPrimaryContact(id: string): Promise<AcceptedResponse> {
  await ensureCsrf();
  return postWithoutBody<AcceptedResponse>(`/api/v1/identity/contacts/${id}/primary`);
}

export async function removeContact(id: string): Promise<AcceptedResponse> {
  await ensureCsrf();
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
  await ensureCsrf();
  return post<Schemas['CreatedContactResponse']>('/api/v1/identity/contacts', body);
}

export async function changePassword(body: ChangePasswordRequest): Promise<PasswordChangedResponse> {
  await ensureCsrf();
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
  completeMfaAuthentication,
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
  getMfaStatus,
  startTotpEnrollment,
  confirmTotpEnrollment,
  disableTotp,
  rotateRecoveryCodes,
  getSessionState,
  startGoogleLogin,
  getExternalIdentityStatus,
  startGoogleLink,
  unlinkGoogleIdentity,
  suspendTenant: (tenantId: string) => tenantLifecycle(tenantId, 'suspend'),
  resumeTenant: (tenantId: string) => tenantLifecycle(tenantId, 'resume'),
  restoreTenant: (tenantId: string) => tenantLifecycle(tenantId, 'restore'),
  deleteTenant,
  listReceivedInvitations,
  listTenantInvitations,
  acceptInvitation: (id: string) => invitationMutation<AcceptedInvitation>(id, 'accept'),
  declineInvitation: (id: string) => invitationMutation<InvitationState>(id, 'decline'),
  revokeInvitation: (id: string) => invitationMutation<InvitationState>(id, 'revoke'),
  reissueInvitation: (id: string) => invitationMutation<InvitationCreated>(id, 'reissue'),
};
