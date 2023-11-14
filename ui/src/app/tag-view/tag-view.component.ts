import {Component, Input, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {Observable} from 'rxjs/internal/Observable';

@Component({
  selector: 'app-tag-view',
  templateUrl: './tag-view.component.html',
  styleUrls: ['./tag-view.component.scss']
})
export class TagViewComponent implements OnInit {

  @Input()
  path: string;

  @Input()
  tags: [] = null;

  constructor(public sqlService: AnalyzedSqlService) { }

  ngOnInit() {
    if (this.tags === null) {
      this.sqlService.getTags(this.path).subscribe(
        data => this.tags = data
      );
    }
  }

  public autocompleteTags = (text: string): Observable<any> => {
    return this.sqlService.autoCompleteTags(text);
  }

  addTag($event: any) {
    // todo handle complete by updating list and error with a toast
    this.sqlService.addTag($event.name, this.path).subscribe();
  }

  removeTag($event: any) {
    // todo handle complete by updating list and error with a toast
    this.sqlService.removeTag($event.id).subscribe();
  }



}
