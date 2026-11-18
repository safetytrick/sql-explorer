import {ComponentFixture, TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {AnnotationsViewComponent} from './annotations-view.component';
import {Annotation} from '../annotation.service';
import {columnTarget} from '../tag-path';

describe('AnnotationsViewComponent', () => {
  let fixture: ComponentFixture<AnnotationsViewComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  const inferred: Annotation = {
    id: 3, target: columnTarget('app_main', 'account', 'status'), namespace: 'classification', key: 'kind',
    value: 'enum', confidence: 'inferred', basis: 'four distinct values', sourceKind: 'rule',
    sourceName: 'low-cardinality', author: null, recordedOn: '2026-10-01T00:00:00Z'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({imports: [AnnotationsViewComponent], providers: [COMMON_TEST_PROVIDERS]});
    fixture = TestBed.createComponent(AnnotationsViewComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('target', columnTarget('app_main', 'account', 'status'));
    fixture.detectChanges();
  });

  it('asks for its one target and shows each fact with who stated it', () => {
    const req = http.expectOne(r => r.url.endsWith('annotations/targets'));
    expect(req.request.body).toEqual([columnTarget('app_main', 'account', 'status')]);
    req.flush([[inferred]]);
    fixture.detectChanges();

    expect(el().querySelector('.ann-value')?.textContent).toBe('enum');
    expect(el().querySelector('.ann')?.getAttribute('title')).toContain('rule low-cardinality');
    expect(el().querySelector('.ann')?.classList).toContain('inferred');
  });

  it('asks once for a target bound as a fresh object on every change detection', () => {
    http.expectOne(r => r.url.endsWith('annotations/targets')).flush([[]]);
    fixture.componentRef.setInput('target', columnTarget('app_main', 'account', 'status'));
    fixture.detectChanges();
    http.expectNone(r => r.url.endsWith('annotations/targets'));

    fixture.componentRef.setInput('target', columnTarget('app_main', 'account', 'code'));
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('annotations/targets'));
  });

  it('renders nothing when nothing is recorded', () => {
    http.expectOne(r => r.url.endsWith('annotations/targets')).flush([[]]);
    fixture.detectChanges();

    expect(el().querySelector('.annotations')).toBeNull();
  });

  it('confirms by appending, and shows the confirmed row in place', () => {
    http.expectOne(r => r.url.endsWith('annotations/targets')).flush([[inferred]]);
    fixture.detectChanges();

    (el().querySelector('.ann-confirm') as HTMLButtonElement).click();
    const post = http.expectOne(r => r.method === 'POST' && r.url.endsWith('annotation/3/confirm'));
    post.flush({...inferred, id: 4, confidence: 'confirmed', sourceKind: 'person', sourceName: null,
      author: {email: 'reader@example.com'}});
    fixture.detectChanges();

    expect(el().querySelector('.ann')?.classList).toContain('confirmed');
    expect(el().querySelector('.ann-confirm')).toBeNull();
  });
});
