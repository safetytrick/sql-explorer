import {Component} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {Title} from '@angular/platform-browser';
import {provideRouter, TitleStrategy} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {formatPageTitle, PageTitleStrategy, paramTitle} from './page-title';
import {appRoutes} from './app.routes';

@Component({ template: '' })
class BlankComponent {}

describe('PageTitleStrategy', () => {

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'tags', component: BlankComponent, title: 'Tags' },
          { path: 'table/:schema/:name', component: BlankComponent,
            title: paramTitle(p => `${p['schema']}.${p['name']}`) },
          { path: 'untitled', component: BlankComponent }
        ]),
        { provide: TitleStrategy, useClass: PageTitleStrategy }
      ]
    });
  });

  it('titles a page from its route, in one spelling', async () => {
    const harness = await RouterTestingHarness.create('/tags');
    expect(TestBed.inject(Title).getTitle()).toBe('Tags · SQL Explorer');

    await harness.navigateByUrl('/table/app_main/account');
    expect(TestBed.inject(Title).getTitle()).toBe('app_main.account · SQL Explorer');
  });

  it('does not keep the previous page\'s title on a route without one', async () => {
    const harness = await RouterTestingHarness.create('/tags');
    await harness.navigateByUrl('/untitled');
    expect(TestBed.inject(Title).getTitle()).toBe('SQL Explorer');
  });

  it('formats a page-supplied title the same way', () => {
    expect(formatPageTitle('Query · account')).toBe('Query · account · SQL Explorer');
    expect(formatPageTitle(null)).toBe('SQL Explorer');
  });
});

describe('appRoutes titles', () => {
  it('every routed page declares a title, so none can keep the last page\'s', () => {
    const untitled = appRoutes
      .filter(route => route.component && !route.title)
      .map(route => route.path);
    expect(untitled).toEqual([]);
  });
});
