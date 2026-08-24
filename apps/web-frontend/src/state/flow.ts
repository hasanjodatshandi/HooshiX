export type FlowStage =
  | 'registration'
  | 'verification'
  | 'authenticated'
  | 'tenant-selection'
  | 'application';

export function canEnterStage(stage: FlowStage, state: { contact: string; authenticated: boolean; tenantId: string | null }) {
  switch (stage) {
    case 'verification':
      return Boolean(state.contact);
    case 'authenticated':
      return state.authenticated;
    case 'tenant-selection':
      return state.authenticated;
    case 'application':
      return state.authenticated && Boolean(state.tenantId);
    default:
      return true;
  }
}
