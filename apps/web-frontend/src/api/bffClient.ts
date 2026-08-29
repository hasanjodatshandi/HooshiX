import type { components } from './generated/schema';
import {
  BffProblemError,
  BffRequestError,
  isUnauthorizedFailure,
  parseProblem,
} from '../errors/problem';

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
export type SelfErasureAccepted = Schemas['SelfErasureAcceptedResponse'];

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
type SelfErasureRequest = Schemas['SelfErasureRequest'];

export type BffRequestOptions = {
  signal?: AbortSignal;
};

const REQUEST_TIMEOUT_MS = 3_000;

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
  const recover = () => request<SessionResponse>('/api/v1/auth/session/csrf', {
      method: 'POST',
    });
  try {
    rememberCsrf(await recover());
  } catch (error) {
    if (!isUnauthorizedFailure(error)) throw error;
    rememberCsrf(await recover());
  }
}

async function problem(response: Response): Promise<never> {
  if (response.status === 401) csrfToken = null;
  let value: unknown;
  try {
    value = await response.json();
  } catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
    value = null;
  }
  const parsed = parseProblem(value);
  if (parsed && parsed.status === response.status) throw new BffProblemError(parsed);
  throw new BffRequestError('BFF_UNEXPECTED_RESPONSE', response.status);
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  options: BffRequestOptions = {},
  expectJson = true,
): Promise<T> {
  if (options.signal?.aborted) throw new BffRequestError('BFF_REQUEST_CANCELLED');
  const controller = new AbortController();
  let timedOut = false;
  const cancel = () => controller.abort();
  options.signal?.addEventListener('abort', cancel, { once: true });
  const timeout = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(path, {
      ...init,
      signal: controller.signal,
      credentials: 'same-origin',
      headers: {
        'x-request-id': requestId(),
        ...init.headers,
      },
    });
    if (!response.ok) return await problem(response);
    if (!expectJson || response.status === 204) return undefined as T;
    try {
      return await response.json() as T;
    } catch (error) {
      if (timedOut || controller.signal.aborted) throw error;
      throw new BffRequestError('BFF_INVALID_RESPONSE', response.status);
    }
  } catch (error) {
    if (timedOut) throw new BffRequestError('BFF_REQUEST_TIMEOUT');
    if (controller.signal.aborted) throw new BffRequestError('BFF_REQUEST_CANCELLED');
    if (error instanceof BffProblemError || error instanceof BffRequestError) throw error;
    throw new BffRequestError('BFF_NETWORK_ERROR');
  } finally {
    window.clearTimeout(timeout);
    options.signal?.removeEventListener('abort', cancel);
  }
}

function requestWithoutResponse(path: string, init: RequestInit): Promise<void> {
  return request<void>(path, init, {}, false);
}

function csrfHeaders(): Record<string, string> {
  return csrfToken ? { 'x-csrf-token': csrfToken } : {};
}

async function post<T>(
  path: string,
  body: unknown,
  options: BffRequestOptions = {},
): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'content-type': 'application/json',
      ...csrfHeaders(),
    },
    body: JSON.stringify(body),
  }, options);
}

async function postWithoutBody<T>(
  path: string,
  options: BffRequestOptions = {},
): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: csrfHeaders(),
  }, options);
}


export async function bootstrapSession(
  options: BffRequestOptions = {},
): Promise<SessionResponse> {
  return rememberCsrf(await request<SessionResponse>('/api/v1/auth/session/bootstrap', {
    method: 'POST',
  }, options));
}

export async function login(
  body: Pick<LocalLoginRequest, 'contact' | 'password'>,
): Promise<SessionResponse> {
  await ensureCsrf();
  return rememberCsrf(await post<SessionResponse>(
    '/api/v1/auth/local',
    { channel: 'EMAIL', ...body },
  ));
}

export async function getSessionState(
  options: BffRequestOptions = {},
): Promise<SessionState> {
  return request<SessionState>('/api/v1/auth/session', {}, options);
}

export async function startGoogleLogin(): Promise<OidcStartResponse> {
  await ensureCsrf();
  const body: OidcStartRequest = { returnTarget: '/oidc/complete' };
  return post<OidcStartResponse>('/api/v1/auth/oidc/google/start', body);
}

export async function getExternalIdentityStatus(
  options: BffRequestOptions = {},
): Promise<ExternalIdentityStatus> {
  return request<ExternalIdentityStatus>('/api/v1/identity/external-identities', {}, options);
}

export async function startGoogleLink(): Promise<OidcStartResponse> {
  await ensureCsrf();
  const body: OidcStartRequest = { returnTarget: '/security/external-identities' };
  return post<OidcStartResponse>('/api/v1/identity/external-identities/google/start', body);
}

export async function unlinkGoogleIdentity(): Promise<void> {
  await ensureCsrf();
  await requestWithoutResponse('/api/v1/identity/external-identities/google', {
    method: 'DELETE',
    headers: csrfHeaders(),
  });
  csrfToken = null;
}

export async function completeMfaAuthentication(body: MfaProof): Promise<SessionResponse> {
  await ensureCsrf();
  return rememberCsrf(await post<SessionResponse>('/api/v1/auth/mfa/complete', body));
}

export async function getMfaStatus(
  options: BffRequestOptions = {},
): Promise<MfaStatus> {
  return request<MfaStatus>('/api/v1/identity/mfa', {}, options);
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
  await requestWithoutResponse('/api/v1/identity/mfa/totp', {
    method: 'DELETE',
    headers: {
      'content-type': 'application/json',
      ...csrfHeaders(),
    },
    body: JSON.stringify(body),
  });
}

export async function rotateRecoveryCodes(body: MfaProof): Promise<RecoveryCodes> {
  await ensureCsrf();
  return post<RecoveryCodes>('/api/v1/identity/mfa/recovery-codes/rotate', body);
}


export async function listTenants(
  options: BffRequestOptions = {},
): Promise<TenantList> {
  return request<TenantList>('/api/v1/identity/tenants', {}, options);
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
  const result = await request<TenantLifecycleResult>(`/api/v1/identity/tenants/${tenantId}`, {
    method: 'DELETE',
    headers: csrfHeaders(),
  });
  if (result.csrfToken) csrfToken = result.csrfToken;
  return result;
}

async function invitationList(
  path: string,
  options: BffRequestOptions = {},
): Promise<InvitationList> {
  return request<InvitationList>(path, {}, options);
}

export const listReceivedInvitations = (options: BffRequestOptions = {}) =>
  invitationList('/api/v1/identity/invitations/received', options);

export const listTenantInvitations = (options: BffRequestOptions = {}) =>
  invitationList('/api/v1/identity/invitations', options);

async function invitationMutation<T>(invitationId: string, operation: string): Promise<T> {
  await ensureCsrf();
  return postWithoutBody<T>(`/api/v1/identity/invitations/${invitationId}/${operation}`);
}


export async function getProfile(options: BffRequestOptions = {}): Promise<Profile> {
  return request<Profile>('/api/v1/identity/profile', {}, options);
}

export async function getContacts(options: BffRequestOptions = {}): Promise<Contact[]> {
  return request<Contact[]>('/api/v1/identity/contacts', {}, options);
}

export async function updateProfile(body: UpdateProfileRequest): Promise<AcceptedResponse> {
  await ensureCsrf();
  return request<AcceptedResponse>('/api/v1/identity/profile', {
    method: 'PUT',
    headers: {
      'content-type': 'application/json',
      ...csrfHeaders(),
    },
    body: JSON.stringify(body),
  });
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
  return request<AcceptedResponse>(`/api/v1/identity/contacts/${id}`, {
    method: 'DELETE',
    headers: csrfHeaders(),
  });
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

export async function requestSelfErasure(
  body: SelfErasureRequest,
): Promise<SelfErasureAccepted> {
  await ensureCsrf();
  const response = await post<SelfErasureAccepted>('/api/v1/identity/erasure', body);
  csrfToken = null;
  return response;
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
  requestSelfErasure,
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
