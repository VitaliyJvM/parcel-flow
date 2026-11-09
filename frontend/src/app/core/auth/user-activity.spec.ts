import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NOW } from '../live/page-visibility';
import {
  BACKGROUND_HEADER,
  BACKGROUND_REQUEST,
  UserActivity,
  userActivityInterceptor,
} from './user-activity';

describe('userActivityInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let now: number;

  beforeEach(() => {
    now = 1_000;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([userActivityInterceptor])),
        provideHttpClientTesting(),
        { provide: NOW, useValue: () => now },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    // Created when the application starts, which counts as the first activity.
    TestBed.inject(UserActivity);
  });

  afterEach(() => http.verify());

  const background = () => ({ context: new HttpContext().set(BACKGROUND_REQUEST, true) });

  /** Sends a GET and returns the background header it went out with (null if none). */
  function send(url: string, options = {}): string | null {
    client.get(url, options).subscribe();
    const request = http.expectOne(url);
    request.flush({});
    return request.request.headers.get(BACKGROUND_HEADER);
  }

  it('a poll with no user interaction since the last activity is background', () => {
    now += 60_000;
    expect(send('/api/shipments/x', background())).toBe('true');
    now += 15_000;
    expect(send('/api/shipments/x', background())).toBe('true');
  });

  it('a poll after the user interacted counts as activity, once', () => {
    now += 60_000;
    TestBed.inject(UserActivity).interacted();
    now += 15_000;
    expect(send('/api/shipments/x', background())).toBeNull();
    now += 15_000;
    expect(send('/api/shipments/x', background())).toBe('true');
  });

  it('a real DOM interaction is picked up', () => {
    now += 60_000;
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));
    now += 1_000;
    expect(send('/api/shipments/x', background())).toBeNull();
  });

  it('a request the user caused is never background, and resets the reference point', () => {
    now += 60_000;
    TestBed.inject(UserActivity).interacted();
    expect(send('/api/retailers/alpha-goods/shipments')).toBeNull();
    now += 15_000;
    expect(send('/api/shipments/x', background())).toBe('true');
  });

  it('leaves non-API requests alone', () => {
    expect(send('/assets/x.json', background())).toBeNull();
  });
});
