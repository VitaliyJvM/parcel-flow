import { TestBed } from '@angular/core/testing';
import { RetailerContext } from './retailer-context';

describe('RetailerContext', () => {
  beforeEach(() => localStorage.clear());

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
