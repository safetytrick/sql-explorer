import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
    name: 'callback',
    pure: false
})
export class CallbackPipePipe implements PipeTransform {

  transform<T>(items: T[], callback: (item: T) => boolean): T[] {
    if (!items || !callback) {
      return items;
    }
    return items.filter(item => callback(item));
  }

}
