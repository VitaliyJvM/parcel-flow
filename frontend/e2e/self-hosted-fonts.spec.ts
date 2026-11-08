import { expect, Page, test } from '@playwright/test';

/**
 * Fonts are self-hosted. With Google Fonts blocked outright, every page must still load Roboto and
 * the Material Symbols subset from the application's own origin, and every <mat-icon> must render
 * as a glyph (a missing ligature would show the icon name as text, e.g. "inventory_2").
 */

const RUN = Date.now();
const RETAILER = `fonts-retailer-${RUN.toString(36)}`;

const VIEWPORTS = [
  { name: 'desktop', width: 1280, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
] as const;

interface IconReport {
  name: string;
  fontFamily: string;
  fontSize: number;
  overflows: boolean;
}

async function iconReport(page: Page): Promise<IconReport[]> {
  return page.locator('mat-icon').evaluateAll((icons) =>
    icons
      .filter((el) => (el as HTMLElement).offsetParent !== null) // visible only
      .map((el) => {
        const html = el as HTMLElement;
        return {
          name: (html.textContent ?? '').trim(),
          fontFamily: getComputedStyle(html).fontFamily,
          fontSize: parseFloat(getComputedStyle(html).fontSize),
          // .mat-icon is a fixed 24px box with overflow hidden: a ligature fits inside it, a
          // spelled-out name does not.
          overflows: html.scrollWidth > html.clientWidth + 2,
        };
      }),
  );
}

for (const viewport of VIEWPORTS) {
  test.describe(`self-hosted fonts (${viewport.name})`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } });

    test('icons and typography render with Google Fonts blocked', async ({ page, request, baseURL }) => {
      const origin = new URL(baseURL ?? 'http://127.0.0.1').origin;
      const blocked: string[] = [];
      const fontUrls: string[] = [];
      const consoleErrors: string[] = [];

      await page.route(/fonts\.(googleapis|gstatic)\.com/, (route) => {
        blocked.push(route.request().url());
        return route.abort();
      });
      page.on('request', (r) => {
        if (r.resourceType() === 'font') fontUrls.push(r.url());
      });
      page.on('console', (m) => {
        if (m.type() === 'error') consoleErrors.push(m.text());
      });
      page.on('pageerror', (e) => consoleErrors.push(e.message));

      // A shipment to open on the details page.
      const created = await request.post('/api/shipments', {
        data: {
          retailerId: RETAILER,
          customerId: 'cust-fonts',
          trackingNumber: `FONTS${RUN}${viewport.name}`,
          carrierCode: 'SWIFTPOST',
          estimatedDeliveryDate: '2026-08-12',
        },
      });
      expect(created.status()).toBe(201);
      const { shipmentId } = (await created.json()) as { shipmentId: string };

      await page.addInitScript((r) => localStorage.setItem('parcelflow.retailer', r), RETAILER);

      const pages = [
        { path: '/dashboard', heading: 'Overview' },
        { path: '/shipments', heading: 'Shipments' },
        { path: `/shipments/${shipmentId}`, heading: /Shipment FONTS/ },
        { path: '/operations/failed-events', heading: 'Failed events' },
        { path: `/simulator?shipmentId=${shipmentId}&trackingNumber=T&carrier=SWIFTPOST`, heading: 'Carrier simulator' },
      ];

      for (const { path, heading } of pages) {
        await page.goto(path);
        await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
        await page.evaluate(() => document.fonts.ready);

        const loaded = await page.evaluate(() => ({
          symbols: document.fonts.check('24px "Material Symbols Outlined"'),
          roboto: document.fonts.check('400 14px Roboto') && document.fonts.check('500 14px Roboto'),
          symbolFaces: [...document.fonts].filter(
            (f) => f.family.replace(/"/g, '') === 'Material Symbols Outlined' && f.status === 'loaded',
          ).length,
        }));
        expect(loaded, `fonts on ${path}`).toEqual({ symbols: true, roboto: true, symbolFaces: 1 });

        const icons = await iconReport(page);
        expect(icons.length, `visible icons on ${path}`).toBeGreaterThan(0);
        for (const icon of icons) {
          expect(icon.fontFamily, `font of "${icon.name}" on ${path}`).toContain('Material Symbols Outlined');
          expect(icon.overflows, `"${icon.name}" renders as text on ${path}`).toBe(false);
          // The smallest deliberate size is 16px (status chips); 14px would mean the icon lost
          // its default size and inherited body text size.
          expect(icon.fontSize, `size of "${icon.name}" on ${path}`).toBeGreaterThanOrEqual(16);
        }
      }

      expect(blocked, 'requests to Google Fonts').toEqual([]);
      expect(fontUrls.length).toBeGreaterThan(0);
      for (const url of fontUrls) {
        expect(new URL(url).origin, `font ${url}`).toBe(origin);
      }
      expect(fontUrls.some((u) => u.includes('material-symbols-outlined-subset'))).toBe(true);
      expect(fontUrls.some((u) => u.includes('roboto-latin-400'))).toBe(true);
      expect(consoleErrors).toEqual([]);
    });
  });
}
