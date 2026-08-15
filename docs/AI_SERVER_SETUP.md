# 同频 Clean · AI 接入补充

## 服务端 AI 聊天

`/api/rooms/:code/chat` 可以让网页遥控器或自建客户端直接调用服务端 AI。

如果没有配置 `OPENAI_API_KEY`，服务端会返回开发模式 mock 回复，方便先测试房间链路。

## 推荐环境变量

```text
OPENAI_API_KEY=你的 API Key
AI_MODEL=gpt-4.1-mini
OPENAI_BASE_URL=https://api.openai.com/v1
AI_ENDPOINT_MODE=chat_completions
```

如果使用 OpenAI 兼容中转，把 `OPENAI_BASE_URL` 改成中转地址，例如：

```text
OPENAI_BASE_URL=https://your-proxy.example.com/v1
AI_MODEL=claude-sonnet-4-20250514
AI_ENDPOINT_MODE=chat_completions
```

`AI_ENDPOINT_MODE` 可选：

- `chat_completions`：默认，兼容大多数 OpenAI 格式中转。
- `responses`：仅用于支持 `/v1/responses` 的服务。

## MCP 与服务端 AI 的区别

- MCP 入口 `/mcp`：让 ChatGPT、Claude、RikkaHub 等客户端自己调用工具读取房间、控制播放、写听歌笔记。
- REST 聊天入口 `/api/rooms/:code/chat`：由同频服务端直接调用配置好的 AI API。

如果已经在 AI 客户端里接了 MCP，通常不需要服务端再配置 AI Key。
