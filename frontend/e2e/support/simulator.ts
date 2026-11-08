import { execFile } from 'node:child_process';
import path from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);

/** Repository root (this file lives in frontend/e2e/support). */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

export interface SimulatorRun {
  shipmentId: string;
  trackingNumber: string;
  carrier: 'SWIFTPOST' | 'PACIFICA';
  scenario: 'NORMAL' | 'DUPLICATE' | 'OUT_OF_ORDER' | 'INVALID_EVENT' | 'UNKNOWN_CARRIER_EVENT' | 'RAPID_CONCURRENT_EVENTS';
  seed: number;
  delayMs?: number;
}

/**
 * Runs the existing carrier simulator CLI exactly as a user would paste it from the UI:
 * `docker compose run --rm carrier-simulator ...`. Resolves when the simulator has published and
 * exited 0; rejects with its output otherwise.
 *
 * An explicit seed makes the published events reproducible. It must still differ between test
 * runs: event IDs derive from the seed alone, so reusing one would make the tracking service
 * (correctly) discard a later run's events as duplicates of an earlier run.
 */
export async function runSimulator(options: SimulatorRun): Promise<string> {
  const args = [
    'compose',
    'run',
    '--rm',
    '--no-TTY',
    'carrier-simulator',
    `--shipment-id=${options.shipmentId}`,
    `--tracking-number=${options.trackingNumber}`,
    `--carrier=${options.carrier}`,
    `--scenario=${options.scenario}`,
    `--seed=${options.seed}`,
    `--delay-ms=${options.delayMs ?? 150}`,
  ];
  try {
    const { stdout, stderr } = await run('docker', args, {
      cwd: REPO_ROOT,
      timeout: 180_000,
      maxBuffer: 10 * 1024 * 1024,
    });
    return stdout + stderr;
  } catch (error) {
    const e = error as { stdout?: string; stderr?: string; message: string };
    throw new Error(`carrier simulator failed: ${e.message}\n${e.stdout ?? ''}\n${e.stderr ?? ''}`, {
      cause: error,
    });
  }
}
