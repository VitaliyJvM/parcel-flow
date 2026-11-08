import { expect, Page, test } from '@playwright/test';
import { runSimulator } from './support/simulator';

/**
 * The real workflow, end to end:
 *
 *   1. register a shipment through the Angular form
 *   2. land on its detail page
 *   3. run the carrier simulator CLI (NORMAL) -> watch it reach DELIVERED via polling
 *   4. run the simulator again (OUT_OF_ORDER, new seed) -> every new event is stored as
 *      SUPERSEDED (DELIVERED is terminal) WITHOUT a shipment version change; the page must still
 *      pick them up
 *   5. check the notification records
 *
 * Identifiers are unique per run (the database and Kafka persist between runs); everything else —
 * scenario, carrier, seeds relative to the run, expected counts — is fixed.
 */

const RUN = Date.now();
const RETAILER = `e2e-retailer-${RUN.toString(36)}`;
const TRACKING_NUMBER = `E2E${RUN}`;
// Java long range; distinct per run and per simulator invocation.
const SEED_NORMAL = RUN;
const SEED_LATE = RUN + 1;

/** SWIFTPOST's scripted journey (carrier-simulator CarrierEventScript) has 8 scans. */
const JOURNEY_LENGTH = 8;

async function selectRetailer(page: Page, retailerId: string) {
  await page.getByRole('button', { name: /select retailer|retailer:/i }).first().click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Retailer ID').fill(retailerId);
  await dialog.getByRole('button', { name: 'Use retailer' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: `Retailer: ${retailerId}. Change retailer` })).toBeVisible();
}

async function registerShipment(page: Page, trackingNumber: string) {
  await page.goto('/shipments/new');
  await page.getByLabel('Customer reference').fill('cust-e2e');
  await page.getByLabel('Tracking number').fill(trackingNumber);
  await page.getByLabel('Estimated delivery date (optional)').fill('2026-08-12');
  await page.getByRole('button', { name: 'Register shipment' }).click();
}

test.describe.serial('shipment workflow against the real stack', () => {
  let shipmentId = '';

  test('register, simulate, observe status, SUPERSEDED events and notifications', async ({ page }) => {
    await page.goto('/dashboard');
    await selectRetailer(page, RETAILER);

    // 1. Register.
    await registerShipment(page, TRACKING_NUMBER);

    // 2. Details page.
    await expect(page).toHaveURL(/\/shipments\/[0-9a-f-]{36}$/);
    shipmentId = page.url().split('/').pop() ?? '';
    const status = page.getByTestId('current-status');
    await expect(status).toHaveAttribute('data-status', 'LABEL_CREATED');
    await expect(page.getByText('Shipment registered.')).toBeVisible();
    // The ETA is a LocalDate: in America/Vancouver it must still read 12 August, not 11.
    await expect(page.getByText('Wed, Aug 12, 2026')).toBeVisible();
    await expect(page.getByText('No carrier events yet')).toBeVisible();
    await expect(page.getByTestId('shipment-version')).toHaveText('0');

    // The Simulate tab produces the same command the test is about to run.
    await page.getByRole('tab', { name: 'Simulate' }).click();
    await expect(page.getByTestId('simulator-command')).toContainText(`--shipment-id=${shipmentId}`);
    await expect(page.getByTestId('simulator-command')).toContainText(`--tracking-number=${TRACKING_NUMBER}`);
    await page.getByRole('tab', { name: /Tracking history/ }).click();

    // 3. Run the CLI simulator; the open page must update by itself (no reload, no clicks).
    await runSimulator({
      shipmentId,
      trackingNumber: TRACKING_NUMBER,
      carrier: 'SWIFTPOST',
      scenario: 'NORMAL',
      seed: SEED_NORMAL,
    });
    await expect(status).toHaveAttribute('data-status', 'DELIVERED', { timeout: 60_000 });
    const events = page.getByTestId('timeline-event');
    await expect(events).toHaveCount(JOURNEY_LENGTH, { timeout: 30_000 });
    await expect(page.locator('[data-testid="timeline-event"][data-processing-status="APPLIED"]')).toHaveCount(
      JOURNEY_LENGTH,
    );
    const versionAfterDelivery = (await page.getByTestId('shipment-version').textContent())?.trim();
    expect(Number(versionAfterDelivery)).toBeGreaterThan(0);

    // 4. Replay the journey with a new seed: new event IDs, so not duplicates, but DELIVERED is
    //    terminal, so all of them are SUPERSEDED and the shipment row (and version) is untouched.
    await runSimulator({
      shipmentId,
      trackingNumber: TRACKING_NUMBER,
      carrier: 'SWIFTPOST',
      scenario: 'OUT_OF_ORDER',
      seed: SEED_LATE,
    });
    await expect(events).toHaveCount(JOURNEY_LENGTH * 2, { timeout: 60_000 });
    await expect(
      page.locator('[data-testid="timeline-event"][data-processing-status="SUPERSEDED"]'),
    ).toHaveCount(JOURNEY_LENGTH);
    await expect(page.getByTestId('timeline-summary')).toContainText(
      `${JOURNEY_LENGTH * 2} events · ${JOURNEY_LENGTH} applied · ${JOURNEY_LENGTH} superseded`,
    );
    await expect(page.getByTestId('shipment-version')).toHaveText(versionAfterDelivery ?? '');
    await expect(status).toHaveAttribute('data-status', 'DELIVERED');
    // Status is from the shipment record; the UI explains why later rows did not change it.
    await expect(page.locator('[data-processing-status="SUPERSEDED"]').first()).toContainText('Superseded');

    // Arrival-order view puts all superseded replays after the applied journey.
    await page.getByRole('radio', { name: 'Arrival order' }).click();
    await expect(events.last()).toHaveAttribute('data-processing-status', 'SUPERSEDED');

    // 5. Notification records: picked up, out for delivery, delivered. Superseded events never
    //    notify, so the replay added none.
    await page.getByRole('tab', { name: /Notifications/ }).click();
    const records = page.getByTestId('notification-record');
    await expect(records).toHaveCount(3);
    await expect(records.nth(0)).toContainText('Parcel picked up');
    await expect(records.nth(1)).toContainText('Out for delivery');
    await expect(records.nth(2)).toContainText('Delivered');
    await expect(records.first()).toContainText('PENDING · not sent');
    await expect(page.getByText('Recorded, not delivered.')).toBeVisible();

    // 6. A notification's source event opens in the tracking history and receives focus, even
    //    though that tab was not rendered while Notifications was shown.
    await records.nth(1).getByRole('button', { name: /Show event SP_OFD #7 in tracking history/ }).click();
    await expect(page.getByRole('tab', { name: /Tracking history/ })).toHaveAttribute('aria-selected', 'true');
    const focused = page.locator('[data-testid="timeline-event"]:focus');
    await expect(focused).toHaveCount(1);
    await expect(focused).toContainText('#7');
    await expect(focused).toHaveAttribute('data-processing-status', 'APPLIED');
    await expect(focused).toBeInViewport();
  });

  test('the shipment is listed for its retailer and filterable by status', async ({ page }) => {
    test.skip(!shipmentId, 'depends on the workflow test');
    await page.goto('/dashboard');
    await selectRetailer(page, RETAILER);
    await page.goto('/shipments?status=DELIVERED');
    await expect(page.getByRole('link', { name: TRACKING_NUMBER })).toBeVisible();
    await page.goto('/shipments?status=IN_TRANSIT');
    await expect(page.getByText('No shipments with status “In transit”')).toBeVisible();

    // A stale page number is recoverable, not reported as "no shipments", and the URL follows.
    await page.goto('/shipments?page=40');
    await expect(page.getByText('Page 41 is out of range')).toBeVisible();
    await expect(page.getByText('No shipments yet')).toHaveCount(0);
    await page.getByRole('button', { name: 'Go to last page (1)' }).click();
    await expect(page).toHaveURL(/\/shipments$/);
    await expect(page.getByRole('link', { name: TRACKING_NUMBER })).toBeVisible();
  });

  test('a duplicate registration is rejected with a clear message', async ({ page }) => {
    test.skip(!shipmentId, 'depends on the workflow test');
    await page.goto('/dashboard');
    await selectRetailer(page, RETAILER);
    await registerShipment(page, TRACKING_NUMBER);
    await expect(page.getByRole('alert').filter({ hasText: 'Already registered' })).toBeVisible();
    await expect(page.getByText('Already registered for this carrier.')).toBeVisible();
    await expect(page).toHaveURL(/\/shipments\/new$/);
  });

  test('an invalid carrier event lands in failed events and is not retryable', async ({ page }) => {
    test.skip(!shipmentId, 'depends on the workflow test');
    await runSimulator({
      shipmentId,
      trackingNumber: TRACKING_NUMBER,
      carrier: 'SWIFTPOST',
      scenario: 'INVALID_EVENT',
      seed: RUN + 2,
    });
    // Dead-lettering is asynchronous; reload the list (bounded) until the record shows up.
    await expect(async () => {
      await page.goto('/operations/failed-events?status=FAILED');
      await expect(page.getByRole('link', { name: shipmentId.slice(0, 8) }).first()).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 60_000, intervals: [1_000, 2_000] });

    const row = page.locator('tr', { has: page.getByRole('link', { name: shipmentId.slice(0, 8) }) }).first();
    await expect(row).toContainText('Validation');
    await expect(row).toContainText('Not retryable');
    await row.locator('td').first().getByRole('link').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Failed event — Validation' })).toBeVisible();
    await expect(page.getByText('Not retryable', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /Retry/ })).toHaveCount(0);
    await expect(page.getByText('deliberately not returned')).toBeVisible();
  });
});
