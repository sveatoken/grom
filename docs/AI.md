# AI gateway

Grom can answer AI requests for the applications you trust, using **your own**
provider accounts. The phone holds the key, you choose the models, and a
connected app sees a catalogue — never a credential.

```
  app (scoped key: ai.read + ai.chat)
        │  POST /v1/ai/chat/completions
        ▼
  Grom data plane (127.0.0.1:47002 or the LAN address you chose)
        │  one of three protocols, the key you stored
        ▼
  OpenAI · Anthropic · Gemini · DeepSeek · Qwen · Kimi · OpenRouter · anything
```

There is no account, no relay and no Grom cloud. The gateway is one more feature
of the server that is already running on this phone (D94).

## What it is not

* **Not a proxy for arbitrary URLs.** A provider is a saved row with an
  operator-typed endpoint; a request selects a model from the catalogue, never a
  host. A model that is not in the catalogue is refused.
* **Not a key store for apps.** `ai.read` and `ai.chat` never return a key,
  sealed or plain, in a status, an export, a header or an error.
* **Not a background service.** Nothing polls. A provider's lamp changes only
  when you press **Test** or when an app makes a call.
* **Not a billing surface.** Spend is whatever your provider console says.

## Set it up

1. **Public access** → switch the data plane on and pick `Loopback` (same phone)
   or `LAN`/a tunnel (another device). Password or key auth both work; a scoped
   app should use a key.
2. **Menu → AI gateway** → **AI gateway enabled**.
3. **Add provider**: pick a kind (`openai`, `anthropic`, `gemini`, `deepseek`,
   `qwen`, `kimi`, `openrouter`, `custom`), paste the API key, and change the base
   URL only if you use a gateway or a local runtime. An empty URL means the
   provider's own endpoint.
4. **Add model**: a short id the apps will ask for (`gpt-4o-mini`), the provider
   it belongs to, and the provider's own model name (empty means "same as the id").
   Mark one as the default if apps may omit `model`.
5. **Test** on the provider row. It sends a one-token request and reports what
   came back — the only thing that proves the key and the endpoint work.
6. On the other device: **Devices → Trust a key**, paste the app's public key and
   tick **See the AI models this device offers** (`ai.read`) and, if it may spend,
   **Send AI requests through this device's keys** (`ai.chat`).

## Providers and protocols

Three wire protocols cover every provider that matters, and they are the whole
extension mechanism — a new vendor is a `custom` row, not an app update.

| kind | protocol | endpoint used |
|---|---|---|
| `openai` | OpenAI | `https://api.openai.com/v1/chat/completions` |
| `deepseek` | OpenAI | `https://api.deepseek.com/v1/chat/completions` |
| `qwen` | OpenAI | DashScope compatible mode |
| `kimi` | OpenAI | Moonshot |
| `openrouter` | OpenAI | OpenRouter |
| `anthropic` | Anthropic | `https://api.anthropic.com/v1/messages` |
| `gemini` | Gemini | `generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` |
| `custom` | any of the three | whatever you type |

A `custom` row with `protocol = anthropic` is how you front an internal gateway
that speaks the Anthropic shape. `http://` is accepted **only** for
`127.0.0.1`, `localhost` or `::1`, so a local llama.cpp, Ollama or LM Studio
works while a remote API key can never travel in clear text.

## What an app gets

```
GET  /v1/ai/models              ai.read    catalogue: ids, providers, availability
POST /v1/ai/chat/completions    ai.chat    OpenAI shape, streaming or not
POST /v1/ai/v1/messages         ai.chat    Anthropic shape (SDK-compatible)
POST /v1/plugins/ai/rpc         ai.*       the same gateway as a service call
```

`GET /grom/v1/connect` carries an `ai` block so an app can tell — before it has a
credential — that this device has a gateway and which scopes it needs.

The full contract is [S7](specs/S7-ai-gateway.md). The reference client is
`app.grom.sdk.connect.GromConnectClient` (`aiCatalog`, `aiChat`, `aiChatStream`),
and `examples/connect` is the compiled example.

### Drop-in with vendor SDKs

```
OpenAI SDK:    base_url = "http://127.0.0.1:47002/v1/ai"
Anthropic SDK: base_url = "http://127.0.0.1:47002/v1/ai"   # POSTs /v1/messages
```

Both need a credential the SDK can send on every request. A vendor SDK cannot
sign Grom's Ed25519 requests, so it must use a **password session token** — which
the server treats as an operator credential, with everything that implies. On a
phone you do not fully control, prefer the Grom SDK with a scoped key over
handing a vendor SDK an operator token.

## Security

| Rule | Where |
|---|---|
| the key is sealed with the Android keystore, never written in plaintext | `AiStore` + `SecretBox` (D40, D44) |
| the key is never projected: operator view says "key stored", public view says nothing | `toOperatorJson` / `toPublicJson` (D95) |
| `ai.read` and `ai.chat` are separate grants, and `services.call` grants neither | `requiredScopes` (D94) |
| the endpoint must be `https`, or `http` on loopback | `validateBaseUrl` (D95) |
| redirects are never followed, so a `Location` cannot move the key | `AiHttpTransport` |
| upstream error text is truncated and stripped of URL and key | `AiErrors` |
| no account, no relay, no third-party call except the provider you configured | S1-R048 |

**Grom does not encrypt the hop to the provider beyond what the provider's TLS
does.** The data plane between the app and this phone is plain HTTP (D47): use
loopback, your LAN, or a tunnel.

## Troubleshooting

| What you see | What it means |
|---|---|
| `AI_DISABLED` | the gateway switch is off |
| `AI_NOT_CONFIGURED` | no model with an enabled provider and a stored key |
| `AI_UNKNOWN_MODEL` | the model is not in the catalogue, or it is disabled |
| `AI_PROVIDER_DISABLED` | the model's provider row is switched off |
| `AI_RATE_LIMITED` | this app used its 20 requests for the minute |
| `AI_UNSUPPORTED_PARAM` | the request asked for a field Grom does not forward (tools, images, audio) |
| `AI_UPSTREAM_UNAUTHORIZED` | the provider rejected the key: re-paste it |
| `AI_UPSTREAM_RATE_LIMITED` | the provider is throttling this key; wait `Retry-After` |
| `AI_MODEL_NOT_FOUND` | this provider does not offer that upstream name |
| `AI_TIMEOUT` | the endpoint is slow or wrong; a local model may need a longer timeout |
| `AI_UNREACHABLE` | the host/port is wrong, or the device has no route to it |
| `SCOPE_REQUIRED` | the app's key lacks `ai.read`/`ai.chat` on this phone |
| a model shows "not usable yet" | its provider is disabled, or has no key |

## Limits

20 AI requests per minute per client; 64 messages and 32 000 characters per
message; 64 KiB request, 512 KiB upstream response; provider timeout 1–120 s
(default 60 s). All of them are checked before a connection is opened.

## Verifying that a request really reaches the provider

The JVM suite proves the wire format against a **real loopback socket**: the
gateway writes through the same `AiHttpTransport` the phone uses, so a wrong
header, URL or body framing fails the build. That test cannot prove a vendor
accepts the request — that needs a vendor key.

`AiGatewayIntegrationTest.live upstream call` does exactly one real call, and it
is **skipped**, never faked, unless the operator exports all three variables:

```bash
export GROM_AI_LIVE_BASE_URL=https://api.deepseek.com
export GROM_AI_LIVE_API_KEY=...            # never committed
export GROM_AI_LIVE_MODEL=deepseek-chat
export GROM_AI_LIVE_PROTOCOL=openai        # openai (default), anthropic or gemini
/workspace/bright-turing/bin/gradle-mem :engine:testDebugUnitTest \
  --tests 'app.grom.server.superserver.plugins.ai.AiGatewayIntegrationTest'
```

A pass means the provider accepted the sealed key, the endpoint and the body.
An `AI_UPSTREAM_UNAUTHORIZED` means the key was rejected; `AI_UNREACHABLE` means
the phone (or this machine) has no route. The key is read from the environment
and never written to the repository.

## Boundaries

* **Availability is configuration.** The catalogue says a model is *callable*,
  not that the provider is reachable; only a call or **Test** proves that.
* **Nothing runs in the background**, so no provider is probed while the screen
  is closed.
* **Streaming stops when the caller leaves** — the upstream read is dropped.
* **No tools, images, audio or embeddings** yet: a request that asks for them is
  refused with the field's name, never silently narrowed.
* **The phone's network is the phone's network**: a metered connection is used
  for every call the app makes through the gateway.
