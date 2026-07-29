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
  const value = process.env.OPENAI_API_KEY?.trim();
  if (!value) {
    throw new AiConfigurationError('OPENAI_API_KEY is required before calling askAI.');
  }
  return value;
};

const model = (): string => process.env.AI_MODEL?.trim() || DEFAULT_MODEL;

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

  const response = await fetch(OPENAI_RESPONSES_URL, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${apiKey()}`,
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
