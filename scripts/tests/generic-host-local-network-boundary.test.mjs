import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = path => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');

test('generic host direct-JAR and env example default to loopback without adding credentials', () => {
  const config = read('examples/generic-spring-host/src/main/resources/application.yml');
  assert.match(config, /^server:\n  address: \$\{SERVER_ADDRESS:127\.0\.0\.1\}\n/m);
  assert.match(config, /secret: \$\{EXAMPLE_HOST_SECRET:\}/);
  assert.match(config, /bearer-token: \$\{EXAMPLE_HOST_TOKEN:\}/);
  assert.match(read('examples/generic-spring-host/.env.example'), /^SERVER_ADDRESS=127\.0\.0\.1$/m);
});

test('Docker example distinguishes container interface from loopback host publication', () => {
  const doc = read('examples/generic-spring-host/README.md');
  const command = doc.match(/```bash\n(docker run[\s\S]*?)\n```/)[1];
  assert.match(command, /-p 127\.0\.0\.1:18080:18080/);
  assert.match(command, /-e SERVER_ADDRESS=0\.0\.0\.0/);
  assert.doesNotMatch(command, /--network[= ]host|--publish-all|-P(?:\s|$)/);
  assert.match(doc, /public demo material/);
  assert.match(doc, /never reuse it for production or shared deployments/);
});

test('host smoke transport remains loopback and the image does not override safe bind defaults', () => {
  const workflow = read('.github/workflows/generic-spring-host-ci.yml');
  assert.equal((workflow.match(/APPROVAL_HOST_URL: http:\/\/127\.0\.0\.1:18080/g) || []).length, 2);
  assert.doesNotMatch(read('examples/generic-spring-host/Dockerfile'), /ENV\s+SERVER_ADDRESS/);
});
