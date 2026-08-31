import { Component } from '@angular/core';
import { Type } from '@angular/core';
import { HttpInterceptorFn, provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  BrowserDynamicTestingModule,
  platformBrowserDynamicTesting,
} from '@angular/platform-browser-dynamic/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideYaverTestClient, YaverTestClientConfig } from '@yaver/test';

// Vitest does not auto-initialize the Angular testing environment.
TestBed.initTestEnvironment(
  [BrowserDynamicTestingModule],
  platformBrowserDynamicTesting());

// ...nor does it auto-teardown TestBed between tests.
import { afterEach } from 'vitest';
afterEach(() => {
  TestBed.resetTestingModule();
});

/**
 * Zoneless test-bed setup. The consumer application owns provideHttpClient
 * and interceptors; the generated client only receives its configuration.
 */
export function configureClient(
  config: YaverTestClientConfig,
  interceptors: HttpInterceptorFn[] = [],
): void {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideHttpClient(),
      provideHttpClientTesting(),
      provideYaverTestClient(config),
    ],
  });
}

/**
 * Minimal host component so resources are created inside an injector with a
 * real destruction lifecycle (assertedInInjectionContext must pass).
 */
@Component({ template: '' })
export class HostComponent {}

export function createFixture<T>(component: Type<T>): { fixture: ComponentFixture<T>; instance: T } {
  const fixture = TestBed.createComponent(component);
  return { fixture, instance: fixture.componentInstance };
}

export function httpTesting(): HttpTestingController {
  return TestBed.inject(HttpTestingController);
}
