import { Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { form, submit } from '@angular/forms/signals';
import { beforeEach, describe, expect, it } from 'vitest';
import { CreateTenantRequest, TenantsApi } from '@yaver/test';
import { createTenantRequestFormMeta, createTenantRequestSchema } from '@yaver/test/forms';
import { configureClient, createFixture, httpTesting } from './setup';
import { TestBed } from '@angular/core/testing';

/** `form()` creates resources internally and must run in an injection context. */
function inInjection<T>(fn: () => T): T {
  return TestBed.runInInjectionContext(fn);
}

describe('Signal Forms integration', () => {
  beforeEach(() => {
    configureClient({ basePath: 'http://test.api/adm' });
  });

  it('compiles the generated schema and reports constraint violations', () => {
    const model = signal<CreateTenantRequest>({
      name: 'A',
      plan: 'pro',
      adminEmail: 'not-an-email',
    });
    const tenantForm = inInjection(() => form(model, createTenantRequestSchema));

    expect(tenantForm().valid()).toBe(false);
    expect(tenantForm.name().errors().length).toBeGreaterThan(0);
    expect(tenantForm.adminEmail().errors().length).toBeGreaterThan(0);

    model.set({
      name: 'Acme Inc.',
      plan: 'pro',
      adminEmail: 'admin@acme.test',
      maxUsers: 50,
    });
    expect(tenantForm().valid()).toBe(true);
  });

  it('submits through the typed command API at the imperative boundary', async () => {
    @Component({ template: '' })
    class Host {
      readonly api = inject(TenantsApi);
    }

    const { instance } = createFixture(Host);
    const model = signal<CreateTenantRequest>({
      name: 'Acme Inc.',
      plan: 'starter',
      adminEmail: 'admin@acme.test',
    });
    let submitted: CreateTenantRequest | undefined;

    const tenantForm = inInjection(() =>
      form(model, createTenantRequestSchema, {
        submission: {
          action: async (field) => {
            submitted = field().value();
            await firstValueFrom(instance.api.createTenant({ body: field().value() }));
            return undefined;
          },
        },
      }),
    );

    expect(tenantForm().valid()).toBe(true);
    // submit() awaits the action, whose Observable only completes when the
    // request is flushed — start it, then flush from the test.
    const completedPromise = submit(tenantForm);

    const req = httpTesting().expectOne('http://test.api/adm/tenants');
    expect(req.request.body.name).toBe('Acme Inc.');
    req.flush({ tenantId: 't-1', name: 'Acme Inc.', status: 'active' });

    expect(await completedPromise).toBe(true);
    expect(submitted?.name).toBe('Acme Inc.');
  });

  it('keeps optional and nullable fields out of required validation', () => {
    // Only the required fields are present: optional/nullable fields
    // (notes, homepage, labels, contacts, primaryContact, metadata) are
    // NOT required by the generated schema.
    const model = signal<CreateTenantRequest>({
      name: 'Acme Inc.',
      plan: 'pro',
      adminEmail: 'admin@acme.test',
    });
    const tenantForm = inInjection(() => form(model, createTenantRequestSchema));
    expect(tenantForm().valid()).toBe(true);
  });

  it('validates nested contacts and nullable fields without failing them', () => {
    const model = signal<CreateTenantRequest>({
      name: 'Acme Inc.',
      plan: 'pro',
      adminEmail: 'admin@acme.test',
      primaryContact: { email: 'bad' },
      contacts: [{ email: 'good@acme.test', phone: 'nope' }],
    });
    const tenantForm = inInjection(() => form(model, createTenantRequestSchema));

    // Nested email constraint from the Contact model applies.
    const primary = tenantForm.primaryContact!();
    void primary;
    expect(tenantForm.primaryContact!.email().errors().length).toBeGreaterThan(0);
    // Pattern on nested phone applies to non-null values only.
    const firstContact = tenantForm.contacts![0]!;
    expect(firstContact.phone!().errors().length).toBeGreaterThan(0);

    model.set({
      name: 'Acme Inc.',
      plan: 'pro',
      adminEmail: 'admin@acme.test',
      homepage: null,
    });
    expect(tenantForm().valid()).toBe(true);
  });

  it('exposes form metadata only for properties declaring x-yaver-form', () => {
    expect(createTenantRequestFormMeta.name?.label).toBe('Tenant name');
    expect(createTenantRequestFormMeta.plan?.control).toBe('select');
    expect(createTenantRequestFormMeta.plan?.options?.[0]).toEqual({
      value: 'starter',
      label: 'Starter',
    });
    // properties without the extension have no metadata
    expect(createTenantRequestFormMeta.metadata).toBeUndefined();
  });
});
