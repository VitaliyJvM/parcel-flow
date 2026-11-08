import type { ShipmentStatus } from '../../core/api/models';
import { SHIPMENT_STATUSES } from '../../shared/domain';

export interface ListQuery {
  status: ShipmentStatus | null;
  page: number;
  size: number;
}

export const DEFAULT_PAGE_SIZE = 20;
export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100] as const;

/**
 * Parses list query parameters defensively: a hand-edited URL with `page=-1` or `size=5000` is
 * clamped to what the backend accepts (`@Min(0)`, `@Max(100)`) instead of producing a 400.
 */
export function parseListQuery(
  status: string | undefined,
  page: string | undefined,
  size: string | undefined,
  maxSize: number,
): ListQuery {
  const parsedStatus = SHIPMENT_STATUSES.find((s) => s === status) ?? null;
  const parsedPage = Number.parseInt(page ?? '', 10);
  const parsedSize = Number.parseInt(size ?? '', 10);
  return {
    status: parsedStatus,
    page: Number.isFinite(parsedPage) && parsedPage > 0 ? parsedPage : 0,
    size:
      Number.isFinite(parsedSize) && parsedSize > 0 ? Math.min(parsedSize, maxSize) : DEFAULT_PAGE_SIZE,
  };
}
