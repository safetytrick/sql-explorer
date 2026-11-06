import {Component} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {SqlHighlightDirective} from './sql-highlight.directive';
import {SchemaHint, TableColor} from './paint';

@Component({
  imports: [SqlHighlightDirective],
  template: '<code [sqlHighlight]="sql" [sqlSchema]="schema" (sqlTableHover)="events.push($event)"></code>',
})
class HostComponent {
  sql = 'SELECT a.id FROM account a JOIN account p ON p.id = a.parent_id';
  schema: SchemaHint[] | null = null;
  events: (TableColor | null)[] = [];
}

describe('SqlHighlightDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  const code = () => (fixture.nativeElement as HTMLElement).querySelector('code')!;
  const span = (text: string, nth = 0) => [...code().querySelectorAll('span')].filter(s => s.textContent === text)[nth];
  const over = (el: Element) => el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
  const labels = () => host.events.map(e => e?.label ?? null);

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders the statement synchronously and exactly', () => {
    expect(code().textContent).toBe(host.sql);
  });

  it('emits the instance under the pointer, once per change', () => {
    over(span('a', 0));        // a.id
    over(span('id', 0));       // still a
    over(span('p', 1));        // the alias declaration of p
    expect(labels()).toEqual(['a', 'p']);
    expect(host.events[0]!.name).toBe('account');
  });

  it('keeps the last instance over keywords and spaces, and emits null on leaving', () => {
    over(span('a', 0));
    over(span('JOIN'));
    code().dispatchEvent(new MouseEvent('mouseleave'));
    expect(labels()).toEqual(['a', null]);
  });

  it('draws nothing on hover', () => {
    over(span('a', 0));
    expect(code().querySelector('.sqt-hot')).toBeNull();
  });

  it('keeps the hover through a re-render that adds a schema', async () => {
    over(span('a', 0));
    host.schema = [{name: 'account', columns: ['id', 'parent_id']}];
    fixture.detectChanges();
    await Promise.resolve();
    expect(labels()).toEqual(['a']);
  });
});
