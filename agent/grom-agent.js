#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
/*
 * Grom agent runtime.
 *
 * The Android supervisor launches this file with:
 *
 *   <files>/node/libnode.so \
 *     <files>/node/grom-agent.js --port <p> --parent-pid <pid>
 *
 * No engine switch: the agent is CommonJS, which every pinned Node LTS loads
 * by default. An experimental flag the runtime does not know is a "bad option"
 * and the process exits before GROM_AGENT_READY (D98).
 *
 * argv never carries a secret. The bearer token and the configured facade port
 * arrive on stdin as one JSON object followed by a newline, after which the
 * supervisor closes the pipe (S3-R007/S3-R008). The agent binds its own HTTP
 * listener to 127.0.0.1 on an ephemeral port; the --port value belongs to the
 * Kotlin loopback facade and is reported in /status only, never bound here.
 *
 * Stdout is a protocol channel, not a log channel:
 *   GROM_READY <pid>
 *   GROM_AGENT_READY <pid> <boundPort>
 * Everything else -- including the sqlite degradation warning -- goes to
 * stderr, with the bearer token redacted.
 */

'use strict';

const http = require('node:http');
const crypto = require('node:crypto');

const MAX_STDIN_BYTES = 16 * 1024;
const MAX_BODY_BYTES = 64 * 1024;
const MAX_HEADER_COUNT = 64;
const REQUEST_TIMEOUT_MS = 10_000;
const HEADERS_TIMEOUT_MS = 8_000;
const KEEP_ALIVE_TIMEOUT_MS = 5_000;
const PARENT_POLL_MS = 1_000;
const STOP_GRACE_MS = 1_000;

const OPS = Object.freeze(['ping', 'echo', 'status', 'kv.get', 'kv.put']);
const STORAGE_UNAVAILABLE_MESSAGE =
  'node:sqlite is not available in this runtime';

const ROUTES = Object.freeze({
  '/healthz': Object.freeze({ method: 'GET', auth: false }),
  '/status': Object.freeze({ method: 'GET', auth: true }),
  '/exec': Object.freeze({ method: 'POST', auth: true }),
});

/* ------------------------------------------------------------------------- *
 * Small utilities
 * ------------------------------------------------------------------------- */

/** Parse a decimal integer in [min, max]; anything else becomes null. */
function parseInteger(value, min, max) {
  let parsed;
  if (typeof value === 'number') {
    parsed = value;
  } else if (typeof value === 'string' && /^[0-9]+$/.test(value)) {
    parsed = Number(value);
  } else {
    return null;
  }
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) return null;
  return parsed;
}

/**
 * Parse the launcher's argv. Unknown arguments are ignored on purpose: the
 * contract says argv has no secrets and the supervisor may grow flags later.
 */
function parseArgs(argv) {
  const result = { port: null, parentPid: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    let name = null;
    let raw = null;
    if (arg === '--port' || arg === '--parent-pid') {
      name = arg;
      raw = argv[i + 1];
      i += 1;
    } else if (arg.startsWith('--port=')) {
      name = '--port';
      raw = arg.slice('--port='.length);
    } else if (arg.startsWith('--parent-pid=')) {
      name = '--parent-pid';
      raw = arg.slice('--parent-pid='.length);
    }
    if (name === '--port') result.port = parseInteger(raw, 0, 65535);
    if (name === '--parent-pid') result.parentPid = parseInteger(raw, 1, Number.MAX_SAFE_INTEGER);
  }
  return result;
}

/**
 * Validate the one JSON object received on stdin.
 *
 * Deliberately never returns the raw text or a JSON.parse message: a parser
 * message can quote attacker-controlled input, and the input contains the
 * bearer token.
 */
function parseConfig(text) {
  if (typeof text !== 'string') return { ok: false, reason: 'invalid-config' };

  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { ok: false, reason: 'invalid-config' };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, reason: 'invalid-config' };
  }

  const token = parsed.token;
  if (typeof token !== 'string' || token.length === 0) {
    return { ok: false, reason: 'invalid-config' };
  }

  const port = parseInteger(parsed.port, 0, 65535);
  if (port === null) return { ok: false, reason: 'invalid-config' };

  const logLevel = parsed.logLevel === undefined ? 'info' : parsed.logLevel;
  if (typeof logLevel !== 'string' || logLevel.length === 0 || logLevel.length > 32) {
    return { ok: false, reason: 'invalid-config' };
  }

  return { ok: true, config: { token, port, logLevel } };
}

/**
 * Read stdin until EOF, capped at maxBytes. Oversized input is rejected as
 * soon as the cap is crossed; the stream is drained and no more bytes are
 * retained.
 */
function readStdinConfig(stream, maxBytes = MAX_STDIN_BYTES) {
  return new Promise((resolve) => {
    const chunks = [];
    let total = 0;
    let settled = false;

    const cleanup = () => {
      stream.removeListener('data', onData);
      stream.removeListener('end', onEnd);
      stream.removeListener('error', onError);
    };
    const finish = (result) => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(result);
    };

    function onData(chunk) {
      if (settled) return;
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk), 'utf8');
      total += buffer.length;
      if (total > maxBytes) {
        finish({ ok: false, reason: 'config-too-large' });
        if (typeof stream.resume === 'function') stream.resume();
        return;
      }
      chunks.push(buffer);
    }
    function onEnd() {
      if (settled) return;
      const text = Buffer.concat(chunks).toString('utf8').trim();
      finish(parseConfig(text));
    }
    function onError() {
      finish({ ok: false, reason: 'stdin-error' });
    }

    stream.on('data', onData);
    stream.on('end', onEnd);
    stream.on('error', onError);
    if (typeof stream.resume === 'function') stream.resume();
  });
}

/** The production loader; tests inject an alternative through createAgent. */
function defaultSqliteLoader() {
  // eslint-disable-next-line global-require
  return require('node:sqlite');
}

function drainRequest(req) {
  try {
    req.resume();
  } catch {
    /* the socket may already be gone */
  }
}

/* ------------------------------------------------------------------------- *
 * Agent factory
 * ------------------------------------------------------------------------- */

/**
 * Build an agent instance without touching the CLI protocol.
 *
 * options:
 *   config       parsed {token, port, logLevel} (required)
 *   parentPid    --parent-pid, or null to disable the death watch
 *   sqliteLoader () => module, injectable so tests can simulate a runtime
 *                without node:sqlite without spawning a different Node binary
 *   stdout       readiness sink (process.stdout in production)
 *   stderr       log sink (process.stderr in production)
 *   onParentGone callback invoked once when the parent disappears
 */
function createAgent(options = {}) {
  const config = options.config;
  if (!config || typeof config.token !== 'string') {
    throw new TypeError('createAgent requires config.token');
  }

  const stdout = options.stdout || process.stdout;
  const stderr = options.stderr || process.stderr;
  const sqliteLoader =
    typeof options.sqliteLoader === 'function' ? options.sqliteLoader : defaultSqliteLoader;
  const parentPid =
    Number.isSafeInteger(options.parentPid) && options.parentPid > 0 ? options.parentPid : null;
  const onParentGone =
    typeof options.onParentGone === 'function' ? options.onParentGone : null;

  const startedAt = Date.now();
  const state = {
    server: null,
    storage: null,
    port: null,
    parentTimer: null,
    stopPromise: null,
    stopping: false,
  };

  /** Redact the bearer token from any value that might reach a log sink. */
  const redact = (value) => {
    const text = String(value);
    if (!config.token) return text;
    return text.split(config.token).join('\u00abredacted\u00bb');
  };

  /**
   * Log to stderr only. Warnings and errors are never suppressed: the
   * node:sqlite degradation warning must appear even when logLevel is quiet.
   */
  const log = (level, message) => {
    if (level === 'debug' && config.logLevel !== 'debug') return;
    if (level === 'info' && config.logLevel !== 'debug' && config.logLevel !== 'info') return;
    try {
      stderr.write(`[grom-agent] ${level}: ${redact(message)}\n`);
    } catch {
      /* logging must never take the agent down */
    }
  };

  const sqliteStatus = () => {
    if (!state.storage || !state.storage.status) {
      return { available: false, backend: 'memory' };
    }
    return {
      available: state.storage.status.available === true,
      backend: state.storage.status.backend === 'node:sqlite' ? 'node:sqlite' : 'memory',
    };
  };

  /**
   * Open the key/value store.
   *
   * The database is deliberately :memory: rather than $HOME/grom/agent.db.
   * On Android HOME is app-private, but it can be read-only or replaced across
   * reinstalls, and the supervisor already owns durable state (the audit store
   * and preferences). Keeping the agent's KV session-scoped avoids a hidden
   * write path, avoids cross-test contamination, and makes the missing-sqlite
   * state deterministic. The contract permits either location.
   */
  function initStorage() {
    let module;
    try {
      module = sqliteLoader();
    } catch {
      log('warn', 'node:sqlite unavailable; kv.* will answer STORAGE_UNAVAILABLE');
      return { status: { available: false, backend: 'memory' }, get: null, put: null, close: null };
    }

    if (!module || typeof module.DatabaseSync !== 'function') {
      log('warn', 'node:sqlite unavailable; kv.* will answer STORAGE_UNAVAILABLE');
      return { status: { available: false, backend: 'memory' }, get: null, put: null, close: null };
    }

    let db = null;
    try {
      db = new module.DatabaseSync(':memory:');
      db.exec('CREATE TABLE IF NOT EXISTS kv(key TEXT PRIMARY KEY, value TEXT NOT NULL)');
      return {
        status: { available: true, backend: 'node:sqlite' },
        get(key) {
          const row = db.prepare('SELECT value FROM kv WHERE key = ?').get(key);
          return row === undefined ? null : row.value;
        },
        put(key, value) {
          db.prepare('INSERT OR REPLACE INTO kv(key, value) VALUES (?, ?)').run(key, value);
        },
        close() {
          try {
            db.close();
          } catch {
            /* already closed */
          }
        },
      };
    } catch {
      if (db) {
        try {
          db.close();
        } catch {
          /* ignore */
        }
      }
      log('warn', 'node:sqlite present but unusable; kv.* will answer STORAGE_UNAVAILABLE');
      return { status: { available: false, backend: 'memory' }, get: null, put: null, close: null };
    }
  }

  function healthPayload() {
    return {
      ok: true,
      pid: process.pid,
      uptimeMs: Date.now() - startedAt,
      ops: OPS.slice(),
      sqlite: sqliteStatus(),
    };
  }

  function statusPayload() {
    return {
      pid: process.pid,
      uptimeMs: Date.now() - startedAt,
      sqlite: sqliteStatus(),
      ops: OPS.slice(),
      parentPid,
      nodeVersion: process.version,
      config: { port: config.port, logLevel: config.logLevel },
    };
  }

  function authorized(req) {
    const header = req.headers.authorization;
    if (typeof header !== 'string') return false;
    const match = /^Bearer[ \t]+(\S+)[ \t]*$/i.exec(header);
    if (!match) return false;
    const given = Buffer.from(match[1], 'utf8');
    const expected = Buffer.from(config.token, 'utf8');
    if (given.length !== expected.length) return false;
    try {
      return crypto.timingSafeEqual(given, expected);
    } catch {
      return false;
    }
  }

  function sendJson(res, status, payload, extraHeaders) {
    if (!res || res.writableEnded || res.destroyed) return;
    let body;
    try {
      body = JSON.stringify(payload);
    } catch {
      body = '{"ok":false,"code":"INTERNAL_ERROR","message":"internal error"}';
      status = 500;
    }
    const headers = {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Length': Buffer.byteLength(body),
      ...(extraHeaders || {}),
    };
    try {
      res.writeHead(status, headers);
      res.end(body);
    } catch {
      try {
        res.destroy();
      } catch {
        /* ignore */
      }
    }
  }

  /**
   * Read a request body with a hard cap. Returns {ok,text} or
   * {ok:false,tooLarge|empty}. Bytes beyond the cap are discarded, never
   * buffered.
   */
  function readBody(req) {
    return new Promise((resolve) => {
      let settled = false;
      const chunks = [];
      let size = 0;

      const cleanup = () => {
        req.removeListener('data', onData);
        req.removeListener('end', onEnd);
        req.removeListener('error', onError);
      };
      const finish = (result) => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve(result);
      };

      const declared = req.headers['content-length'];
      if (declared !== undefined) {
        const n = Number(declared);
        if (Number.isFinite(n) && n > MAX_BODY_BYTES) {
          finish({ ok: false, tooLarge: true });
          drainRequest(req);
          return;
        }
      }

      function onData(chunk) {
        if (settled) return;
        const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk), 'utf8');
        size += buffer.length;
        if (size > MAX_BODY_BYTES) {
          finish({ ok: false, tooLarge: true });
          drainRequest(req);
          return;
        }
        chunks.push(buffer);
      }
      function onEnd() {
        if (settled) return;
        if (size === 0) {
          finish({ ok: false, empty: true });
          return;
        }
        finish({ ok: true, text: Buffer.concat(chunks).toString('utf8') });
      }
      function onError() {
        finish({ ok: false, empty: true });
      }

      req.on('data', onData);
      req.on('end', onEnd);
      req.on('error', onError);
    });
  }

  function execResult(req, res, op, args) {
    if (op === 'ping') {
      sendJson(res, 200, {
        op: 'ping',
        ok: true,
        result: 'pong',
        pid: process.pid,
        uptimeMs: Date.now() - startedAt,
      });
      return;
    }

    if (op === 'echo') {
      let echoed = args;
      if (args && typeof args === 'object' && !Array.isArray(args)) {
        echoed = Object.prototype.hasOwnProperty.call(args, 'echo') ? args.echo : args;
      }
      sendJson(res, 200, { op: 'echo', ok: true, result: echoed });
      return;
    }

    if (op === 'status') {
      sendJson(res, 200, { op: 'status', ok: true, result: statusPayload() });
      return;
    }

    // kv.get / kv.put. A missing sqlite runtime is a truthful 503, never a
    // silent in-memory fallback.
    if (!state.storage || !state.storage.status || state.storage.status.available !== true) {
      sendJson(res, 503, {
        ok: false,
        code: 'STORAGE_UNAVAILABLE',
        message: STORAGE_UNAVAILABLE_MESSAGE,
      });
      return;
    }

    if (!args || typeof args !== 'object' || Array.isArray(args)) {
      sendJson(res, 400, {
        ok: false,
        code: 'INVALID_ARGS',
        message: 'args must be an object',
      });
      return;
    }

    const key = args.key;
    if (typeof key !== 'string' || key.length === 0 || key.length > 1024) {
      sendJson(res, 400, {
        ok: false,
        code: 'INVALID_ARGS',
        message: 'args.key must be a non-empty string',
      });
      return;
    }

    try {
      if (op === 'kv.put') {
        if (typeof args.value !== 'string') {
          sendJson(res, 400, {
            ok: false,
            code: 'INVALID_ARGS',
            message: 'args.value must be a string',
          });
          return;
        }
        state.storage.put(key, args.value);
        sendJson(res, 200, {
          op: 'kv.put',
          ok: true,
          result: { key, stored: true },
        });
        return;
      }

      const value = state.storage.get(key);
      sendJson(res, 200, {
        op: 'kv.get',
        ok: true,
        result: { key, found: value !== null, value },
      });
    } catch {
      sendJson(res, 500, {
        ok: false,
        code: 'STORAGE_ERROR',
        message: 'storage operation failed',
      });
    }
  }

  async function handleExec(req, res) {
    const body = await readBody(req);
    if (!body.ok) {
      if (body.tooLarge) {
        sendJson(res, 413, {
          ok: false,
          code: 'PAYLOAD_TOO_LARGE',
          message: `request body exceeds ${MAX_BODY_BYTES} bytes`,
        });
      } else {
        sendJson(res, 400, {
          ok: false,
          code: 'BAD_REQUEST',
          message: 'request body is required',
        });
      }
      return;
    }

    let payload;
    try {
      payload = JSON.parse(body.text);
    } catch {
      sendJson(res, 400, {
        ok: false,
        code: 'BAD_REQUEST',
        message: 'request body must be a JSON object',
      });
      return;
    }
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
      sendJson(res, 400, {
        ok: false,
        code: 'BAD_REQUEST',
        message: 'request body must be a JSON object',
      });
      return;
    }

    const op = payload.op;
    if (typeof op !== 'string' || !OPS.includes(op)) {
      // The message is constant: echoing an attacker-supplied op could leak a
      // value that happens to equal the bearer token into the response.
      sendJson(res, 400, {
        ok: false,
        code: 'UNKNOWN_OP',
        message: 'unknown op',
      });
      return;
    }

    const args = payload.args === undefined ? {} : payload.args;
    execResult(req, res, op, args);
  }

  async function handle(req, res) {
    let pathname;
    try {
      pathname = new URL(req.url, 'http://127.0.0.1').pathname;
    } catch {
      drainRequest(req);
      sendJson(res, 400, {
        ok: false,
        code: 'BAD_REQUEST',
        message: 'invalid request target',
      });
      return;
    }

    const route = ROUTES[pathname];
    if (!route) {
      drainRequest(req);
      sendJson(res, 404, {
        ok: false,
        code: 'NOT_FOUND',
        message: 'no such endpoint',
      });
      return;
    }

    if (req.method !== route.method) {
      drainRequest(req);
      sendJson(
        res,
        405,
        { ok: false, code: 'METHOD_NOT_ALLOWED', message: 'method not allowed' },
        { Allow: route.method },
      );
      return;
    }

    if (route.auth && !authorized(req)) {
      drainRequest(req);
      sendJson(res, 401, {
        ok: false,
        code: 'UNAUTHORIZED',
        message: 'a valid bearer token is required',
      });
      return;
    }

    if (pathname === '/healthz') {
      sendJson(res, 200, healthPayload());
      return;
    }
    if (pathname === '/status') {
      sendJson(res, 200, statusPayload());
      return;
    }
    await handleExec(req, res);
  }

  function createServer() {
    const server = http.createServer((req, res) => {
      handle(req, res).catch(() => {
        log('error', 'request handling failed');
        if (!res.headersSent) {
          sendJson(res, 500, {
            ok: false,
            code: 'INTERNAL_ERROR',
            message: 'internal error',
          });
        } else {
          try {
            res.destroy();
          } catch {
            /* ignore */
          }
        }
      });
    });

    // Bounded request handling: no unbounded header count, no forever body,
    // no idle keep-alive connection holding the listener open.
    server.requestTimeout = REQUEST_TIMEOUT_MS;
    server.headersTimeout = HEADERS_TIMEOUT_MS;
    server.keepAliveTimeout = KEEP_ALIVE_TIMEOUT_MS;
    server.maxHeadersCount = MAX_HEADER_COUNT;

    server.on('clientError', (_error, socket) => {
      if (socket.writable) {
        socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
      }
    });

    return server;
  }

  function closeStorage() {
    if (!state.storage) return;
    if (typeof state.storage.close === 'function') {
      try {
        state.storage.close();
      } catch {
        /* ignore */
      }
    }
    state.storage = null;
  }

  function parentIsAlive() {
    try {
      process.kill(parentPid, 0);
      return true;
    } catch (error) {
      // EPERM means the process exists but is owned by another user.
      return Boolean(error && error.code === 'EPERM');
    }
  }

  function startParentWatch() {
    if (parentPid === null || state.parentTimer) return;
    state.parentTimer = setInterval(() => {
      if (state.stopping) return;
      if (parentIsAlive()) return;
      clearInterval(state.parentTimer);
      state.parentTimer = null;
      log('info', 'parent process is gone; shutting down');
      if (onParentGone) {
        try {
          onParentGone();
        } catch {
          /* the CLI callback owns exit */
        }
      } else {
        stop();
      }
    }, PARENT_POLL_MS);
    if (typeof state.parentTimer.unref === 'function') state.parentTimer.unref();
  }

  function start() {
    return new Promise((resolve, reject) => {
      if (state.server) {
        reject(new Error('agent already started'));
        return;
      }

      state.storage = initStorage();
      const server = createServer();
      state.server = server;

      let settled = false;
      const failStart = () => {
        if (settled) return;
        settled = true;
        closeStorage();
        state.server = null;
        reject(new Error('listen failed'));
      };

      server.once('error', (error) => {
        if (!settled) {
          failStart();
          return;
        }
        log('error', `server error: ${error && error.code ? error.code : 'unknown'}`);
      });

      server.listen({ host: '127.0.0.1', port: 0 }, () => {
        if (settled) return;
        const address = server.address();
        if (!address || typeof address === 'string') {
          try {
            server.close();
          } catch {
            /* ignore */
          }
          failStart();
          return;
        }
        settled = true;
        state.port = address.port;

        // stdout carries exactly these two protocol lines on success.
        try {
          stdout.write(
            `GROM_READY ${process.pid}\nGROM_AGENT_READY ${process.pid} ${state.port}\n`,
          );
        } catch {
          /* a closed stdout must not crash the listener */
        }
        log('info', `listening on 127.0.0.1:${state.port} pid=${process.pid}`);
        startParentWatch();
        resolve({ pid: process.pid, port: state.port });
      });
    });
  }

  function stop() {
    if (state.stopPromise) return state.stopPromise;
    state.stopping = true;
    if (state.parentTimer) {
      clearInterval(state.parentTimer);
      state.parentTimer = null;
    }

    const server = state.server;
    if (!server) {
      closeStorage();
      state.stopPromise = Promise.resolve();
      return state.stopPromise;
    }

    state.stopPromise = new Promise((resolve) => {
      let settled = false;
      const done = () => {
        if (settled) return;
        settled = true;
        closeStorage();
        resolve();
      };
      try {
        server.close(done);
      } catch {
        done();
        return;
      }
      // Node 18.2+: drop idle keep-alive connections immediately so a clean
      // SIGTERM does not wait for the keep-alive timeout.
      if (typeof server.closeAllConnections === 'function') {
        try {
          server.closeAllConnections();
        } catch {
          /* ignore */
        }
      }
      const timer = setTimeout(done, STOP_GRACE_MS);
      if (typeof timer.unref === 'function') timer.unref();
    });
    return state.stopPromise;
  }

  return {
    start,
    stop,
    health: healthPayload,
    status: statusPayload,
    get port() {
      return state.port;
    },
    get server() {
      return state.server;
    },
  };
}

/* ------------------------------------------------------------------------- *
 * CLI entry point
 * ------------------------------------------------------------------------- */

function emitFatal(stdout, reason) {
  const line = `GROM_FATAL ${reason}\n`;
  let exited = false;
  const exitNow = () => {
    if (exited) return;
    exited = true;
    process.exit(1);
  };
  try {
    process.exitCode = 1;
    stdout.write(line, exitNow);
    // Guard against a stdout sink that never invokes the callback. This is
    // intentionally short: the supervisor treats a fatal agent as final.
    setTimeout(exitNow, 250);
  } catch {
    exitNow();
  }
}

async function main(argv, io = {}) {
  const stdin = io.stdin || process.stdin;
  const stdout = io.stdout || process.stdout;
  const stderr = io.stderr || process.stderr;

  const args = parseArgs(argv);

  let agent = null;
  let shuttingDown = false;
  const shutdown = (code) => {
    if (shuttingDown) return;
    shuttingDown = true;
    const finish = () => process.exit(code);
    if (!agent) {
      process.exitCode = code;
      finish();
      return;
    }
    agent.stop().then(finish, finish);
  };

  // Install the stop handlers before reading stdin: a supervisor that aborts
  // the spawn (or a test that SIGTERMs during startup) still gets exit 0.
  process.once('SIGTERM', () => shutdown(0));
  process.once('SIGINT', () => shutdown(0));

  const parsed = await readStdinConfig(stdin);
  if (!parsed.ok) {
    emitFatal(stdout, parsed.reason);
    return;
  }

  agent = createAgent({
    config: parsed.config,
    parentPid: args.parentPid,
    stdout,
    stderr,
    onParentGone: () => shutdown(0),
  });

  try {
    await agent.start();
  } catch {
    await agent.stop();
    emitFatal(stdout, 'bind-failed');
  }
}

if (require.main === module) {
  main(process.argv.slice(2)).catch(() => {
    // No error details: they could include the token from a future code path.
    try {
      process.stdout.write('GROM_FATAL internal-error\n');
    } catch {
      /* ignore */
    }
    process.exit(1);
  });
}

module.exports = {
  MAX_STDIN_BYTES,
  MAX_BODY_BYTES,
  OPS,
  STORAGE_UNAVAILABLE_MESSAGE,
  parseInteger,
  parseArgs,
  parseConfig,
  readStdinConfig,
  defaultSqliteLoader,
  createAgent,
};
