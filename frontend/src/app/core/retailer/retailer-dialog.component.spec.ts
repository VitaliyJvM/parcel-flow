import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { RetailerContext } from './retailer-context';
import { RetailerDialog } from './retailer-dialog.component';

describe('RetailerDialog', () => {
  beforeEach(() => localStorage.clear());

  async function render() {
    const close = vi.fn();
    TestBed.configureTestingModule({
      imports: [RetailerDialog],
      providers: [{ provide: MatDialogRef, useValue: { close } }],
    });
    const fixture = TestBed.createComponent(RetailerDialog);
    await fixture.whenStable();
    return { fixture, close, el: fixture.nativeElement as HTMLElement };
  }

  it('selects the typed retailer on submit without a native form submission', async () => {
    const { fixture, close, el } = await render();
    const input = el.querySelector('input') as HTMLInputElement;
    input.value = 'retailer-7';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    const submit = new Event('submit', { cancelable: true });
    el.querySelector('form')?.dispatchEvent(submit);

    expect(submit.defaultPrevented).toBe(true);
    expect(TestBed.inject(RetailerContext).retailerId()).toBe('retailer-7');
    expect(close).toHaveBeenCalledWith('retailer-7');
  });

  it('does not accept a blank id', async () => {
    const { fixture, close, el } = await render();
    const input = el.querySelector('input') as HTMLInputElement;
    input.value = '   ';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    el.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(close).not.toHaveBeenCalled();
    expect(TestBed.inject(RetailerContext).retailerId()).toBeNull();
  });
});
