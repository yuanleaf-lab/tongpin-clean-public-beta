const DEFAULT_BASE_URL = 'https://api.openai.com/v1';
const DEFAULT_MODEL = 'gpt-4.1-mini';

type AiEndpointMode = 'chat_completions' | 'responses';

export class AiConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AiConfigurationError';
  }
}

export class AiRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AiRequestError';
  }
}

const apiKey = (): string => process.env.OPENAI_API_KEY?.trim() ?? '';

const model = (): string => process.env.AI_MODEL?.trim() || DEFAULT_MODEL;

const endpointMode = (): AiEndpointMode => {
  const value = process.env.AI_ENDPOINT_MODE?.trim().toLowerCase();
  return value === 'responses' ? 'responses' : 'chat_completions';
};

const baseUrl = (): string => {
  const value = process.env.OPENAI_BASE_URL?.trim() || process.env.AI_BASE_URL?.trim() || DEFAULT_BASE_URL;
  return value.replace(/\/+$/, '');
};

const endpointUrl = (): string => {
  const base = baseUrl();
  return endpointMode() === 'responses' ? `${base}/responses` : `${base}/chat/completions`;
};

const userMessageFromPrompt = (prompt: string): string => {
  const marker = '\u7528\u6237\u6d88\u606f\uff1a';
  const index = prompt.lastIndexOf(marker);
  if (index === -1) return prompt;
  return prompt.slice(index + marker.length).trim();
};

const mockReply = (prompt: string): string => {
  const message = userMessageFromPrompt(prompt).slice(0, 500);
  return `\u5171\u542c\u5f00\u53d1\u6a21\u5f0f\u5df2\u6536\u5230\u4f60\u7684\u6d88\u606f\uff1a${message}\u3002\u5f53\u524d\u8fd8\u672a\u8fde\u63a5\u771f\u5b9e AI\u3002`;
};

const extractResponsesText = (body: unknown): string => {
  if (!body || typeof body !== 'object') return '';
  const value = body as {
    output_text?: unknown;
    output?: Array<{
      content?: Array<{
        type?: string;
        text?: unknown;
      }>;
    }>;
  };
  if (typeof value.output_text === 'string') return value.output_text.trim();
  const text = value.output
    ?.flatMap(item => item.content ?? [])
    .filter(item => item.type === 'output_text' && typeof item.text === 'string')
    .map(item => item.text)
    .join('');
  return text?.trim() ?? '';
};

const extractChatCompletionsText = (body: unknown): string => {
  if (!body || typeof body !== 'object') return '';
  const value = body as {
    choices?: Array<{
      message?: { content?: unknown };
      text?: unknown;
    }>;
  };
  const first = value.choices?.[0];
  const content = first?.message?.content ?? first?.text;
  if (typeof content === 'string') return content.trim();
  if (Array.isArray(content)) {
    return content
      .map(part => {
        if (typeof part === 'string') return part;
        if (part && typeof part === 'object' && 'text' in part && typeof part.text === 'string') return part.text;
        return '';
      })
      .join('')
      .trim();
  }
  return '';
};

const extractOutputText = (body: unknown, mode: AiEndpointMode): string => {
  return mode === 'responses' ? extractResponsesText(body) : extractChatCompletionsText(body);
};

const buildRequestBody = (prompt: string, mode: AiEndpointMode): Record<string, unknown> => {
  if (mode === 'responses') {
    return {
      model: model(),
      input: prompt
    };
  }

  return {
    model: model(),
    messages: [
      {
        role: 'system',
        content: '\u4f60\u662f\u540c\u9891 Clean \u7684\u5171\u542c\u804a\u5929\u52a9\u624b\u3002\u56de\u590d\u81ea\u7136\u3001\u7b80\u6d01\u3001\u6709\u966a\u4f34\u611f\uff0c\u4e0d\u7f16\u9020\u672a\u63d0\u4f9b\u7684\u4fe1\u606f\u3002'
      },
      {
        role: 'user',
        content: prompt
      }
    ]
  };
};

export async function askAI(prompt: string): Promise<string> {
  const cleanPrompt = prompt.trim();
  if (!cleanPrompt) throw new AiRequestError('prompt is required before calling askAI.');
  const key = apiKey();
  if (!key) return mockReply(cleanPrompt);

  const mode = endpointMode();
  const response = await fetch(endpointUrl(), {
    method: 'POST',
    headers: {
      authorization: `Bearer ${key}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify(buildRequestBody(cleanPrompt, mode))
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new AiRequestError(`AI request failed with HTTP ${response.status}: ${detail.slice(0, 500)}`);
  }

  const body: unknown = await response.json();
  const text = extractOutputText(body, mode);
  if (!text) throw new AiRequestError('AI response did not include output text.');
  return text;
}
