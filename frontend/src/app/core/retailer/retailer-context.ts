import { computed, inject, Injectable, signal } from '@angular/core';
import { AuthSession } from '../auth/auth-session';

const STORAGE_KEY = 'parcelflow.retailer';
const RECENT_KEY = 'parcelflow.retailer.recent';
const MAX_RECENT = 5;
/** Mirrors `RegisterShipmentRequest.retailerId` (@NotBlank @Size(max = 64)). */
export const RETAILER_ID_MAX_LENGTH = 64;

/**
 * The retailer the UI is currently scoped to.
 *
 * - **RETAILER**: always the retailer the server assigned to the signed-in user
 *   (`AuthSession.retailerId()`). It cannot be changed here, and nothing the UI sends could widen
 *   it: the backend checks every request against its own copy of the assignment.
 * - **ADMIN**: a view filter the admin chooses, because an admin may act for any retailer. The
 *   choice is remembered in localStorage purely as a convenience — it is a retailer id, not a
 *   credential — and every access is guarded because storage can be unavailable.
 */
@Injectable({ providedIn: 'root' })
export class RetailerContext {
  private readonly session = inject(AuthSession);
  private readonly selected = signal<string | null>(readString(STORAGE_KEY));
  private readonly recentIds = signal<readonly string[]>(readList(RECENT_KEY));

  /** Whether the signed-in user may choose a retailer (ADMIN only). */
  readonly canSelect = computed(() => this.session.isAdmin());

  readonly retailerId = computed<string | null>(() => {
    if (this.session.isRetailer()) {
      return this.session.retailerId();
    }
    return this.session.isAdmin() ? this.selected() : null;
  });
  readonly recent = this.recentIds.asReadonly();
  readonly hasRetailer = computed(() => this.retailerId() !== null);

  /** ADMIN only; ignored for anyone else. */
  select(retailerId: string): void {
    const value = normalizeRetailerId(retailerId);
    if (value === null || !this.canSelect()) {
      return;
    }
    this.selected.set(value);
    this.recentIds.update((ids) =>
      [value, ...ids.filter((id) => id !== value)].slice(0, MAX_RECENT),
    );
    write(STORAGE_KEY, value);
    write(RECENT_KEY, JSON.stringify(this.recentIds()));
  }

  clear(): void {
    this.selected.set(null);
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
