import { computed, Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'parcelflow.retailer';
const RECENT_KEY = 'parcelflow.retailer.recent';
const MAX_RECENT = 5;
/** Mirrors `RegisterShipmentRequest.retailerId` (@NotBlank @Size(max = 64)). */
export const RETAILER_ID_MAX_LENGTH = 64;

/**
 * The retailer the UI is currently scoped to.
 *
 * This is a *view filter*, not an identity. The backend has no authentication, so anyone can read
 * any retailer's shipments by changing the path; selecting a retailer here only decides which
 * list the UI asks for. Persisted in localStorage purely as a convenience; every access is
 * guarded because storage can be unavailable (private windows, blocked site data).
 */
@Injectable({ providedIn: 'root' })
export class RetailerContext {
  private readonly current = signal<string | null>(readString(STORAGE_KEY));
  private readonly recentIds = signal<readonly string[]>(readList(RECENT_KEY));

  readonly retailerId = this.current.asReadonly();
  readonly recent = this.recentIds.asReadonly();
  readonly hasRetailer = computed(() => this.current() !== null);

  select(retailerId: string): void {
    const value = normalizeRetailerId(retailerId);
    if (value === null) {
      return;
    }
    this.current.set(value);
    this.recentIds.update((ids) => [value, ...ids.filter((id) => id !== value)].slice(0, MAX_RECENT));
    write(STORAGE_KEY, value);
    write(RECENT_KEY, JSON.stringify(this.recentIds()));
  }

  clear(): void {
    this.current.set(null);
    remove(STORAGE_KEY);
  }
}

export function normalizeRetailerId(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length === 0 || trimmed.length > RETAILER_ID_MAX_LENGTH ? null : trimmed;
}

function storage(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

function readString(key: string): string | null {
  try {
    const value = storage()?.getItem(key) ?? null;
    return value === null ? null : normalizeRetailerId(value);
  } catch {
    return null;
  }
}

function readList(key: string): string[] {
  try {
    const raw = storage()?.getItem(key);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed)
      ? parsed
          .filter((item): item is string => typeof item === 'string')
          .map((item) => normalizeRetailerId(item))
          .filter((item): item is string => item !== null)
          .slice(0, MAX_RECENT)
      : [];
  } catch {
    return [];
  }
}

function write(key: string, value: string): void {
  try {
    storage()?.setItem(key, value);
  } catch {
    // Storage full or blocked: the selection still works for this session.
  }
}

function remove(key: string): void {
  try {
    storage()?.removeItem(key);
  } catch {
    // ignore
  }
}
