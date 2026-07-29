const OPENAI_RESPONSES_URL = 'https://api.openai.com/v1/responses';
const DEFAULT_MODEL = 'gpt-4.1-mini';

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

const apiKey = (): string => {
  return process.env.OPENAI_API_KEY?.trim() ?? '';
};

const model = (): string => process.env.AI_MODEL?.trim() || DEFAULT_MODEL;

const userMessageFromPrompt = (prompt: string): string => {
  const marker = '用户消息：';
  const index = prompt.lastIndexOf(marker);
  if (index === -1) return prompt;
  return prompt.slice(index + marker.length).trim();
};

const mockReply = (prompt: string): string => {
  const message = userMessageFromPrompt(prompt).slice(0, 500);
  return `共听开发模式已收到你的消息：${message}。当前还未连接真实 AI。`;
};

const extractOutputText = (body: unknown): string => {
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

export async function askAI(prompt: string): Promise<string> {
  const cleanPrompt = prompt.trim();
  if (!cleanPrompt) throw new AiRequestError('prompt is required before calling askAI.');
  const key = apiKey();
  if (!key) return mockReply(cleanPrompt);

  const response = await fetch(OPENAI_RESPONSES_URL, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${key}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify({
      model: model(),
      input: cleanPrompt
    })
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new AiRequestError(`OpenAI request failed with HTTP ${response.status}: ${detail.slice(0, 500)}`);
  }

  const body: unknown = await response.json();
  const text = extractOutputText(body);
  if (!text) throw new AiRequestError('OpenAI response did not include output text.');
  return text;
}
