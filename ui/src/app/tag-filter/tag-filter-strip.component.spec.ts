import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';
import {Router} from '@angular/router';
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
      providers: [COMMON_TEST_PROVIDERS]
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

  // ── url ──

  it('writes the exclusion into the url and clears it again', () => {
    const navigate = TestBed.inject(Router).navigate as jasmine.Spy;

    component.toggle('system');
    component.toggle('system');
    expect(navigate.calls.mostRecent().args[1].queryParams['tagsExclude']).toBe('system');

    component.toggle('system');
    expect(navigate.calls.mostRecent().args[1].queryParams['tagsExclude']).toBeNull();
  });
});
