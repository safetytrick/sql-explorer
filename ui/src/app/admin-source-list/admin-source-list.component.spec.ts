import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { AdminSourceListComponent } from './admin-source-list.component';

describe('AdminSourceListComponent', () => {
  let component: AdminSourceListComponent;
  let fixture: ComponentFixture<AdminSourceListComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [AdminSourceListComponent]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(AdminSourceListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // sqlx-n5j.4: what each server is for, and where sampling would go
  it('lists each server with its roles and says where sampling goes', () => {
    component.environment = {
      name: 'local',
      servers: [
        {name: 'explorer', schemaLoaded: true, roles: ['primary']},
        {name: 'replica', schemaLoaded: false, roles: ['replica', 'sampling']},
        {name: 'legacy', schemaLoaded: false}
      ],
      sources: {loaded: false, sourceCount: 0, loadedOn: null, importStats: null},
      sampling: {server: 'replica', available: true, reason: null}
    };
    fixture.detectChanges();
    const rows = Array.from(fixture.nativeElement.querySelectorAll('tbody tr')) as HTMLElement[];

    expect(rows[0].textContent).toContain('primary');
    expect(rows[1].querySelector('.role-chip.sampling')?.textContent).toBe('sampling');
    expect(rows[2].textContent).toContain('—');
    expect(fixture.nativeElement.querySelector('.sampling-line').textContent).toContain('replica');
  });

  it('says why nothing is sampled', () => {
    component.environment = {
      name: 'local', servers: [], sources: {loaded: false, sourceCount: 0, loadedOn: null, importStats: null},
      sampling: {server: null, available: false, reason: 'environment local has no server with the sampling role'}
    };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.sampling-line.unavailable').textContent)
      .toContain('no server with the sampling role');
  });
});
