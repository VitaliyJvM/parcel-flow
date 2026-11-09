import { TestBed } from '@angular/core/testing';
import type { Role, SessionUser } from '../app/core/api/models';
import { AuthSession } from '../app/core/auth/auth-session';
import { BrowserNavigation } from '../app/core/auth/browser-navigation';

export const ADMIN_USER: SessionUser = {
  subject: '6f1c0a52-3b7e-4d0e-9a41-2d5f8c7b1a01',
  username: 'ops.admin',
  displayName: 'Olive Operator',
  role: 'ADMIN',
  retailerId: null,
};

export function retailerUser(retailerId: string): SessionUser {
  return {
    subject: `sub-${retailerId}`,
    username: `retailer.${retailerId}`,
    displayName: `Retailer ${retailerId}`,
    role: 'RETAILER',
    retailerId,
  };
}

/**
 * Puts the test's AuthSession into a signed-in state, as `GET /api/auth/session` would. Call after
 * `TestBed.configureTestingModule` and before anything reads the session.
 */
export function signInAs(role: Role, retailerId = 'retailer-42'): SessionUser {
  const user = role === 'ADMIN' ? ADMIN_USER : retailerUser(retailerId);
  TestBed.inject(AuthSession).accept({ authenticated: true, user });
  return user;
}

export function signOutForTest(): void {
  TestBed.inject(AuthSession).accept({ authenticated: false, user: null });
}

/** Records full-page navigations instead of performing them. */
export class RecordingNavigation {
  readonly assigned: string[] = [];
  readonly posts: { action: string; fields: Readonly<Record<string, string>> }[] = [];

  assign(url: string): void {
    this.assigned.push(url);
  }

  submitPost(action: string, fields: Readonly<Record<string, string>>): void {
    this.posts.push({ action, fields });
  }
}

export function provideRecordingNavigation() {
  const navigation = new RecordingNavigation();
  return { navigation, provider: { provide: BrowserNavigation, useValue: navigation } };
}
