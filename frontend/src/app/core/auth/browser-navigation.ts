import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';

/**
 * The two full-page navigations authentication needs, behind an injectable seam so unit tests can
 * observe them instead of unloading the test runner's page.
 *
 * Both are deliberately top-level navigations rather than `HttpClient` calls: login and logout
 * redirect to the identity provider's origin, which an XHR/fetch could not follow (and must not:
 * the provider's pages are not part of this application's API).
 */
@Injectable({ providedIn: 'root' })
export class BrowserNavigation {
  private readonly document = inject(DOCUMENT);

  /** Leaves the SPA for `url` (same-origin path; the backend redirects onward). */
  assign(url: string): void {
    this.document.location.assign(url);
  }

  /**
   * Submits a POST form to a same-origin path. Used for logout: the response is a redirect to the
   * identity provider's end-session endpoint, which the browser follows as a normal navigation, so
   * the ID token hint in that redirect never passes through script.
   */
  submitPost(action: string, fields: Readonly<Record<string, string>>): void {
    const form = this.document.createElement('form');
    form.method = 'post';
    form.action = action;
    form.hidden = true;
    for (const [name, value] of Object.entries(fields)) {
      const input = this.document.createElement('input');
      input.type = 'hidden';
      input.name = name;
      input.value = value;
      form.appendChild(input);
    }
    this.document.body.appendChild(form);
    form.submit();
  }
}
