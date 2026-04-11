import {Component, OnDestroy, OnInit} from '@angular/core';
import {Author} from '../author';
import {ActivatedRoute} from '@angular/router';
import {Subscription} from 'rxjs/internal/Subscription';
import { MarkdownToHtmlPipe } from '../markdown-to-html.pipe';

@Component({
    selector: 'app-note-view',
    templateUrl: './note-view.component.html',
    styleUrls: ['./note-view.component.css'],
    imports: [MarkdownToHtmlPipe]
})
export class NoteViewComponent implements OnInit, OnDestroy {

  note: string = null;
  createdOn: Date = null;
  author: Author = null;
  private sub: Subscription;

  constructor(private route: ActivatedRoute) { }

  ngOnInit() {
    this.sub = this.route.data.subscribe(data => {
      this.note = data.note;
      this.createdOn = data.createdOn;
      this.author = data.author;
    });
  }

  ngOnDestroy() {
    this.sub.unsubscribe();
  }

}
