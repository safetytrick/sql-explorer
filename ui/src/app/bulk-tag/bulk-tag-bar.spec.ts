import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {BulkTagBarComponent} from './bulk-tag-bar.component';

/**
 * A bulk write is confirmed before it happens, and the two things the
 * confirmation must never get wrong are what it will touch and whether it
 * happened at all (sqlx-agl).
 */
describe('BulkTagBarComponent', () => {

  let component: BulkTagBarComponent;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [BulkTagBarComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    component = TestBed.createComponent(BulkTagBarComponent).componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const result = (changed: number, unchanged: number) =>
    ({name: 'system', requested: changed + unchanged, changed, unchanged});

  describe('enumerated mode', () => {

    beforeEach(() => {
      component.targets = [schemaTarget('mysql'), schemaTarget('sys')];
      component.count = 2;
      component.setLabel = '2 of 29 schemas';
    });

    // Nothing may be written before the user says yes.
    it('asks before writing anything', () => {
      component.pick('system', false);

      expect(component.pending?.name).toBe('system');
      http.expectNone('/api/sql-explorer/tags/bulk');
    });

    it('writes the chosen targets on confirm', () => {
      component.pick('system', false);
      component.confirm();

      const request = http.expectOne('/api/sql-explorer/tags/bulk');
      expect(request.request.body)
        .toEqual({name: 'system', targets: [schemaTarget('mysql'), schemaTarget('sys')]});
      request.flush(result(2, 0));
      http.expectOne('/api/sql-explorer/tags').flush([]);
      expect(component.resultText).toBe('2 of 2');
    });

    it('cancelling writes nothing', () => {
      component.pick('system', false);
      component.cancel();
      component.confirm();

      expect(component.pending).toBeNull();
      http.expectNone('/api/sql-explorer/tags/bulk');
    });

    // Removal is the same gesture against the opposite endpoint — and the
    // endpoint matters, because the wrong one strips the tag corpus-wide.
    it('removal goes to the remove endpoint', () => {
      component.pick('system', true);
      component.confirm();

      http.expectOne('/api/sql-explorer/tags/bulk/remove').flush(result(2, 0));
      http.expectOne('/api/sql-explorer/tags').flush([]);
    });

    /**
     * Every row already carried the tag. That is a result, not a failure, and
     * it must not read as one — a bare "nothing changed" would look like a bug.
     */
    it('reports an already-tagged set as a result rather than a failure', () => {
      component.pick('system', false);
      component.confirm();
      http.expectOne('/api/sql-explorer/tags/bulk').flush(result(0, 2));
      http.expectOne('/api/sql-explorer/tags').flush([]);

      expect(component.failed).toBeNull();
      expect(component.resultText).toContain('already');
    });

    it('is disabled when nothing matched', () => {
      component.count = 0;
      expect(component.empty).toBeTrue();
    });
  });

  describe('search mode', () => {

    beforeEach(() => {
      component.search = {};
      component.count = 1847;
    });

    /**
     * The count in the confirmation comes from the server, resolved by the same
     * call that performs the write — not from whatever the page happened to
     * display.
     */
    it('asks the server how many before confirming', () => {
      component.pick('billing', false);

      http.expectOne('/api/sql-explorer/tags/search/preview')
        .flush({count: 1850, limit: 25000, capped: false});

      expect(component.pending?.count).toBe(1850);
    });

    /**
     * Over the cap the action is refused outright. Truncating would leave the
     * user believing the whole set was tagged, which is the failure this design
     * exists to avoid.
     */
    it('refuses rather than truncating when the set is over the cap', () => {
      component.pick('billing', false);
      http.expectOne('/api/sql-explorer/tags/search/preview')
        .flush({count: 30000, limit: 25000, capped: true});

      expect(component.pending?.overCap).toBe(25000);

      component.confirm();
      http.expectNone('/api/sql-explorer/tags/search');
    });

    it('sends the search itself, not a list of paths', () => {
      component.pick('billing', false);
      http.expectOne('/api/sql-explorer/tags/search/preview')
        .flush({count: 3, limit: 25000, capped: false});
      component.confirm();

      const request = http.expectOne('/api/sql-explorer/tags/search');
      expect(request.request.body).toEqual({name: 'billing', search: {}, remove: false});
      request.flush(result(3, 0));
      http.expectOne('/api/sql-explorer/tags').flush([]);
    });
  });

  /** The server's message names what to fix; showing our own would lose it. */
  it('surfaces the server message on failure', () => {
    component.targets = [schemaTarget('mysql')];
    component.count = 1;
    component.pick('system', false);
    component.confirm();

    http.expectOne('/api/sql-explorer/tags/bulk')
      .flush({message: 'too many paths: 30000 exceeds the limit of 25000'},
        {status: 400, statusText: 'Bad Request'});

    expect(component.failed).toContain('exceeds the limit');
  });
});

import {TagPickerComponent} from './tag-picker.component';
import {TagColorsService} from '../tag-colors.service';
import {schemaTarget} from '../tag-path';

/**
 * The picker's job is to offer the right names. Both failures found here were
 * only visible after a write: a name just created was not offered, and
 * removing offered to create (sqlx-agl).
 */
describe('TagPickerComponent', () => {

  let component: TagPickerComponent;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TagPickerComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    component = TestBed.createComponent(TagPickerComponent).componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const summaries = (...names: string[]) => names.map(name => ({name, color: '#4a9eff'}));

  it('offers existing names', () => {
    component.ngOnInit();
    http.expectOne('/api/sql-explorer/tags').flush(summaries('billing', 'audit'));

    expect(component.options).toEqual(['billing', 'audit']);
  });

  /**
   * The one a subscription taken at init misses. `getSummaries(true)` replaces
   * the service's cached observable rather than pushing to its subscribers, so
   * a name a bulk write just created never reaches a picker that subscribed
   * once — you tag three schemas `just-created` and then cannot find it to
   * remove. Re-reading on open is what closes that.
   */
  it('sees names created since it was first opened', () => {
    component.ngOnInit();
    http.expectOne('/api/sql-explorer/tags').flush(summaries('billing'));
    expect(component.options).not.toContain('just-created');

    // what the bulk bar does after a successful write
    TestBed.inject(TagColorsService).getSummaries(true).subscribe();
    http.expectOne('/api/sql-explorer/tags').flush(summaries('billing', 'just-created'));

    component.toggle();

    expect(component.options).toContain('just-created');
  });

  /** Re-reading is free while nothing has refreshed: the summaries are cached. */
  it('does not re-request when nothing has changed', () => {
    component.ngOnInit();
    http.expectOne('/api/sql-explorer/tags').flush(summaries('billing'));

    component.toggle();

    http.expectNone('/api/sql-explorer/tags');
  });

  it('offers an unknown name as one to create', () => {
    component.ngOnInit();
    http.expectOne('/api/sql-explorer/tags').flush(summaries('billing'));
    component.onFilter('brand-new');

    expect(component.newName).toBe('brand-new');
  });

  // Two tags differing only in case would look identical everywhere they are
  // listed, so an existing name is never offered as a new one.
  it('does not offer to create a name that already exists in another case', () => {
    component.ngOnInit();
    http.expectOne('/api/sql-explorer/tags').flush(summaries('Billing'));
    component.onFilter('billing');

    expect(component.newName).toBeNull();
  });

  /** A tag nothing carries cannot be removed from anything. */
  it('never offers to create when it is choosing what to remove', () => {
    component.allowCreate = false;
    component.ngOnInit();
    http.expectOne('/api/sql-explorer/tags').flush(summaries('billing'));
    component.onFilter('brand-new');

    expect(component.newName).toBeNull();
  });
});
