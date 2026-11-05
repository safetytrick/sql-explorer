import {TestBed} from '@angular/core/testing';
import {ThemeService} from '../theme.service';
import {SettingsComponent} from './settings.component';

describe('SettingsComponent', () => {
  it('offers every theme and applies the one chosen', () => {
    const setCurrent = jasmine.createSpy('setCurrent');
    TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [{provide: ThemeService, useValue: {getCurrent: () => '', setCurrent}}]
    });
    const fixture = TestBed.createComponent(SettingsComponent);
    fixture.detectChanges();
    const radios = (fixture.nativeElement as HTMLElement).querySelectorAll('input[type=radio]');
    expect(radios.length).toBe(4);

    (radios[2] as HTMLInputElement).click();
    expect(setCurrent).toHaveBeenCalledWith('solarized-light');
  });
});
