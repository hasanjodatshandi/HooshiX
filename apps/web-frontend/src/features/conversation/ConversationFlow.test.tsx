import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { bffClient } from '../../api/bffClient';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ConversationFlow } from './ConversationFlow';

vi.mock('../../api/bffClient', () => ({
  bffClient: {
    listConversations: vi.fn(),
    listConversationMessages: vi.fn(),
  },
}));

const conversation = {
  conversationId: '11111111-1111-4111-8111-111111111111',
  title: 'Safety review',
  lifecycle: 'ACTIVE' as const,
  version: 1,
  createdAt: '2026-09-20T12:00:00Z',
  lastActivityAt: '2026-09-20T12:00:00Z',
};

describe('ConversationFlow', () => {
  beforeEach(() => {
    vi.mocked(bffClient.listConversations).mockResolvedValue({
      conversations: [conversation],
      nextPageToken: '',
    });
    vi.mocked(bffClient.listConversationMessages).mockResolvedValue({
      messages: [{
        messageId: '22222222-2222-4222-8222-222222222222',
        conversationId: conversation.conversationId,
        role: 'ASSISTANT',
        content: '<img src=x onerror=alert(1)>plain response',
        ordinal: 1,
        createdAt: '2026-09-20T12:01:00Z',
      }],
      nextPageToken: '',
    });
  });

  it('loads private messages and renders model content only as text', async () => {
    const { container } = render(<I18nProvider><ConversationFlow /></I18nProvider>);

    expect(await screen.findByRole('heading', { name: 'Safety review' })).toBeInTheDocument();
    expect(await screen.findByText('<img src=x onerror=alert(1)>plain response')).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
    await waitFor(() => expect(bffClient.listConversationMessages).toHaveBeenCalledWith(
      conversation.conversationId,
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    ));
  });

  it('does not mistake an unavailable conversation list for an empty account', async () => {
    vi.mocked(bffClient.listConversations).mockRejectedValueOnce(new Error('private backend detail'));
    render(<I18nProvider><ConversationFlow /></I18nProvider>);

    expect(await screen.findByRole('alert')).toHaveTextContent('UNKNOWN_ERROR');
    expect(screen.queryByText('No conversations yet')).not.toBeInTheDocument();
    expect(screen.queryByText('private backend detail')).not.toBeInTheDocument();
  });
});
