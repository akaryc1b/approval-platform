import { before } from 'node:test';
import { provisionGrafanaCiFonts } from '../ops/grafana-browser-fonts.mjs';

// Once per aggregate, outside the unchanged 60s font/80s component/360s native tests.
// Cold CI setup owns 570s plus at most 3s cleanup; failure blocks every runtime probe.
before(async () => {
  if (process.env.GITHUB_ACTIONS === 'true') await provisionGrafanaCiFonts();
}, { timeout: 575000 });

import './m4-sla-calendar-core-boundary.test.mjs';
import './m4-sla-calendar-security-boundary.test.mjs';
import './ops-observability-boundary.test.mjs';
import './ops-delivery-dashboard.test.mjs';
import './ops-engine-job-alerting.test.mjs';
import './ops-engine-job-dashboard.test.mjs';
import './ops-grafana-browser.test.mjs';
import './ops-grafana-provisioning.test.mjs';
import './ops-grafana-query-readiness.test.mjs';
import './ops-grafana-browser-transport.test.mjs';
import './ops-grafana-browser-diagnostics.test.mjs';
import './ops-grafana-native-snapshot.test.mjs';
import './ops-grafana-io-window.test.mjs';
import './ops-grafana-startup-evidence.test.mjs';
import './ops-monitoring-isolation.test.mjs';
import './ops-sla-completion-alerts.test.mjs';
