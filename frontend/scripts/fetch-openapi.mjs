// Downloads the tracking service's OpenAPI document into openapi/ so `npm run api:generate` can
// regenerate the TypeScript contracts. The snapshot is committed: builds never need a running
// backend, and a contract change shows up as a reviewable diff.
//
//   npm run api:fetch                              # http://127.0.0.1:8080/v3/api-docs
//   OPENAPI_URL=http://host:8080/v3/api-docs npm run api:fetch
//
// CI runs this against the live service in the e2e job and fails on any diff (contract drift).
// For that comparison to mean "the contract changed" and nothing else, the output is canonical:
// object keys are sorted recursively (springdoc does not order them by default, so path and schema
// order could otherwise follow bean/reflection order), arrays keep their order (it can carry
// meaning, e.g. enum values), and formatting is fixed.
import { writeFile } from 'node:fs/promises';

const url = process.env.OPENAPI_URL ?? 'http://127.0.0.1:8080/v3/api-docs';
const target = new URL('../openapi/tracking-service.openapi.json', import.meta.url);

function canonical(value) {
  if (Array.isArray(value)) {
    return value.map(canonical);
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, canonical(value[key])]),
    );
  }
  return value;
}

let response;
try {
  response = await fetch(url, { headers: { Accept: 'application/json' } });
} catch (error) {
  console.error(`GET ${url} failed: ${error.message}`);
  process.exit(1);
}
if (!response.ok) {
  console.error(`GET ${url} -> HTTP ${response.status}`);
  process.exit(1);
}
const document = await response.json();
// The `servers` entry points at the backend's own port; the browser only ever uses relative
// same-origin paths, so it is dropped to keep the snapshot environment-neutral.
delete document.servers;
await writeFile(target, JSON.stringify(canonical(document), null, 2) + '\n');
console.log(`Wrote ${target.pathname}`);
