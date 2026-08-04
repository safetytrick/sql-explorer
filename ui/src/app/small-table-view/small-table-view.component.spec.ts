import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';
import {provideRouter} from '@angular/router';

import {SmallTableViewComponent} from './small-table-view.component';

describe('SmallTableViewComponent', () => {
  let component: SmallTableViewComponent;
  let fixture: ComponentFixture<SmallTableViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [SmallTableViewComponent],
      providers: [provideRouter([])]
    }).compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SmallTableViewComponent);
    component = fixture.componentInstance;
  });

  /**
   * Regression: a query can reference tables the loaded schema doesn't know
   * (e.g. a misextracted reference), and the metadata endpoint returns an
   * empty body for those — the input arrives null. Rendering must not throw
   * "Cannot read properties of null (reading 'schema')".
   */
  it('renders nothing for a null table', () => {
    component.table = null;

    expect(() => fixture.detectChanges()).not.toThrow();
    expect(fixture.nativeElement.querySelector('h2')).toBeNull();
  });

  it('renders the table link when a structure is present', () => {
    component.table = {
      schema: 'tdb', name: 'member_session', type: 'TABLE', remarks: '',
      foreignKeys: [], indices: [], columns: [], underlyingTables: null, viewColumns: null
    };

    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h2')?.textContent).toContain('tdb.member_session');
  });
});
