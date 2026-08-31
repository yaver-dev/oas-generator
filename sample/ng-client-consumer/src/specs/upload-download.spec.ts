import { Component, inject } from '@angular/core';
import { HttpEventType } from '@angular/common/http';
import { first, firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { ReportsApi, TenantsApi } from '@yaver/test';
import { configureClient, createFixture, httpTesting } from './setup';

describe('uploads, downloads and streams', () => {
  beforeEach(() => {
    configureClient({ basePath: 'http://test.api/adm' });
  });

  it('sends multipart uploads as FormData without a manual boundary header', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { instance } = createFixture(Host);
    const file = new Blob(['logo-bytes'], { type: 'image/png' });
    const promise = firstValueFrom(
      instance.api.uploadTenantLogo({ tenantId: 't-1', file, label: 'main' }),
    );

    const req = httpTesting().expectOne('http://test.api/adm/tenants/t-1/logo');
    expect(req.request.body).toBeInstanceOf(FormData);
    // The browser must set multipart Content-Type including the boundary;
    // the client deliberately does not send one manually.
    expect(req.request.headers.get('Content-Type')).toBeNull();

    req.flush({ url: 'http://cdn.test/logo.png' });
    const logo = await promise;
    expect(logo.url).toBe('http://cdn.test/logo.png');
  });

  it('emits upload progress events through the events variant', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { instance } = createFixture(Host);
    const file = new Blob(['xxxxxxxxxxxxxxxx'], { type: 'image/png' });
    const events: HttpEventType[] = [];
    // Progress streams emit multiple events; collect them manually until the
    // response event completes the subscription.
    const done = new Promise<void>((resolve) => {
      instance.api
        .uploadTenantLogoEvents({ tenantId: 't-1', file, label: 'main' })
        .subscribe({ next: (event) => { events.push(event.type); if (event.type === HttpEventType.Response) resolve(); } });
    });

    const req = httpTesting().expectOne('http://test.api/adm/tenants/t-1/logo');
    req.flush({ url: 'http://cdn.test/logo.png' });
    await done;

    expect(events).toContain(HttpEventType.Response);
  });

  it('downloads binary reports as Blob', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(ReportsApi);
    }

    const { instance } = createFixture(Host);
    const promise = firstValueFrom(instance.api.downloadReport({ reportId: 'r-1' }));

    const req = httpTesting().expectOne('http://test.api/adm/reports/r-1');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['pdf-bytes'], { type: 'application/pdf' }));

    const blob = await promise;
    expect(blob).toBeInstanceOf(Blob);
  });

  it('exposes binary export progress through the events variant', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(ReportsApi);
    }

    const { instance } = createFixture(Host);
    const done = firstValueFrom(
      instance.api
        .exportReportsEvents({ body: { from: '2026-01-01' } })
        .pipe(first((event) => event.type === HttpEventType.Response)),
    );

    const req = httpTesting().expectOne('http://test.api/adm/reports/export');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['zip']));

    const event = await done;
    expect(event.type).toBe(HttpEventType.Response);
  });

  it('streams text/event-stream responses as string events', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(ReportsApi);
    }

    const { instance } = createFixture(Host);
    const done = firstValueFrom(
      instance.api
        .streamEventsEvents({})
        .pipe(first((event) => event.type === HttpEventType.Response)),
    );

    const req = httpTesting().expectOne('http://test.api/adm/events/stream');
    expect(req.request.responseType).toBe('text');
    expect(req.request.reportProgress).toBe(true);

    req.flush('data: hello\n\n', { status: 200, statusText: 'OK' });
    const event = await done;
    expect(event.type).toBe(HttpEventType.Response);
    const body = 'body' in event ? (event.body as string | null) : null;
    expect(body).toContain('data: hello');
  });

  it('returns typed void for bodyless audit commands', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(ReportsApi);
    }

    const { instance } = createFixture(Host);
    const promise = firstValueFrom(
      instance.api.logAuditEvent({ body: { message: 'hi' } }),
    );

    const req = httpTesting().expectOne('http://test.api/adm/audit/log');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await expect(promise).resolves.toBeNull();
  });
});
