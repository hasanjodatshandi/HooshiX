import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import * as actions from '../../state/appActions';

export function RegistrationFlow() {
  const { state: appState, dispatch } = useAppState();
  const contact = appState.contact;
  const status = appState.registrationStatus;
  const error = appState.lastError ?? '';


  async function submit() {
    dispatch(actions.clearError());
    dispatch(actions.registrationRequestStarted());
    try {
      await bffClient.register({
      channel: 'EMAIL',
      contact,
      password: '',
      locale: 'fa',
      firstName: '',
      lastName: '',
      });
    dispatch(actions.registrationSucceeded());
    navigate('/verify');
    } catch (e) {
      dispatch(actions.registrationFailed(getErrorMessage(e)));
    }
  }

  return <section><h2>Registration</h2><input value={contact} onChange={(e) => dispatch(actions.registrationStarted(e.target.value))} /><button onClick={submit}>Continue</button><p>{status}</p><p>{error}</p></section>;
}
