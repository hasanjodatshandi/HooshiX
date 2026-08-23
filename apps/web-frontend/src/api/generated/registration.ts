export type RegistrationChannel = 'EMAIL' | 'PHONE';
export type RegistrationLocale = 'fa' | 'en';

export interface RegisterRequest {
  channel: RegistrationChannel;
  contact: string;
  password: string;
  locale: RegistrationLocale;
  firstName: string;
  lastName: string;
  fatherName?: string;
}

export interface ResendRequest {
  channel: RegistrationChannel;
  contact: string;
}

export interface ConfirmRequest {
  channel: RegistrationChannel;
  contact: string;
  code: string;
}

export interface AcceptedResponse { accepted: boolean }
export interface ConfirmedResponse { confirmed: boolean }
