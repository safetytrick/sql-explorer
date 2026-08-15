import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {of} from 'rxjs';
import {TagFieldComponent} from './tag-field.component';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {TagColorsService} from '../tag-colors.service';
import {Tag} from '../api-types';

function tag(id: number, name: string): Tag {
  return {id, name, path: '/schema/app_other', type: 'schema', author: null, createdOn: null};
}

describe('TagFieldComponent', () => {

  let fixture: ComponentFixture<TagFieldComponent>;
  let component: TagFieldComponent;
  let sqlService: jasmine.SpyObj<AnalyzedSqlService>;

  beforeEach(async () => {
    sqlService = jasmine.createSpyObj('AnalyzedSqlService', ['getTags', 'addTag', 'removeTag']);
    sqlService.getTags.and.returnValue(of([]));
    sqlService.removeTag.and.returnValue(of(undefined));

    await TestBed.configureTestingModule({
      imports: [TagFieldComponent],
      providers: [
        provideRouter([]),
        {provide: AnalyzedSqlService, useValue: sqlService},
        {
          provide: TagColorsService,
          useValue: {
            getSummaries: () => of([
              {name: 'clickhouse', color: '#a78bfa', queries: 0, tables: 19, schemas: 0, other: 0, lastUsed: null},
              {name: 'system', color: null, queries: 0, tables: 0, schemas: 4, other: 0, lastUsed: null}
            ]),
            chipStyle: () => ({color: '#fff', background: '#000'})
          }
        }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TagFieldComponent);
    component = fixture.componentInstance;
    component.path = '/schema/app_other';
  });

  it('removes by association id, which is the only signal the delete worked', () => {
    component.tags = [tag(7, 'clickhouse'), tag(9, 'system')];
    fixture.detectChanges();

    component.detach(tag(7, 'clickhouse'), new MouseEvent('click'));

    expect(sqlService.removeTag).toHaveBeenCalledWith(7);
    expect(component.tags!.map(t => t.name)).toEqual(['system']);
  });

  it('does not offer a tag that is already applied', () => {
    component.tags = [tag(7, 'clickhouse')];
    fixture.detectChanges();

    expect(component.options).toEqual(['system']);
  });

  it('offers to create only a name that is not already a tag', () => {
    component.tags = [];
    fixture.detectChanges();

    component.onFilter('clickhouse');
    expect(component.creatable).toBeNull();

    component.onFilter('gdpr');
    expect(component.creatable).toBe('gdpr');
    expect(component.options).toEqual([]);
  });

  it('attaches against the path it was given and keeps the new name selectable', () => {
    component.tags = [];
    fixture.detectChanges();
    sqlService.addTag.and.returnValue(of(tag(11, 'gdpr')));

    component.attach('gdpr');

    expect(sqlService.addTag).toHaveBeenCalledWith('gdpr', '/schema/app_other');
    expect(component.tags!.map(t => t.name)).toEqual(['gdpr']);
  });

  it('ignores a tag it already carries rather than posting a duplicate', () => {
    component.tags = [tag(7, 'clickhouse')];
    fixture.detectChanges();

    component.attach('CLICKHOUSE');

    expect(sqlService.addTag).not.toHaveBeenCalled();
  });

  it('fetches its own tags when none are supplied', () => {
    component.tags = null;
    fixture.detectChanges();

    expect(sqlService.getTags).toHaveBeenCalledWith('/schema/app_other');
  });
});
