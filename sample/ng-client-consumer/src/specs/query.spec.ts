import { Component, signal } from '@angular/core';
import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  ListTenantsParams,
  injectGetTenantResource,
  injectListTenantsResource,
} from '@yaver/test';
import { configureClient, createFixture, httpTesting } from './setup';

describe('httpResource queries', () => {
  beforeEach(() => {
    configureClient({ basePath: 'http://test.api/adm' });
  });

  it('issues a GET with serialized query parameters', async () => {
    @Component({ template: '' })
    class Host {
      readonly params = signal<ListTenantsParams | undefined>({
        term: 'acme',
        limit: 25,
        tags: ['a', 'b'],
      });
      readonly tenants = injectListTenantsResource(() => this.params());
    }

    const { fixture, instance } = createFixture(Host);
    fixture.detectChanges();

    const req = httpTesting().expectOne(
      (r) => r.method === 'GET' && r.url === 'http://test.api/adm/tenants',
    );
    expect(req.request.params.get('term')).toBe('acme');
    expect(req.request.params.get('limit')).toBe('25');
    expect(req.request.params.getAll('tags')).toEqual(['a', 'b']);
    expect(req.request.params.get('status')).toBeNull();

    req.flush({ items: [], total: 0 });
    await fixture.whenStable();
    expect(instance.tenants.value()).toEqual({ items: [], total: 0 });
    expect(instance.tenants.hasValue()).toBe(true);
  });

  it('stays idle when the params factory returns undefined', async () => {
    @Component({ template: '' })
    class Host {
      readonly params = signal<ListTenantsParams | undefined>(undefined);
      readonly tenants = injectListTenantsResource(() => this.params());
    }

    const { fixture, instance } = createFixture(Host);
    fixture.detectChanges();
    await fixture.whenStable();

    httpTesting().verify();
    expect(instance.tenants.status()).toBe('idle');
    expect(instance.tenants.value()).toBeUndefined();
  });

  it('re-issues the request and cancels the obsolete one when params change', async () => {
    @Component({ template: '' })
    class Host {
      readonly params = signal<ListTenantsParams | undefined>({ term: 'one' });
      readonly tenants = injectListTenantsResource(() => this.params());
    }

    const { fixture, instance } = createFixture(Host);
    fixture.detectChanges();

    const first = httpTesting().expectOne(
      (r) => r.url === 'http://test.api/adm/tenants' && r.params.get('term') === 'one',
    );
    first.flush({ items: [], total: 0 });
    await fixture.whenStable();

    instance.params.set({ term: 'two' });
    fixture.detectChanges();

    const second = httpTesting().expectOne(
      (r) => r.url === 'http://test.api/adm/tenants' && r.params.get('term') === 'two',
    );
    // The obsolete request of the previous parameter set is aborted.
    expect(first.cancelled).toBe(true);

    second.flush({ items: [{ tenantId: 't-2', name: 'Two', status: 'active' }], total: 1 });
    await fixture.whenStable();
    expect(instance.tenants.value()?.items[0]?.tenantId).toBe('t-2');
  });

  it('exposes error state and allows reload', async () => {
    @Component({ template: '' })
    class Host {
      readonly params = signal<ListTenantsParams | undefined>({});
      readonly tenants = injectListTenantsResource(() => this.params());
    }

    const { fixture, instance } = createFixture(Host);
    fixture.detectChanges();

    const controller = httpTesting();
    controller
      .expectOne((r) => r.url === 'http://test.api/adm/tenants')
      .flush(
        { title: 'nope' },
        { status: HttpStatusCode.InternalServerError, statusText: 'boom' },
      );
    await fixture.whenStable();

    const resource = instance.tenants;
    expect(resource.status()).toBe('error');
    expect(resource.error()).toBeInstanceOf(HttpErrorResponse);
    expect(resource.isLoading()).toBe(false);

    resource.reload();
    fixture.detectChanges();
    controller
      .expectOne((r) => r.url === 'http://test.api/adm/tenants')
      .flush({ items: [{ tenantId: 't1', name: 'n', status: 'active' }], total: 1 });
    await fixture.whenStable();

    expect(resource.status()).toBe('resolved');
    expect(resource.value()?.items[0]?.tenantId).toBe('t1');
  });

  it('interpolates path parameters and supports conditional queries', async () => {
    @Component({ template: '' })
    class Host {
      readonly tenantId = signal<string | undefined>('tenant-7');
      readonly tenant = injectGetTenantResource(() => {
        const tenantId = this.tenantId();
        return tenantId ? { tenantId } : undefined;
      });
    }

    const { fixture } = createFixture(Host);
    fixture.detectChanges();

    const req = httpTesting().expectOne('http://test.api/adm/tenants/tenant-7');
    expect(req.request.method).toBe('GET');
    req.flush({ tenantId: 'tenant-7', name: 'Seven', status: 'active' });
    await fixture.whenStable();

    // Switching to undefined (conditional query) must not fire a new request.
    fixture.componentInstance.tenantId.set(undefined);
    fixture.detectChanges();
    await fixture.whenStable();
    httpTesting().verify();
  });

  it('fails into the error state when a required parameter is missing', async () => {
    @Component({ template: '' })
    class Host {
      readonly tenant = injectGetTenantResource(
        () => ({ tenantId: undefined as unknown as string }),
      );
    }

    const { fixture, instance } = createFixture(Host);
    fixture.detectChanges();
    await fixture.whenStable();

    // No request is sent; the validation error surfaces on the resource.
    httpTesting().verify();
    expect(instance.tenant.status()).toBe('error');
    expect(String(instance.tenant.error())).toContain(
      'Required parameter tenantId was null or undefined when calling getTenant.',
    );
  });

  it('sends the configured Accept header', async () => {
    @Component({ template: '' })
    class Host {
      readonly tenants = injectListTenantsResource(() => ({}));
    }

    const { fixture } = createFixture(Host);
    fixture.detectChanges();

    const req = httpTesting().expectOne('http://test.api/adm/tenants');
    expect(req.request.headers.get('Accept')).toBe('application/json');
    req.flush({ items: [], total: 0 });
    await fixture.whenStable();
  });
});
