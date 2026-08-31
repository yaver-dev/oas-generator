import { Component, inject } from '@angular/core';
import { HttpInterceptorFn, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { TenantsApi, injectListTenantsResource, provideYaverTestClient } from '@yaver/test';
import { yaverTestClientConfig } from '@yaver/test/testing';
import { createFixture, httpTesting } from './setup';

describe('client provider', () => {
  it('validates basePath at provider time', () => {
    expect(() => provideYaverTestClient({ basePath: '' })).toThrowError(/basePath/);
  });

  it('creates the testing config helper with the same shape', () => {
    const config = yaverTestClientConfig('http://test.api', true);
    expect(config).toEqual({ basePath: 'http://test.api', withCredentials: true });
    expect(yaverTestClientConfig('http://x')).toEqual({
      basePath: 'http://x',
      withCredentials: false,
    });
  });

  it('does not install zone.js', () => {
    expect((globalThis as Record<string, unknown>).Zone).toBeUndefined();
  });

  it('lets application interceptors observe generated commands and queries', async () => {
    const observed: { method: string; url: string; forwarded: boolean }[] = [];
    const markingInterceptor: HttpInterceptorFn = (req, next) => {
      observed.push({ method: req.method, url: req.url, forwarded: true });
      return next(req);
    };

    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
      readonly tenants = injectListTenantsResource(() => ({}));
    }

    const { TestBed } = await import('@angular/core/testing');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([markingInterceptor])),
        provideHttpClientTesting(),
        provideYaverTestClient({ basePath: 'http://test.api/adm' }),
      ],
    });
    const fixture = TestBed.createComponent(Host);
    const host = fixture.componentInstance;
    fixture.detectChanges();

    const queryReq = httpTesting().expectOne('http://test.api/adm/tenants');
    expect(queryReq.request.method).toBe('GET');
    queryReq.flush({ items: [], total: 0 });
    await fixture.whenStable();

    const done = firstValueFrom(
      host.api.createTenant({
        body: { name: 'Acme', plan: 'pro', adminEmail: 'a@b.test' },
      }),
    );
    const cmdReq = httpTesting().expectOne('http://test.api/adm/tenants');
    expect(cmdReq.request.method).toBe('POST');
    cmdReq.flush({ tenantId: 't-1', name: 'Acme', status: 'active' });
    await done;

    // Both the httpResource query and the Observable command traversed the
    // application interceptor chain exactly once.
    expect(observed).toEqual([
      { method: 'GET', url: 'http://test.api/adm/tenants', forwarded: true },
      { method: 'POST', url: 'http://test.api/adm/tenants', forwarded: true },
    ]);
  });
});
