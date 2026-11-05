import {ComponentFixture, TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {NotesCardComponent} from './notes-card.component';

describe('NotesCardComponent', () => {
  let fixture: ComponentFixture<NotesCardComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({imports: [NotesCardComponent], providers: [COMMON_TEST_PROVIDERS]});
    fixture = TestBed.createComponent(NotesCardComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('path', '/schema/app_main/table/account');
    fixture.detectChanges();
  });

  it('lists the notes on its path, oldest first, as plain text', () => {
    const req = http.expectOne(r => r.url.endsWith('notes/path'));
    expect(req.request.params.get('path')).toBe('/schema/app_main/table/account');
    req.flush([
      {id: 2, path: '', text: 'second', createdOn: '2026-02-01T00:00:00Z', author: {email: 'a@example.com'}},
      {id: 1, path: '', text: '<b>first</b>', createdOn: '2026-01-01T00:00:00Z', author: null}
    ]);
    fixture.detectChanges();

    const texts = Array.from(el().querySelectorAll('.note-text')).map(node => node.textContent);
    expect(texts).toEqual(['<b>first</b>', 'second']);
  });

  it('adds a note as a form post and shows it', () => {
    http.expectOne(r => r.url.endsWith('notes/path')).flush([]);
    const component = fixture.componentInstance;
    component.adding = true;
    component.draft = '  kept for the audit job; a+b  ';
    component.save();

    const post = http.expectOne(r => r.method === 'POST' && r.url.endsWith('notes'));
    // decoded as a form would be on the server: '+' must survive as '+'
    const sent = new URLSearchParams(post.request.body as string);
    expect(sent.get('text')).toBe('kept for the audit job; a+b');
    expect(sent.get('path')).toBe('/schema/app_main/table/account');
    post.flush({id: 9, path: '', text: 'kept for the audit job; a+b', createdOn: null, author: null});

    expect(component.notes.map(note => note.text)).toEqual(['kept for the audit job; a+b']);
    expect(component.adding).toBe(false);
  });

  it('says the notes could not be read rather than showing none', () => {
    http.expectOne(r => r.url.endsWith('notes/path')).flush('no', {status: 500, statusText: 'err'});
    fixture.detectChanges();
    expect(el().textContent).toContain('notes could not be read');
  });
});
