export type PreviewInfo = { expiresAt: string };

export function readPreviewInfo(health: unknown): PreviewInfo | null {
  if (!health || typeof health !== 'object') return null;
  const preview = (health as { preview?: unknown }).preview;
  if (!preview || typeof preview !== 'object') return null;
  const value = preview as Record<string, unknown>;
  if (value.ephemeral !== true || value.syntheticDataOnly !== true || typeof value.expiresAt !== 'string') return null;
  const expiry = Date.parse(value.expiresAt);
  return Number.isFinite(expiry) ? { expiresAt: new Date(expiry).toISOString() } : null;
}

export function previewHasExpired(info: PreviewInfo, now: number): boolean {
  return now >= Date.parse(info.expiresAt);
}
