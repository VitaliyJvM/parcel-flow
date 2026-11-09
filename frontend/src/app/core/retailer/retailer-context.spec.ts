import { TestBed } from '@angular/core/testing';
import { signInAs, signOutForTest } from '../../../testing/auth';
import { RetailerContext } from './retailer-context';

describe('RetailerContext (ADMIN: a view filter the admin chooses)', () => {
  beforeEach(() => {
    localStorage.clear();
    signInAs('ADMIN');
  });

  it('persists the selection and a short recent list', () => {
    const context = TestBed.inject(RetailerContext);
    context.select('  retailer-1 ');
    context.select('retailer-2');
    context.select('retailer-1');
    expect(context.retailerId()).toBe('retailer-1');
    expect(context.recent()).toEqual(['retailer-1', 'retailer-2']);
    expect(localStorage.getItem('parcelflow.retailer')).toBe('retailer-1');
  });

  it('restores from storage and ignores invalid stored values', () => {
    localStorage.setItem('parcelflow.retailer', 'r'.repeat(65));
    localStorage.setItem('parcelflow.retailer.recent', '["ok", 7, "   "]');
    const context = TestBed.inject(RetailerContext);
    expect(context.retailerId()).toBeNull();
    expect(context.recent()).toEqual(['ok']);
  });

  it('rejects blank and over-long ids', () => {
    const context = TestBed.inject(RetailerContext);
    context.select('   ');
    context.select('x'.repeat(65));
    expect(context.retailerId()).toBeNull();
  });

  it('survives unavailable storage', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceeded');
    });
    const context = TestBed.inject(RetailerContext);
    context.select('retailer-1');
    expect(context.retailerId()).toBe('retailer-1');
    spy.mockRestore();
  });
});

describe('RetailerContext (RETAILER: the server-assigned retailer, fixed)', () => {
  beforeEach(() => localStorage.clear());

  it('is the retailer from the session, and selecting another one is ignored', () => {
    signInAs('RETAILER', 'alpha-goods');
    const context = TestBed.inject(RetailerContext);
    expect(context.retailerId()).toBe('alpha-goods');
    expect(context.canSelect()).toBe(false);

    context.select('beta-supplies');
    expect(context.retailerId()).toBe('alpha-goods');
    expect(localStorage.getItem('parcelflow.retailer')).toBeNull();
  });

  it("ignores an admin's stored selection left in this browser", () => {
    localStorage.setItem('parcelflow.retailer', 'beta-supplies');
    signInAs('RETAILER', 'alpha-goods');
    expect(TestBed.inject(RetailerContext).retailerId()).toBe('alpha-goods');
  });

  it('is empty when nobody is signed in', () => {
    localStorage.setItem('parcelflow.retailer', 'beta-supplies');
    signOutForTest();
    const context = TestBed.inject(RetailerContext);
    expect(context.retailerId()).toBeNull();
    expect(context.canSelect()).toBe(false);
  });
});
