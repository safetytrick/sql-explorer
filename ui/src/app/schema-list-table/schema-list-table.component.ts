import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { TagViewComponent } from '../tag-view/tag-view.component';
import { SchemaRollup } from '../api-types';

@Component({
    selector: 'app-schema-list-table',
    templateUrl: './schema-list-table.component.html',
    styleUrls: ['./schema-list-table.component.scss'],
    imports: [RouterLink, PicoViewComponent, TagViewComponent]
})
export class SchemaListTableComponent {
  @Input()
  schemas: SchemaRollup[] = [];
}
