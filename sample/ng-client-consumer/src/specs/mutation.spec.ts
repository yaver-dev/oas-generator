import { Component, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { CreateTenantRequest, TenantsApi } from '@yaver/test';
import { configureClient, createFixture, httpTesting } from './setup';

describe('Observable command APIs', () => {
  beforeEach(() => {
    configureClient({ basePath: 'http://test.api/adm' });
  });

  it('returns a precise Observable of the response body', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { fixture, instance } = createFixture(Host);
    void fixture;
    const request: CreateTenantRequest = {
      name: 'Acme',
      plan: 'pro',
      adminEmail: 'admin@acme.test',
    };

    const promise = firstValueFrom(instance.api.createTenant({ body: request }));
    const req = httpTesting().expectOne('http://test.api/adm/tenants');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Content-Type')).toBe('application/json');
    expect(req.request.body).toEqual(request);

    req.flush({ tenantId: 't-1', name: 'Acme', status: 'active' });
    const detail = await promise;
    expect(detail.tenantId).toBe('t-1');
  });

  it('surfaces HTTP errors through the Observable error channel', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { instance } = createFixture(Host);
    const promise = firstValueFrom(
      instance.api.createTenant({
        body: { name: 'Dup', plan: 'pro', adminEmail: 'a@b.test' },
      }),
    );

    const req = httpTesting().expectOne('http://test.api/adm/tenants');
    req.flush({ title: 'conflict' }, { status: 409, statusText: 'Conflict' });

    await expect(promise).rejects.toBeInstanceOf(HttpErrorResponse);
  });

  it('supports cancellation by unsubscribing', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { instance } = createFixture(Host);
    const subscription = instance.api
      .deleteTenant({ tenantId: 't-9' })
      .subscribe({ next: () => expect.fail('must not emit') });

    const req = httpTesting().expectOne('http://test.api/adm/tenants/t-9');
    expect(req.request.method).toBe('DELETE');
    subscription.unsubscribe();
    expect(req.cancelled).toBe(true);

    httpTesting().verify();
  });

  it('exposes full-response variants with typed HttpResponse', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { instance } = createFixture(Host);
    const promise = firstValueFrom(
      instance.api.createTenantResponse({
        body: { name: 'Acme', plan: 'pro', adminEmail: 'admin@acme.test' },
      }),
    );

    const req = httpTesting().expectOne('http://test.api/adm/tenants');
    req.flush(
      { tenantId: 't-2', name: 'Acme', status: 'active' },
      { status: 201, statusText: 'Created' },
    );

    const response = await promise;
    expect(response.status).toBe(201);
    expect(response.body?.tenantId).toBe('t-2');
  });

  it('resolves void for 204 no-content commands', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { instance } = createFixture(Host);
    const promise = firstValueFrom(instance.api.deleteTenant({ tenantId: 't-3' }));

    const req = httpTesting().expectOne('http://test.api/adm/tenants/t-3');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    await expect(promise).resolves.toBeNull();
  });

  it('keeps commands lazy: instantiating the API issues no request', () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    createFixture(Host);
    httpTesting().verify();
  });
});
