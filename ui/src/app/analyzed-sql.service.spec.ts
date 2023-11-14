import { TestBed, inject } from '@angular/core/testing';

import { AnalyzedSqlService } from './analyzed-sql.service';

describe('AnalyzedSqlService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AnalyzedSqlService]
    });
  });

  it('should be created', inject([AnalyzedSqlService], (service: AnalyzedSqlService) => {
    expect(service).toBeTruthy();
  }));
});
