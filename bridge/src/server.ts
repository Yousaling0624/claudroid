import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { hostname } from "node:os";
import { AuthError, type AuthService, type DeviceRecord, type PairingInfo } from "./auth.js";
import type { BridgeConfig } from "./config.js";
import { HttpError } from "./errors.js";
import type { EventBus } from "./events.js";
import type { FileError, FileService } from "./files.js";
import type { PermissionBroker } from "./permissions.js";
import type { TunnelManager } from "./tunnel.js";
import type { TurnManager } from "./turns.js";
import {
  PROTOCOL_VERSION,
  SAFE_PERMISSION_MODES,
  type BridgeEvent,
  type HostInfo,
  type PairRequest,
  type PermissionDecision,
  type TurnStartRequest,
} from "./types.js";

export interface BridgeServerDeps {
  config: BridgeConfig;
  auth: AuthService;
  bus: EventBus;
  turns: TurnManager;
  files: FileService;
  tunnel: TunnelManager;
  version: string;
  claudeVersion?: string;
  logger?: (message: string) => void;
  onPairingChanged?: (info: PairingInfo) => void;
}

const MAX_JSON_BYTES = 8 * 1024 * 1024;

function hostInfo(deps: BridgeServerDeps): HostInfo {
  const modes = deps.config.allowBypassPermissions
    ? [...SAFE_PERMISSION_MODES, "bypassPermissions" as const]
    : SAFE_PERMISSION_MODES;
  return {
    protocol: PROTOCOL_VERSION,
    daemonVersion: deps.version,
    claudeVersion: deps.claudeVersion,
    hostName: hostname(),
    platform: process.platform,
    arch: process.arch,
    workspaceRoots: deps.files.workspaceRoots,
    defaultCwd: deps.config.defaultCwd,
    defaultModel: process.env.ANTHROPIC_MODEL,
    permissionModes: modes,
    allowBypassPermissions: deps.config.allowBypassPermissions,
    tunnel: deps.tunnel.current(),
  };
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  if (res.headersSent) return;
  const data = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(data),
    "cache-control": "no-store",
  });
  res.end(data);
}

function fail(res: ServerResponse, error: unknown): void {
  if (error instanceof HttpError || error instanceof AuthError) {
    sendJson(res, error.status, { error: error.message });
    return;
  }
  if (error && typeof error === "object" && "status" in error && typeof (error as FileError).status === "number") {
    const fileError = error as FileError;
    sendJson(res, fileError.status, { error: fileError.message });
    return;
  }
  sendJson(res, 500, { error: "守护进程内部错误。" });
}

async function readJson(req: IncomingMessage, limit = MAX_JSON_BYTES): Promise<unknown> {
  const declared = Number(req.headers["content-length"] ?? 0);
  if (Number.isFinite(declared) && declared > limit) throw new HttpError("请求体过大。", 413);
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > limit) throw new HttpError("请求体过大。", 413);
    chunks.push(buffer);
  }
  if (size === 0) return null;
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new HttpError("JSON 格式无效。", 400);
  }
}

function authenticate(req: IncomingMessage, auth: AuthService): DeviceRecord {
  const header = req.headers.authorization ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header);
  const device = match ? auth.verify(match[1]!.trim()) : null;
  if (!device) throw new HttpError("未授权：请先配对。", 401);
  return device;
}

function writeEvent(res: ServerResponse, event: BridgeEvent): void {
  try {
    res.write(`id: ${event.seq}\nevent: ${event.kind}\ndata: ${JSON.stringify(event)}\n\n`);
  } catch {
    // The connection is gone; the close handler removes the subscriber.
  }
}

function openEventStream(
  req: IncomingMessage,
  res: ServerResponse,
  deps: BridgeServerDeps,
  device: DeviceRecord,
): void {
  const url = new URL(req.url ?? "/", "http://localhost");
  const headerSince = Number(req.headers["last-event-id"] ?? 0);
  const querySince = Number(url.searchParams.get("since") ?? 0);
  const since = Math.max(
    0,
    Number.isFinite(querySince) && querySince > 0 ? querySince : headerSince,
  );

  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-store",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  });
  res.flushHeaders();
  res.write("retry: 3000\n\n");

  for (const event of deps.bus.replaySince(since, device.id)) writeEvent(res, event);
  const unsubscribe = deps.bus.subscribe(device.id, (event) => writeEvent(res, event));
  const heartbeat = setInterval(() => {
    try {
      res.write(": ping\n\n");
    } catch {
      // handled by close
    }
  }, 15_000);
  heartbeat.unref();

  let closed = false;
  const cleanup = (): void => {
    if (closed) return;
    closed = true;
    clearInterval(heartbeat);
    unsubscribe();
  };
  req.on("close", cleanup);
  res.on("close", cleanup);
}

async function handle(
  req: IncomingMessage,
  res: ServerResponse,
  deps: BridgeServerDeps,
): Promise<void> {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = url.pathname;
  const method = req.method ?? "GET";

  if (method === "POST" && path === "/v1/pair") {
    const body = (await readJson(req, 64 * 1024)) as Partial<PairRequest> | null;
    if (!body || typeof body.code !== "string") throw new HttpError("缺少配对码。", 400);
    const ip = req.socket.remoteAddress ?? "unknown";
    const paired = deps.auth.pair(body.code, body.deviceName ?? "Android", ip);
    // Print the next code to the host operator; a paired device never sees it.
    const next = deps.auth.issuePairingCode();
    deps.onPairingChanged?.(next);
    sendJson(res, 200, { token: paired.token, host: hostInfo(deps) });
    return;
  }

  const device = authenticate(req, deps.auth);

  if (method === "GET" && path === "/v1/health") {
    sendJson(res, 200, hostInfo(deps));
    return;
  }

  if (method === "GET" && path === "/v1/events") {
    openEventStream(req, res, deps, device);
    return;
  }

  if (method === "POST" && path === "/v1/turns") {
    const body = (await readJson(req)) as TurnStartRequest | null;
    if (!body) throw new HttpError("缺少请求体。", 400);
    const snapshot = await deps.turns.start(device.id, body);
    sendJson(res, 202, snapshot);
    return;
  }

  const turnRoute = /^\/v1\/turns\/([^/]+)(?:\/(cancel|permission))?$/.exec(path);
  if (turnRoute) {
    const turnId = decodeURIComponent(turnRoute[1]!);
    const action = turnRoute[2];
    if (method === "GET" && !action) {
      sendJson(res, 200, deps.turns.snapshot(turnId, device.id));
      return;
    }
    if (method === "POST" && action === "cancel") {
      sendJson(res, 200, deps.turns.cancel(turnId, device.id));
      return;
    }
    if (method === "POST" && action === "permission") {
      const body = (await readJson(req, 256 * 1024)) as
        | { requestId?: unknown; behavior?: unknown; message?: unknown; always?: unknown }
        | null;
      if (!body || typeof body.requestId !== "string") {
        throw new HttpError("缺少 requestId。", 400);
      }
      if (body.behavior !== "allow" && body.behavior !== "deny") {
        throw new HttpError("behavior 必须是 allow 或 deny。", 400);
      }
      const decision: PermissionDecision =
        body.behavior === "allow"
          ? { behavior: "allow", always: body.always === true, message: undefined }
          : {
              behavior: "deny",
              message: typeof body.message === "string" ? body.message : undefined,
            };
      deps.turns.respondPermission(turnId, device.id, body.requestId, decision);
      sendJson(res, 200, { ok: true });
      return;
    }
    throw new HttpError("不支持的任务操作。", 405);
  }

  if (path === "/v1/fs") {
    const fsPath = url.searchParams.get("path");
    if (!fsPath) throw new HttpError("缺少 path。", 400);
    if (method === "GET") {
      const op = url.searchParams.get("op") ?? "list";
      if (op === "download") {
        const { stream, size } = await deps.files.openRead(fsPath);
        if (res.headersSent) return;
        res.writeHead(200, {
          "content-type": "application/octet-stream",
          "content-length": size,
          "cache-control": "no-store",
        });
        stream.pipe(res);
        return;
      }
      if (op === "stat") {
        sendJson(res, 200, await deps.files.statPath(fsPath));
        return;
      }
      sendJson(res, 200, { entries: await deps.files.list(fsPath) });
      return;
    }
    if (method === "PUT") {
      const written = await deps.files.saveStream(fsPath, req);
      sendJson(res, 200, { ok: true, size: written });
      return;
    }
    if (method === "DELETE") {
      const recursive = url.searchParams.get("recursive") === "1";
      await deps.files.remove(fsPath, recursive);
      sendJson(res, 200, { ok: true });
      return;
    }
    throw new HttpError("不支持的 HTTP 方法。", 405);
  }

  if (method === "POST" && path === "/v1/fs/mkdir") {
    const body = (await readJson(req, 64 * 1024)) as { path?: unknown } | null;
    if (!body || typeof body.path !== "string") throw new HttpError("缺少 path。", 400);
    await deps.files.mkdir(body.path);
    sendJson(res, 200, { ok: true });
    return;
  }

  if (method === "POST" && path === "/v1/fs/rename") {
    const body = (await readJson(req, 64 * 1024)) as { from?: unknown; to?: unknown } | null;
    if (!body || typeof body.from !== "string" || typeof body.to !== "string") {
      throw new HttpError("缺少 from/to。", 400);
    }
    await deps.files.rename(body.from, body.to);
    sendJson(res, 200, { ok: true });
    return;
  }

  if (path === "/v1/tunnel") {
    if (method === "GET") {
      sendJson(res, 200, deps.tunnel.current());
      return;
    }
    if (method === "POST") {
      const body = (await readJson(req, 64 * 1024)) as { action?: unknown } | null;
      const action = body?.action;
      if (action === "start") {
        if (deps.config.tunnel.mode === "off") {
          throw new HttpError("主机配置未启用隧道（tunnel.mode=off）。", 409);
        }
        deps.tunnel.start();
      } else if (action === "stop") {
        deps.tunnel.stop();
      } else if (action === "restart") {
        deps.tunnel.restart();
      } else {
        throw new HttpError("action 必须是 start/stop/restart。", 400);
      }
      sendJson(res, 200, deps.tunnel.current());
      return;
    }
    throw new HttpError("不支持的 HTTP 方法。", 405);
  }

  throw new HttpError("接口不存在。", 404);
}

export function createBridgeServer(deps: BridgeServerDeps): Server {
  const server = createServer((req, res) => {
    void handle(req, res, deps).catch((error) => {
      try {
        fail(res, error);
      } catch {
        res.destroy();
      }
    });
  });
  server.keepAliveTimeout = 65_000;
  server.headersTimeout = 70_000;
  server.requestTimeout = 10 * 60_000;
  return server;
}
