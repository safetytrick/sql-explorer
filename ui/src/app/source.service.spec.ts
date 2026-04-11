import { TestBed } from '@angular/core/testing';

import { SourceService } from './source.service';

describe('SourceService', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('should be created', () => {
    const service: SourceService = TestBed.inject(SourceService);
    expect(service).toBeTruthy();
  });
});
