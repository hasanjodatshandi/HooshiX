import { type FormEvent, useEffect, useState } from 'react';
import {
  bffClient,
  type Conversation,
  type ConversationMessage,
  type ConversationRun,
} from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { useI18n } from '../../i18n/I18nProvider';

const ACTIVE_RUN_STATES = new Set<ConversationRun['state']>(['QUEUED', 'RUNNING']);
const POLL_INTERVAL_MS = 1_000;

export function ConversationFlow() {
  const { t } = useI18n();
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selected, setSelected] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [run, setRun] = useState<ConversationRun | null>(null);
  const [feedbackSubmitted, setFeedbackSubmitted] = useState(false);
  const [title, setTitle] = useState('');
  const [userMessage, setUserMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function loadConversations(signal?: AbortSignal) {
    const result = await bffClient.listConversations(signal ? { signal } : {});
    setConversations(result.conversations);
    setSelected((current) => {
      if (!current) return result.conversations[0] ?? null;
      return result.conversations.find((item) => item.conversationId === current.conversationId)
        ?? result.conversations[0]
        ?? null;
    });
  }

  async function loadMessages(conversationId: string, signal?: AbortSignal) {
    const result = await bffClient.listConversationMessages(
      conversationId,
      signal ? { signal } : {},
    );
    setMessages([...result.messages].sort((left, right) => left.ordinal - right.ordinal));
  }

  useEffect(() => {
    const controller = new AbortController();
    void loadConversations(controller.signal).catch((cause) => {
      if (!controller.signal.aborted) setError(getErrorMessage(cause));
    });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!selected) {
      setMessages([]);
      setRun(null);
      setFeedbackSubmitted(false);
      return;
    }
    const controller = new AbortController();
    void loadMessages(selected.conversationId, controller.signal).catch((cause) => {
      if (!controller.signal.aborted) setError(getErrorMessage(cause));
    });
    return () => controller.abort();
  }, [selected?.conversationId]);

  useEffect(() => {
    if (!run || !ACTIVE_RUN_STATES.has(run.state)) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      void bffClient.getConversationRun(run.conversationId, run.runId, {
        signal: controller.signal,
      }).then(async (next) => {
        if (!ACTIVE_RUN_STATES.has(next.state) && next.state === 'SUCCEEDED') {
          await loadMessages(next.conversationId, controller.signal);
        }
        setRun(next);
      }).catch((cause) => {
        if (!controller.signal.aborted) setError(getErrorMessage(cause));
      });
    }, POLL_INTERVAL_MS);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [run]);

  async function perform(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    const canonicalTitle = title.trim();
    if (!canonicalTitle) return;
    await perform(async () => {
      const created = await bffClient.createConversation({ title: canonicalTitle });
      setConversations((current) => [created, ...current]);
      setSelected(created);
      setTitle('');
      setRun(null);
    });
  }

  async function submitMessage(event: FormEvent) {
    event.preventDefault();
    const content = userMessage.trim();
    if (!selected || selected.lifecycle !== 'ACTIVE' || !content) return;
    await perform(async () => {
      const createdRun = await bffClient.createConversationRun(selected.conversationId, {
        userMessage: content,
      });
      setRun(createdRun);
      setFeedbackSubmitted(false);
      setUserMessage('');
      await loadMessages(selected.conversationId);
    });
  }

  async function archive() {
    if (!selected) return;
    await perform(async () => {
      const archived = await bffClient.archiveConversation(
        selected.conversationId,
        selected.version,
      );
      setConversations((current) => current.map((item) => (
        item.conversationId === archived.conversationId ? archived : item
      )));
      setSelected(archived);
      setRun(null);
    });
  }

  async function remove() {
    if (!selected) return;
    await perform(async () => {
      await bffClient.deleteConversation(selected.conversationId, selected.version);
      const remaining = conversations.filter(
        (item) => item.conversationId !== selected.conversationId,
      );
      setConversations(remaining);
      setSelected(remaining[0] ?? null);
      setRun(null);
    });
  }

  async function cancel() {
    if (!run) return;
    await perform(async () => {
      setRun(await bffClient.cancelConversationRun(run.conversationId, run.runId));
    });
  }

  async function submitFeedback(value: 'HELPFUL' | 'NOT_HELPFUL' | 'UNSAFE' | 'FACTUALLY_WRONG') {
    if (!run || run.state !== 'SUCCEEDED' || feedbackSubmitted) return;
    await perform(async () => {
      await bffClient.submitConversationRunFeedback(run.conversationId, run.runId, { value });
      setFeedbackSubmitted(true);
    });
  }

  return <main><section aria-labelledby="conversation-title">
    <h1 id="conversation-title">{t('conversations')}</h1>
    <form onSubmit={(event) => void create(event)}>
      <label htmlFor="new-conversation-title">{t('conversationTitle')}</label>
      <input id="new-conversation-title" required maxLength={120} value={title} onChange={(event) => setTitle(event.target.value)} />
      <button type="submit" disabled={busy || !title.trim()}>{t('createConversation')}</button>
    </form>

    <h2>{t('conversationList')}</h2>
    {conversations.length === 0 && <p>{t('noConversations')}</p>}
    <ul>{conversations.map((conversation) => <li key={conversation.conversationId}>
      <button type="button" disabled={busy} aria-pressed={selected?.conversationId === conversation.conversationId} onClick={() => { setSelected(conversation); setRun(null); }}>
        {conversation.title} ({conversation.lifecycle})
      </button>
    </li>)}</ul>

    {selected && <section aria-labelledby="selected-conversation-title">
      <h2 id="selected-conversation-title">{selected.title}</h2>
      <p>{t('conversationLifecycle', { state: selected.lifecycle })}</p>
      {selected.lifecycle === 'ACTIVE' && <button type="button" disabled={busy || Boolean(run && ACTIVE_RUN_STATES.has(run.state))} onClick={() => void archive()}>{t('archiveConversation')}</button>}
      <button type="button" disabled={busy || Boolean(run && ACTIVE_RUN_STATES.has(run.state))} onClick={() => void remove()}>{t('deleteConversation')}</button>

      <h3>{t('messages')}</h3>
      <ol aria-live="polite">{messages.map((message) => <li key={message.messageId}>
        <strong>{message.role === 'USER' ? t('you') : t('assistant')}</strong>
        <p style={{ whiteSpace: 'pre-wrap' }}>{message.content}</p>
      </li>)}</ol>

      {selected.lifecycle === 'ACTIVE' && <form onSubmit={(event) => void submitMessage(event)}>
        <label htmlFor="conversation-message">{t('message')}</label>
        <textarea id="conversation-message" required maxLength={16000} value={userMessage} onChange={(event) => setUserMessage(event.target.value)} />
        <button type="submit" disabled={busy || !userMessage.trim() || Boolean(run && ACTIVE_RUN_STATES.has(run.state))}>{t('sendMessage')}</button>
      </form>}

      {run && <div role="status" aria-live="polite">
        <p>{t('runState', { state: run.state })}</p>
        {run.failureCode && <p>{t('runFailure', { code: run.failureCode })}</p>}
        {ACTIVE_RUN_STATES.has(run.state) && <button type="button" disabled={busy || run.cancellationRequested} onClick={() => void cancel()}>{t('cancelRun')}</button>}
        {run.state === 'SUCCEEDED' && <fieldset disabled={busy || feedbackSubmitted}>
          <legend>{feedbackSubmitted ? t('feedbackSubmitted') : t('rateResponse')}</legend>
          <button type="button" onClick={() => void submitFeedback('HELPFUL')}>{t('feedbackHelpful')}</button>
          <button type="button" onClick={() => void submitFeedback('NOT_HELPFUL')}>{t('feedbackNotHelpful')}</button>
          <button type="button" onClick={() => void submitFeedback('UNSAFE')}>{t('feedbackUnsafe')}</button>
          <button type="button" onClick={() => void submitFeedback('FACTUALLY_WRONG')}>{t('feedbackFactuallyWrong')}</button>
        </fieldset>}
      </div>}
    </section>}
    <p role="alert">{error}</p>
  </section></main>;
}
