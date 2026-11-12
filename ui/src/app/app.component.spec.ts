import { TestBed, waitForAsync } from '@angular/core/testing';
import { COMMON_TEST_PROVIDERS } from './testing/common-test-providers';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [COMMON_TEST_PROVIDERS],
    }).compileComponents();
  }));

  it('should create the app', waitForAsync(() => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.debugElement.componentInstance;
    expect(app).toBeTruthy();
  }));

  it('should have as title \'app\'', waitForAsync(() => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.debugElement.componentInstance;
    expect(app.title).toEqual('app');
  }));

  it('should render the app shell', waitForAsync(() => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.debugElement.nativeElement;
    expect(compiled.querySelector('app-nav-bar')).toBeTruthy();
    expect(compiled.querySelector('router-outlet')).toBeTruthy();
  }));

  it('knows the workbench with the environment in front of it (sqlx-e13.7)', () => {
    expect(AppComponent.isTreePage('/queries?q=x')).toBeTrue();
    expect(AppComponent.isTreePage('/prod/queries')).toBeTrue();
    expect(AppComponent.isTreePage('/prod/tables')).toBeFalse();
  });
});
