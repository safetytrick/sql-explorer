import { ComponentFixture, TestBed, fakeAsync, tick, waitForAsync } from '@angular/core/testing';
import { of } from 'rxjs';

import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { analyzedSqlServiceStub } from '../testing/analyzed-sql-service-stub';
import { AnalyzedSqlService } from '../analyzed-sql.service';
import { TagViewComponent } from './tag-view.component';
import { Tag } from '../api-types';

describe('TagViewComponent', () => {
  let component: TagViewComponent;
  let fixture: ComponentFixture<TagViewComponent>;

  const tag = (id: number, name: string): Tag => ({ id, name, path: '/schema/tdb/table/branch', type: 'table' } as Tag);

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [TagViewComponent],
      providers: [
        COMMON_TEST_PROVIDERS,
        { provide: AnalyzedSqlService, useValue: analyzedSqlServiceStub({
          getTags: () => of([tag(1, 'core')]),
          addTag: (name: string) => of(tag(99, name)),
          autoCompleteTags: () => of(['core', 'legacy', 'hot'])
        }) }
      ]
    }).compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TagViewComponent);
    component = fixture.componentInstance;
    component.path = '/schema/tdb/table/branch';
    fixture.detectChanges();
  });

  it('renders loaded tags as chips with a real × remove control', () => {
    const chip = fixture.nativeElement.querySelector('.tag-chip');
    expect(chip?.textContent).toContain('core');
    expect(chip?.querySelector('.rx')?.textContent).toBe('×');
  });

  it('adds a tag on enter and clears the input', () => {
    component.tagInput = 'hot';
    component.addTag(component.tagInput);
    fixture.detectChanges();

    expect(component.tags?.map(t => t.name)).toEqual(['core', 'hot']);
    expect(component.tagInput).toBe('');
  });

  it('does not add duplicates (case-insensitive)', () => {
    component.addTag('CORE');
    expect(component.tags?.length).toBe(1);
  });

  it('removes a tag via its chip', () => {
    component.removeTag(component.tags![0]);
    fixture.detectChanges();

    expect(component.tags?.length).toBe(0);
    expect(fixture.nativeElement.querySelector('.tag-chip')).toBeNull();
  });

  it('suggests after the debounce, excluding tags already applied', fakeAsync(() => {
    component.onInput('e');
    tick(200);
    fixture.detectChanges();

    // 'core' is already applied and must not be suggested
    expect(component.suggestions).toEqual(['legacy', 'hot']);
    const items = fixture.nativeElement.querySelectorAll('.tag-sugg-item');
    expect(items.length).toBe(2);
  }));
});
