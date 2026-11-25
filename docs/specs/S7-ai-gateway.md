# S7 — AI gateway (unified providers behind one local API)

This document is the contract for an application that asks a Grom server for an
AI completion. It describes discovery, the catalogue, the two compatibility
shapes, streaming, parameter support, error codes and the security rules that
surround a provider key.

It introduces no protocol. Everything below rides the existing data plane (S5)
and the existing credential model (S6). There is no WebSocket, no MCP bridge and
no second server: the gateway is one more surface on the listener the operator
already owns.

Read S5 for the listener's own rules and S6 for authentication. This document
starts where those end.

## 1. The one-paragraph version

```
GET  /grom/v1/connect              → is there a gateway, and what does it need?
GET  /v1/ai/models                 → the callable catalogue            (ai.read)
POST /v1/ai/chat/completions       → OpenAI shape, stream or not      (ai.chat)
POST /v1/ai/v1/messages            → Anthropic shape, stream or not   (ai.chat)
POST /v1/plugins/ai/rpc            → the same gateway as a service    (ai.read/ai.chat)
```

A caller never sees a provider API key. It selects a model from the catalogue and
the server spends the operator's stored key against the provider, then normalises
the answer back to the shape the caller asked for.

## 2. Discovery

### S7-R001
`GET /grom/v1/connect` (S6-R004) carries an `ai` block whether or not the gateway
is enabled:

```json
"ai": {
  "path": "/v1/ai",
  "models": "/v1/ai/models",
  "chat": "/v1/ai/chat/completions",
  "messages": "/v1/ai/messages",
  "requires": { "models": "ai.read", "chat": "ai.chat" },
  "enabled": true,
  "configured": true,
  "listed": false,
  "items": []
}
```

`enabled` is the operator's switch, `configured` means at least one model has an
enabled provider and a stored key. Both are configuration, not a measurement: a
`configured` gateway can still fail, and the failure arrives per request.

### S7-R002
`items` is populated only for a caller holding `ai.read` (or an operator
credential), and it contains the same entries as `GET /v1/ai/models`. The
anonymous document MUST NOT contain a model id, a provider endpoint or a key.

### S7-R003
An unknown model, a disabled provider or a missing key MUST be reported before
any upstream connection is opened. A client MUST treat `503 AI_NOT_CONFIGURED`
as "the operator has not finished setting this up", not as a transport failure.

## 3. The catalogue — `GET /v1/ai/models`

### S7-R004
Requires `ai.read`. Answers `200`:

```json
{
  "ok": true,
  "defaultModel": "gpt-4o-mini",
  "models": [
    { "id": "gpt-4o-mini", "provider": "openai", "providerTitle": "OpenAI",
      "title": "GPT-4o mini", "enabled": true, "available": true,
      "capabilities": ["chat", "stream"] }
  ],
  "providers": [
    { "id": "openai", "kind": "openai", "protocol": "openai",
      "title": "OpenAI", "enabled": true, "configured": true, "modelCount": 1 }
  ]
}
```

### S7-R005
`available` is **derived from configuration**: the model is enabled, its provider
is enabled, and the provider has a stored key. It is not a health claim. A client
MUST NOT render `available` as "the provider is up"; only a successful call
proves that.

### S7-R006
The document MUST NOT contain a provider base URL, an API key (sealed or plain),
a key fingerprint, an upstream model name or a header value. It names what may be
called, not where the operator's credential is sent.

### S7-R007
The catalogue is the operator's spending limit. A model that is not in it MUST
NOT be forwarded, even when the client knows the provider's own name for it. A
client that needs another model asks the operator to add it.

## 4. Calling — OpenAI shape

### S7-R008
`POST /v1/ai/chat/completions` requires `ai.chat`. The body is the OpenAI chat
completion request. Supported fields:

| field | meaning |
|---|---|
| `model` | a catalogue id (`gpt-4o-mini`) or a qualified name (`openai/gpt-4o-mini`); omitted uses the operator's default |
| `messages` | `[{role, content}]`; roles `system`, `developer`, `user`, `assistant`; `content` is a string or text blocks |
| `stream` | boolean; `true` switches to server-sent events |
| `temperature`, `top_p` | sampling |
| `max_tokens` / `max_completion_tokens` | output bound |
| `stop` | a string or a list of strings |
| `seed`, `presence_penalty`, `frequency_penalty`, `user` | forwarded where the provider supports them |
| `n` | must be `1` |

### S7-R009
Any other field — `tools`, `tool_choice`, `response_format`, `logprobs`,
`stream_options`, `modalities`, `audio`, an unknown key — MUST be refused with
`400 AI_UNSUPPORTED_PARAM` naming the field. Silently ignoring it would let a
client believe the provider honoured a request it never received.

### S7-R010
Only text content is supported. An image, audio or tool-result block MUST be
refused with `400 AI_NOT_SUPPORTED`, never dropped.

### S7-R011
A successful answer is the OpenAI shape, with one addition:

```json
{
  "id": "chatcmpl-grom-1-42", "object": "chat.completion", "created": 1760000000,
  "model": "gpt-4o-mini",
  "choices": [{ "index": 0,
                "message": { "role": "assistant", "content": "hello" },
                "finish_reason": "stop" }],
  "usage": { "prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 4 },
  "grom": { "provider": "openai", "upstreamModel": "gpt-4o-mini", "latencyMs": 412 }
}
```

`grom` is additive and stable; a client MUST ignore it if it does not need it.
`usage` values the provider did not report are `0`, and the `grom` block is the
only place Grom adds information.

## 5. Calling — Anthropic shape

### S7-R012
`POST /v1/ai/v1/messages` (and the alias `POST /v1/ai/messages`) requires
`ai.chat`. The body is the Anthropic Messages request: `model`, `messages`,
`max_tokens`, `system`, `temperature`, `top_p`, `top_k`, `stop_sequences`,
`stream`. `max_tokens` is required by the upstream protocol; when a client omits
it the server applies its own default (1024) rather than forwarding an invalid
body.

Anthropic SDKs POST to `${baseURL}/v1/messages`, which is why the route exists in
that shape: point `baseURL` at `http://127.0.0.1:47002/v1/ai`.

### S7-R013
A successful answer is the Anthropic shape:

```json
{ "id": "chatcmpl-grom-2-7", "type": "message", "role": "assistant",
  "model": "claude-sonnet", "content": [{ "type": "text", "text": "hello" }],
  "stop_reason": "end_turn", "stop_sequence": null,
  "usage": { "input_tokens": 3, "output_tokens": 1 },
  "grom": { "provider": "anthropic", "latencyMs": 512 } }
```

## 6. Streaming

### S7-R014
With `"stream": true` the answer is `200` with
`Content-Type: text/event-stream; charset=utf-8` and `Connection: close`.

OpenAI shape:

```
data: {"id":"…","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

data: {"id":"…","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

```

Anthropic shape:

```
event: message_start
data: {"type":"message_start","message":{…}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

event: message_stop
data: {"type":"message_stop"}

```

### S7-R015
Everything that can fail before the first byte — unknown model, disabled
provider, missing key, a body over the limit — MUST be answered as JSON with its
status, *before* the stream head. A client MUST NOT receive `200` and then
discover that the request was rejected.

### S7-R016
A failure after the head has been written MUST arrive as an event: an OpenAI
`data:` frame carrying `error`, or an Anthropic `event: error`. The stream MUST
then end. The OpenAI shape ends with `data: [DONE]` even after an error so a
simple client loop terminates.

### S7-R017
When a provider closes its stream without a finish event, the gateway MUST emit a
finish (`stop`) and close. It MUST NOT invent text.

### S7-R018
The client closing the connection MUST stop the upstream read. Grom MUST NOT keep
reading a provider after the caller is gone.

## 7. Scopes

### S7-R019
`ai.read` grants `GET /v1/ai/models`, the `ai` block of `connect`, and the
`models`/`status` ops of the `ai` service. `ai.chat` grants the two completion
routes and the `chat` op.

### S7-R020
`services.call` MUST NOT grant any AI operation. The `ai` service publishes an
empty `scopedOps`; its operations are gated by `requiredScopes` instead. A
credential that may use arbitrary services therefore cannot spend the operator's
AI credit, and a client that only holds `ai.chat` does not need `services.call`.

### S7-R021
No scope grants access to a provider API key. The key is opened by the engine for
the duration of one upstream request and is never returned by any route, status,
export or error.

## 8. Errors

### S7-R022
The envelope is the standard one — `{"ok":false,"error":{"code","message",
"detail"}}` — with Grom's own codes. `detail` is the provider's own explanation,
truncated, and never contains a URL, a header or a key.

| code | status | meaning |
|---|---|---|
| `AI_DISABLED` | 503 | the operator switched the gateway off |
| `AI_NOT_CONFIGURED` | 503 | no usable provider/model/key |
| `AI_UNKNOWN_MODEL` | 404 | not in the catalogue, or disabled |
| `AI_PROVIDER_DISABLED` | 503 | the provider row is off |
| `AI_BAD_REQUEST` | 400 | the body is not a valid document |
| `AI_UNSUPPORTED_PARAM` | 400 | a field Grom does not implement |
| `AI_NOT_SUPPORTED` | 400 | a content type Grom does not forward (image, audio, tools) |
| `AI_REQUEST_TOO_LARGE` | 413 | a message or the request exceeds the bound |
| `AI_RATE_LIMITED` | 429 | this client's own AI budget |
| `AI_UPSTREAM_UNAUTHORIZED` | 502 | the provider rejected the stored key |
| `AI_UPSTREAM_RATE_LIMITED` | 429 | the provider is throttling; `Retry-After` when it said so |
| `AI_UPSTREAM_BAD_REQUEST` | 400 | the provider refused the request |
| `AI_MODEL_NOT_FOUND` | 404 | the provider does not know the model |
| `AI_UPSTREAM_ERROR` | 502 | the provider reported a server error |
| `AI_TIMEOUT` | 504 | the provider did not answer in time |
| `AI_UNREACHABLE` | 502 | the endpoint refused the connection |
| `AI_BAD_RESPONSE` | 502 | the provider answered something that is not its own shape |
| `SCOPE_REQUIRED` | 403 | this credential lacks `ai.read`/`ai.chat` |

### S7-R023
A 4xx from the provider is mapped to its own code and MUST NOT be reported as
`AI_UNREACHABLE`: "the key is wrong" and "the network is down" need different
advice.

## 9. Limits

### S7-R024
The bounds are fixed and enforced before a socket is opened: 64 messages per
request, 32 000 characters per message, 64 KiB per request body (the data plane's
own cap), 512 KiB per upstream response, 64 KiB per stream event, 20 AI requests
per minute per client, and a provider timeout between 1 s and 120 s (default
60 s).

## 10. Reference client

`app.grom.sdk.connect.GromConnectClient` implements this document:

```kotlin
val client = GromConnectClient("http://127.0.0.1:47002")
val credential = GromCredential.Key(mySigner)   // holds `ai.read` + `ai.chat`

val catalog = client.aiCatalog(credential)
val model = catalog.preferred()?.id ?: error("the operator offers no model")

val reply = client.aiChat(credential, "Summarise this in one line.", model = model)
println(reply.text)

client.aiChatStream(credential, "Count to five.", model = model) { delta ->
    print(delta)   // arrives as the provider produces it
}
```

The SDK adds no dependency, never holds a key and reports the server's `AI_*`
codes as `GromConnectException.code`.

## 11. Out of scope

* **Tools, function calling, images, audio, embeddings** — not implemented. A
  request that asks for them is refused by name (S7-R009, S7-R010).
* **Provider-side prompt caching, batches, fine-tunes** — provider-specific
  features are not part of the unified surface.
* **MCP** — not implemented. The service surface (`/v1/plugins/ai/rpc`) already
  describes and calls the same operations; an MCP adapter can be a separate
  bridge when a concrete MCP-only client appears.
* **Key rotation, usage accounting and billing** — the operator's own provider
  console remains the source of truth for spend.
* **A cloud relay** — D70, S1-R004. The gateway talks to the endpoint the
  operator typed, from the phone.
