import { renderUnicodeCompact } from "uqr";

/**
 * A QR block for the terminal.
 *
 * `uqr` draws light modules as foreground glyphs, so on a light terminal the
 * code would come out inverted. Pinning every row to black background / bright
 * white foreground keeps it scannable in both themes; the extra border is the
 * quiet zone phone cameras need.
 */
export function renderTerminalQr(text: string): string {
  return renderUnicodeCompact(text, { ecc: "M", border: 4 })
    .split("\n")
    .map((line) => `\x1b[40m\x1b[97m${line}\x1b[0m`)
    .join("\n");
}
