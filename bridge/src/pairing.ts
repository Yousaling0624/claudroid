/**
 * QR pairing payload shared with the app (`docs/REMOTE_HOST.md` §3).
 *
 * The QR encodes the same secret as the printed pairing code plus the address
 * the phone should call. Keeping it a URI (instead of free-form JSON) makes the
 * payload self-describing, so a future scanner can reject foreign codes before
 * spending one failed pairing attempt.
 */
export const PAIRING_URI_VERSION = 1;

export interface PairingPayloadInput {
  baseUrl: string;
  code: string;
  /** Suggested host label; usually `os.hostname()`. */
  name?: string;
}

function isLoopback(host: string): boolean {
  return (
    host === "localhost" ||
    host === "::1" ||
    host === "[::1]" ||
    host.startsWith("127.")
  );
}

/**
 * Picks the address a phone can actually reach. A daemon bound to 127.0.0.1
 * only advertises loopback, so showing a QR for it would always fail on the
 * scanning device; in that case callers should tell the operator to use the
 * tunnel or --bind 0.0.0.0 instead.
 */
export function selectPairingUrl(urls: readonly string[]): string | null {
  const reachable = urls.filter((value) => {
    try {
      return !isLoopback(new URL(value).hostname);
    } catch {
      return false;
    }
  });
  return reachable.find((value) => value.startsWith("https://")) ?? reachable[0] ?? null;
}

/** `claudroid://pair?v=1&url=…&code=…&name=…` (query values percent-encoded). */
export function buildPairingPayload(input: PairingPayloadInput): string {
  const params = new URLSearchParams();
  params.set("v", String(PAIRING_URI_VERSION));
  params.set("url", input.baseUrl.trim().replace(/\/+$/, ""));
  params.set("code", input.code.trim().toUpperCase());
  const name = input.name?.trim();
  if (name) params.set("name", name.slice(0, 40));
  return `claudroid://pair?${params.toString()}`;
}
