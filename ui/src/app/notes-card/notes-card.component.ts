import {Component, EventEmitter, Input, OnChanges, Output} from '@angular/core';
import {HttpClient, HttpHeaders} from '@angular/common/http';
import {DatePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';

const endpoint = '/api/sql-explorer/';

/** A note as `/notes/path` returns it. */
export interface Note {
  id: number;
  path: string;
  text: string;
  createdOn: string | null;
  author: { email: string | null } | null;
}

/**
 * Notes on one thing — a table today, addressed by its tag path
 * (`/schema/{s}/table/{t}`). For the comments that never make it into the DDL
 * (sqlx-rw2.6).
 *
 * The backend lists and adds notes; it has no edit or delete, so neither does
 * this. Text is shown as written (pre-wrapped), not rendered as markdown: a
 * note is someone's words, and rendering them is a way to lose some.
 */
@Component({
  selector: 'app-notes-card',
  templateUrl: './notes-card.component.html',
  styleUrls: ['./notes-card.component.scss'],
  imports: [DatePipe, FormsModule]
})
export class NotesCardComponent implements OnChanges {
  @Input({required: true}) path!: string;
  /** The card's title; a column's notes say which column (sqlx-n5j.1). */
  @Input() heading = 'Notes';
  /** A note was saved here. */
  @Output() added = new EventEmitter<Note>();

  notes: Note[] = [];
  failed = false;
  adding = false;
  draft = '';
  saving = false;
  saveFailed = false;

  constructor(private http: HttpClient) {}

  ngOnChanges() {
    this.notes = [];
    this.failed = false;
    this.http.get<Note[]>(endpoint + 'notes/path', {params: {path: this.path}}).subscribe({
      next: notes => this.notes = [...(notes ?? [])].sort((a, b) => (a.createdOn ?? '').localeCompare(b.createdOn ?? '')),
      error: () => this.failed = true
    });
  }

  save() {
    const text = this.draft.trim();
    if (!text || this.saving) { return; }
    this.saving = true;
    this.saveFailed = false;
    // Form-encoded, not query params: a note can be long. URLSearchParams, not
    // HttpParams: Angular's codec leaves '+' unencoded, and in a form body '+'
    // decodes as a space, so "a+b" would be saved as "a b".
    const body = new URLSearchParams({text, path: this.path}).toString();
    this.http.post<Note>(endpoint + 'notes', body,
      {headers: new HttpHeaders({'Content-Type': 'application/x-www-form-urlencoded'})}).subscribe({
      next: note => {
        this.notes = [...this.notes, note];
        this.added.emit(note);
        this.draft = '';
        this.adding = false;
        this.saving = false;
      },
      error: () => { this.saving = false; this.saveFailed = true; }
    });
  }

  cancel() {
    this.adding = false;
    this.draft = '';
    this.saveFailed = false;
  }
}
