import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';
import {ActivatedRoute, convertToParamMap, ParamMap, Router} from '@angular/router';
import {BehaviorSubject} from 'rxjs';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {TagFilterStripComponent} from './tag-filter-strip.component';

/**
 * The strip's own logic is the tri-state cycle and what it writes to the URL.
 * The include/exclude *semantics* are the codec's and are tested there.
 */
describe('TagFilterStripComponent', () => {

  let fixture: ComponentFixture<TagFilterStripComponent>;
  let component: TagFilterStripComponent;

  function row(...tags: string[]) {
    return {tags: tags.map(name => ({id: name, name, path: null, color: null}))} as never;
  }

  function chips(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.strip-chip'));
  }

  function chip(name: string): HTMLElement {
    return chips().find(el => (el.textContent || '').includes(name)) as HTMLElement;
  }

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [TagFilterStripComponent],
      providers: [COMMON_TEST_PROVIDERS, {
        provide: ActivatedRoute,
        useValue: (() => {
          const params = new BehaviorSubject<ParamMap>(convertToParamMap({}));
          return {queryParamMap: params, get snapshot() { return {queryParamMap: params.value}; }};
        })()
      }]
    }).compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TagFilterStripComponent);
    component = fixture.componentInstance;
    spyOn(TestBed.inject(Router), 'navigate').and.returnValue(Promise.resolve(true));
    fixture.componentRef.setInput('rows', [row('system'), row('core'), row('core', 'pii')]);
    fixture.detectChanges();
  });

  // ── the cycle ──

  it('starts every chip unselected', () => {
    expect(component.isOn('core')).toBeFalse();
    expect(component.isExcluded('core')).toBeFalse();
  });

  it('cycles off → include → exclude → off', () => {
    component.toggle('system');
    expect([component.isOn('system'), component.isExcluded('system')]).toEqual([true, false]);

    component.toggle('system');
    expect([component.isOn('system'), component.isExcluded('system')]).toEqual([false, true]);

    component.toggle('system');
    expect([component.isOn('system'), component.isExcluded('system')]).toEqual([false, false]);
  });

  it('never holds a tag as both included and excluded', () => {
    component.toggle('core');
    component.toggle('core');

    expect(component.state.tags).not.toContain('core');
    expect(component.state.excludeTags).toContain('core');
  });

  // ── what it filters ──

  /** The reason exclusion exists: subtract the noise, keep everything else. */
  it('counts every row that does not carry the excluded tag', () => {
    component.toggle('system');
    component.toggle('system');

    expect(component.matched).toBe(2);
  });

  it('treats the strip as active when only an exclusion is set', () => {
    component.toggle('system');
    component.toggle('system');

    expect(component.active).toBeTrue();
  });

  it('clears both halves', () => {
    component.toggle('core');
    component.toggle('system');
    component.toggle('system');

    component.clear();

    expect(component.state.tags).toEqual([]);
    expect(component.state.excludeTags).toEqual([]);
  });

  /**
   * An excluded tag hides its own rows, so its count can reach zero on the
   * page. It still has to be offered, or the filter cannot be switched off.
   */
  it('keeps offering an excluded tag no row carries any more', () => {
    fixture.componentRef.setInput('rows', [row('core')]);
    component.toggle('system');
    component.toggle('system');
    fixture.detectChanges();

    expect(component.stripTags.map(tag => tag.name)).toContain('system');
  });

  // ── rendering ──

  it('marks the excluded chip as more than unselected', () => {
    component.toggle('core');
    component.toggle('core');
    fixture.detectChanges();

    expect(chip('core').classList).toContain('off');
    expect(chip('core').classList).not.toContain('on');
  });

  it('says what the next click will do', () => {
    expect(component.chipTitle({name: 'core', count: 2})).toContain('Click to require it');

    component.toggle('core');
    expect(component.chipTitle({name: 'core', count: 2})).toContain('hide them instead');

    component.toggle('core');
    expect(component.chipTitle({name: 'core', count: 2})).toContain('stop filtering');
  });


  // ── exclusion reaches through the container ──

  /**
   * `system` is typically attached to schemas, not to individual tables, so
   * without inherited tags the chip would never appear on /tables — the strip
   * would be unable to offer the filter it exists for.
   */
  it('offers a tag that only the container carries', () => {
    fixture.componentRef.setInput('rows',
      [{tags: [], inheritedTags: ['system']}, {tags: [], inheritedTags: ['app']}]);
    fixture.detectChanges();

    expect(component.stripTags.map(tag => tag.name)).toContain('system');
  });

  it('counts a row once when it both carries and inherits a tag', () => {
    fixture.componentRef.setInput('rows', [{tags: [{name: 'core'}], inheritedTags: ['core']}]);
    fixture.detectChanges();

    expect(component.stripTags.find(tag => tag.name === 'core')?.count).toBe(1);
  });

  it('hides a row whose container is excluded, though the row is untagged', () => {
    fixture.componentRef.setInput('rows',
      [{tags: [], inheritedTags: ['system']}, {tags: [], inheritedTags: ['app']}]);
    fixture.detectChanges();

    component.toggle('system');
    component.toggle('system');

    expect(component.matched).toBe(1);
  });


  // ── the URL is the source of truth, not this component (sqlx-zl1) ──

  describe('when something else writes the parameter', () => {

    function urlSays(params: Record<string, string>) {
      (TestBed.inject(ActivatedRoute).queryParamMap as BehaviorSubject<ParamMap>)
        .next(convertToParamMap(params));
      fixture.detectChanges();
    }

    /**
     * The schema-tree sidebar writes the same `schemaTagsExclude`. Reading the
     * snapshot once left this strip showing one state while the URL and the
     * page showed another — which is how an exclude set in the sidebar could
     * look applied and not be.
     */
    it('picks up an exclusion set elsewhere', () => {
      urlSays({schemaTagsExclude: 'system'});

      expect(component.isExcluded('system')).toBeTrue();
      expect(component.state.excludeTags).toEqual(['system']);
    });

    it('emits so the page re-filters', () => {
      const seen: string[][] = [];
      component.filterChange.subscribe(state => seen.push(state.excludeTags));

      urlSays({schemaTagsExclude: 'system'});

      expect(seen).toContain(['system']);
    });

    /** Its own write comes back through the same channel; that must not re-emit. */
    it('does not react to the echo of its own write', () => {
      component.toggle('system');
      component.toggle('system');
      let emissions = 0;
      component.filterChange.subscribe(() => emissions++);

      urlSays({schemaTagsExclude: 'system'});

      expect(emissions).toBe(0);
    });
  });

  // ── url ──

  /** One spelling, and it is the workbench's — see sqlx-uxo. */
  it('writes the exclusion as the workbench scope parameter, and clears it again', () => {
    const navigate = TestBed.inject(Router).navigate as jasmine.Spy;

    component.toggle('system');
    component.toggle('system');
    expect(navigate.calls.mostRecent().args[1].queryParams['schemaTagsExclude']).toBe('system');

    component.toggle('system');
    expect(navigate.calls.mostRecent().args[1].queryParams['schemaTagsExclude']).toBeNull();
  });
});
