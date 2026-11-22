/** Wire types shared by the bridge server, the runner and the tests. */

export const PROTOCOL_VERSION = 1;

export type PermissionMode =
  | "default"
  | "acceptEdits"
  | "plan"
  | "bypassPermissions";

export const SAFE_PERMISSION_MODES: PermissionMode[] = [
  "default",
  "acceptEdits",
  "plan",
];

export interface HostInfo {
  protocol: number;
  daemonVersion: string;
  claudeVersion?: string;
  hostName: string;
  platform: string;
  arch: string;
  workspaceRoots: string[];
  defaultCwd: string;
  defaultModel?: string;
  permissionModes: PermissionMode[];
  allowBypassPermissions: boolean;
  /** True when `GET /v1/events/batch` exists; quick tunnels buffer SSE, so the app polls instead. */
  eventPoll: boolean;
  tunnel: TunnelStatus;
}

export interface PairRequest {
  code: string;
  deviceName?: string;
}

export interface PairResponse {
  token: string;
  host: HostInfo;
}

export interface TurnStartRequest {
  cwd: string;
  prompt: string;
  resumeSessionId?: string;
  permissionMode?: PermissionMode;
  model?: string;
}

export interface TurnSnapshot {
  turnId: string;
  status: "running" | "done" | "cancelled" | "error";
  exitCode?: number;
  sessionId?: string;
  error?: string;
  startedAt: number;
  finishedAt?: number;
}

export interface PermissionAsk {
  requestId: string;
  toolUseID: string;
  toolName: string;
  input: Record<string, unknown>;
  suggestions?: unknown;
  title?: string;
  displayName?: string;
  description?: string;
  decisionReason?: string;
}

export type PermissionDecision =
  | {
      behavior: "allow";
      /** True when the user chose "always allow for this session". */
      always?: boolean;
      message?: string;
    }
  | { behavior: "deny"; message?: string };

export interface FileEntry {
  name: string;
  path: string;
  isDir: boolean;
  size: number;
  mtimeMs: number;
}

interface EventBase {
  seq: number;
  /** Device that owns per-turn events; absent for host/tunnel broadcasts. */
  owner?: string;
}

export interface CliEvent extends EventBase {
  kind: "cli";
  turnId: string;
  owner: string;
  /** One raw Claude Code stream-json line, forwarded verbatim. */
  line: string;
}

export interface PermissionEvent extends EventBase {
  kind: "permission";
  turnId: string;
  owner: string;
  requestId: string;
  toolUseID: string;
  toolName: string;
  input: Record<string, unknown>;
  suggestions?: unknown;
  title?: string;
  displayName?: string;
  description?: string;
  decisionReason?: string;
  expiresAt: number;
}

export interface TurnEvent extends EventBase {
  kind: "turn";
  turnId: string;
  owner: string;
  phase: "started" | "done" | "error";
  exitCode?: number;
  error?: string;
}

export interface HostEvent extends EventBase {
  kind: "host";
  host: HostInfo;
}

export interface TunnelEvent extends EventBase {
  kind: "tunnel";
  tunnel: TunnelStatus;
}

export type BridgeEvent =
  | CliEvent
  | PermissionEvent
  | TurnEvent
  | HostEvent
  | TunnelEvent;

export type PublishableEvent =
  | Omit<CliEvent, "seq">
  | Omit<PermissionEvent, "seq">
  | Omit<TurnEvent, "seq">
  | Omit<HostEvent, "seq">
  | Omit<TunnelEvent, "seq">;

export type TunnelMode = "off" | "quick" | "named";

export interface TunnelStatus {
  mode: TunnelMode;
  state: "off" | "starting" | "up" | "error" | "stopped";
  url?: string;
  error?: string;
  changedAt: number;
}
